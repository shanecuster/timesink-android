//! `timesink-ffi`: the bridge between the Android app (Kotlin) and
//! `timesink-core` (Rust).
//!
//! ## How the bridge works
//!
//! Everything marked `#[uniffi::export]`, `#[derive(uniffi::Record)]` and so on
//! below becomes callable from Kotlin. At build time a tool called
//! `uniffi-bindgen` reads the compiled library and writes a Kotlin file
//! (`timesink_ffi.kt`) with matching classes and functions, so from the Kotlin
//! side it looks like ordinary Kotlin code:
//!
//! ```kotlin
//! val themes = listThemes()   // calls the Rust function below
//! ```
//!
//! ## What is exported so far
//!
//! * **Theme palettes** (`list_themes`, `default_theme_name`) -- pure data, so
//!   the phone uses exactly the same colours as the terminal app.
//!
//! * **The database** (`Database`) -- one object the app opens once. Through it
//!   the app reads the day summary (totals, categories, apps, hours, timeline,
//!   listening), and, in debug builds only, fills an empty database with made-up
//!   demo data so the screens can be tried before sync exists.
//! * **Category colours** (`category_color`) -- the same stable colour per
//!   category the terminal app uses.
//!
//! * **Other periods** (`period_summary`, `custom_range`) -- week, month, year,
//!   all time, calendar week, quarter and custom, each stepped back and forth.
//!
//! Later steps add sync and the phone's own usage.
//!
//! ## Why mirror types instead of exporting core's types directly?
//!
//! `timesink-core` knows nothing about UniFFI (it must stay usable by the
//! terminal app and the daemon too), so it cannot carry UniFFI's `derive`
//! attributes. Instead this crate defines small "mirror" types (`RgbColor`,
//! `ThemePalette`) and converts core's types into them with `From`
//! implementations. The glue stays here and core stays clean.

// This one line generates the low-level plumbing UniFFI needs (the
// C-compatible entry points the Kotlin side calls through). It must appear
// exactly once, at the crate root, before the other exports are used.
uniffi::setup_scaffolding!();

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard, RwLock, RwLockReadGuard};

use timesink_core::appmap::{self, AppMap};
use timesink_core::data::{Snapshot, View};
use timesink_core::db::{Db, MERGE_GAP_SECS};
use timesink_core::domains::DomainConfig;
use timesink_core::model::WindowSnapshot;
use timesink_core::period::{self, Range};
use timesink_core::syncdoc::ConfLists;
use timesink_core::theme::{self, Rgb, Theme};
use timesink_core::{demo, stats};
use timesink_sync::{self as sync, HttpRemote, SyncConfig};

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/// The one error type Kotlin sees.
///
/// UniFFI turns this into a Kotlin exception class. The readable text is in
/// `detail` (not `message`, because Kotlin's exception class already has a
/// property called `message` and a generated subclass declaring its own would
/// clash with it).
#[derive(Debug, uniffi::Error)]
pub enum FfiError {
    /// Something failed in timesink-core. `detail` is the full human-readable
    /// explanation, including each underlying cause.
    Failed {
        /// The explanation to show or log.
        detail: String,
    },
}

/// How an `FfiError` prints. Required for any error type: it is the text shown
/// when the error is displayed or logged.
impl std::fmt::Display for FfiError {
    /// Writes the error's detail text.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // `match` takes the value apart by variant; there is only one for now.
        match self {
            FfiError::Failed { detail } => write!(f, "{detail}"),
        }
    }
}

/// Marks `FfiError` as a proper Rust error type. All the required behaviour
/// already comes from `Debug` + `Display` above, so the body is empty.
impl std::error::Error for FfiError {}

/// Lets `?` convert core's `anyhow::Error` into an `FfiError` automatically.
///
/// With this in place, a function returning `Result<_, FfiError>` can call a
/// core function that returns `anyhow::Result<_>` and simply write `?` after
/// it: on failure, `?` calls this `from` to do the conversion.
impl From<anyhow::Error> for FfiError {
    /// Wraps an `anyhow::Error`, keeping its whole chain of causes.
    fn from(err: anyhow::Error) -> FfiError {
        // `{err:#}` is the "alternate" format for anyhow errors: it prints the
        // top-level message followed by every underlying cause.
        FfiError::Failed {
            detail: format!("{err:#}"),
        }
    }
}

// ---------------------------------------------------------------------------
// Theme palettes
// ---------------------------------------------------------------------------

/// One colour as three bytes (0-255). Mirror of core's `Rgb`.
///
/// `#[derive(uniffi::Record)]` makes this a plain data class on the Kotlin
/// side, with one property per field.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct RgbColor {
    /// Red, 0-255.
    pub r: u8,
    /// Green, 0-255.
    pub g: u8,
    /// Blue, 0-255.
    pub b: u8,
}

/// Converts core's colour into the mirror type Kotlin can receive.
impl From<Rgb> for RgbColor {
    /// Copies the three channels across.
    fn from(c: Rgb) -> RgbColor {
        RgbColor {
            r: c.0,
            g: c.1,
            b: c.2,
        }
    }
}

/// A complete colour theme. Mirror of core's `Theme`: the same named slots, so
/// the app and the terminal program look alike.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ThemePalette {
    /// The theme's display name, such as "Everforest".
    pub name: String,
    /// Background of the whole screen.
    pub background: RgbColor,
    /// Normal text.
    pub text: RgbColor,
    /// Quiet text: hints, axis labels, empty-state messages.
    pub muted: RgbColor,
    /// Box borders and empty bar tracks.
    pub border: RgbColor,
    /// Highlights: the selected tab, the title, the start of gradients.
    pub accent: RgbColor,
    /// The far end of the gradients.
    pub banner_end: RgbColor,
    /// A faint fill colour (empty heat-map cells).
    pub selection: RgbColor,
    /// Good / busy.
    pub ok: RgbColor,
    /// Attention.
    pub warn: RgbColor,
    /// Problems.
    pub danger: RgbColor,
    /// Colours for the category and application bars, ordered so neighbours
    /// differ the most.
    pub accents: Vec<RgbColor>,
}

/// Converts one of core's themes into the mirror type.
impl From<&Theme> for ThemePalette {
    /// Copies every slot, turning each core colour into an `RgbColor`.
    fn from(t: &Theme) -> ThemePalette {
        ThemePalette {
            name: t.name.to_string(),
            background: t.bg.into(),
            text: t.fg.into(),
            muted: t.muted.into(),
            border: t.border.into(),
            accent: t.accent.into(),
            banner_end: t.banner_end.into(),
            selection: t.selection.into(),
            ok: t.ok.into(),
            warn: t.warn.into(),
            danger: t.danger.into(),
            accents: t.accents.iter().map(|c| (*c).into()).collect(),
        }
    }
}

/// Every built-in theme, in the order the terminal app cycles through them.
#[uniffi::export]
pub fn list_themes() -> Vec<ThemePalette> {
    // `.iter().map(..).collect()` builds a new list by converting each theme.
    theme::THEMES.iter().map(ThemePalette::from).collect()
}

/// The name of the theme to use when none has been chosen yet.
#[uniffi::export]
pub fn default_theme_name() -> String {
    theme::DEFAULT_THEME.to_string()
}

// ---------------------------------------------------------------------------
// Time zones
// ---------------------------------------------------------------------------

/// How the phone tells Rust the local time zone.
///
/// The totals are per *local* day, so Rust needs to know how far local time is
/// from UTC at any moment (that changes with daylight saving). Rather than ship
/// a time-zone database in Rust, Kotlin implements this small interface with
/// `java.util.TimeZone` and hands it over; Rust calls it back whenever it needs
/// an offset. `with_foreign` is what allows Kotlin (the "foreign" language) to
/// implement a Rust trait.
#[uniffi::export(with_foreign)]
pub trait ZoneOffsets: Send + Sync {
    /// Seconds to add to a UTC time (seconds since 1970) to get local time then.
    fn offset_at(&self, epoch_secs: i64) -> i64;
}

// ---------------------------------------------------------------------------
// Day summary records
// ---------------------------------------------------------------------------

/// A name with an amount of time, used for categories and listening players.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NameSecs {
    /// The category or player name.
    pub name: String,
    /// Seconds spent.
    pub secs: i64,
    /// Which theme accent colour to use (see `category_color`); `None` for a
    /// name with no colour of its own, drawn in the theme's grey.
    pub color_index: Option<u32>,
}

/// Time in one application, after display names have been applied.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppSecs {
    /// Short display name, such as "wezterm".
    pub name: String,
    /// The category the app belongs to.
    pub category: String,
    /// Seconds spent.
    pub secs: i64,
    /// Accent colour index of the app's category.
    pub color_index: Option<u32>,
    /// The raw app ids (Android package names) merged into this row. They are
    /// what `set_app` needs to rename the app or move it to another category.
    pub raws: Vec<String>,
}

/// Time on one website (recorded by the desktop browser extension and synced).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SiteSecs {
    /// Friendly site name, such as "github".
    pub name: String,
    /// The site's category.
    pub category: String,
    /// Seconds spent.
    pub secs: i64,
    /// Accent colour index of the site's category.
    pub color_index: Option<u32>,
}

/// One coloured stretch of today, for the timeline strip.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TimelineSpan {
    /// Seconds after local midnight.
    pub start: i64,
    /// Seconds after local midnight.
    pub end: i64,
    /// The category the time belongs to.
    pub category: String,
    /// Accent colour index of that category.
    pub color_index: Option<u32>,
}

/// Everything the Today screen shows, worked out in Rust.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DaySummary {
    /// Local day number (days since 1970-01-01); Kotlin turns it into a date.
    pub day: i64,
    /// A short description of the day shown, such as `Tue Nov 10, 2026`.
    pub label: String,
    /// Is there an earlier day to step back to?
    pub can_back: bool,
    /// Is there a later day to step forward to (false when this is today)?
    pub can_forward: bool,
    /// Total active seconds today.
    pub total_secs: i64,
    /// Total seconds of media listening today.
    pub listening_secs: i64,
    /// Seconds per category, biggest first.
    pub categories: Vec<NameSecs>,
    /// Seconds per application, biggest first.
    pub apps: Vec<AppSecs>,
    /// Active seconds in each of the 24 hours (always 24 entries).
    pub hours: Vec<i64>,
    /// Seconds since local midnight right now (for the "now" marker).
    pub now_secs: i64,
    /// Coloured stretches of today, in order.
    pub timeline: Vec<TimelineSpan>,
    /// Seconds of listening per player, biggest first.
    pub listening: Vec<NameSecs>,
    /// Seconds per website, biggest first (empty when no site time exists).
    pub sites: Vec<SiteSecs>,
}

/// The periods the dashboard can show, in the terminal app's tab order.
/// Mirror of core's `View`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Period {
    /// Just one day (today, or a day stepped back to).
    Today,
    /// The last 7 days.
    Week,
    /// The last 30 days.
    Month,
    /// The last 365 days, summed by month.
    Year,
    /// Everything recorded, summed by month once it is long.
    All,
    /// A calendar week, Monday to Sunday.
    CalWeek,
    /// A calendar quarter.
    Quarter,
    /// Days chosen by the user.
    Custom,
}

/// Converts the bridge's period into core's view.
impl From<Period> for View {
    /// One-to-one: each period is the view of the same name.
    fn from(p: Period) -> View {
        match p {
            Period::Today => View::Today,
            Period::Week => View::Week,
            Period::Month => View::Month,
            Period::Year => View::Year,
            Period::All => View::All,
            Period::CalWeek => View::CalWeek,
            Period::Quarter => View::Quarter,
            Period::Custom => View::Custom,
        }
    }
}

/// A run of whole local days chosen for the Custom period, both ends included.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct CustomSpan {
    /// The first local day number.
    pub first: i64,
    /// The last local day number (never before `first`, never after today).
    pub last: i64,
}

/// One day of a longer period: its totals and its activity hour by hour (a row
/// of the heat map).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DayInfo {
    /// Local day number (days since 1970-01-01).
    pub day: i64,
    /// Total active seconds that day.
    pub total: i64,
    /// Seconds of media listening that day.
    pub listening: i64,
    /// Active seconds in each of the 24 hours.
    pub hours: Vec<i64>,
}

/// One calendar month of a long period.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MonthInfo {
    /// Calendar year.
    pub year: i32,
    /// Month number, 1 to 12.
    pub month: u32,
    /// Active seconds in the month (within the period shown).
    pub total: i64,
    /// Days of the month inside the period.
    pub days: i64,
    /// Days that had any activity.
    pub active_days: i64,
}

/// Everything a longer period's screen shows, worked out in Rust.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PeriodSummary {
    /// A short description of the days covered, such as `Nov 3-9, 2026`.
    pub label: String,
    /// Is there an earlier period to step back to?
    pub can_back: bool,
    /// Is there a later period to step forward to?
    pub can_forward: bool,
    /// Show months instead of a day-by-hour grid (too many days to list)?
    pub by_month: bool,
    /// Total active seconds in the period.
    pub total_secs: i64,
    /// Total seconds of media listening in the period.
    pub listening_secs: i64,
    /// Days in the period that had any activity.
    pub active_days: i64,
    /// Seconds per category, biggest first.
    pub categories: Vec<NameSecs>,
    /// Seconds per application, biggest first.
    pub apps: Vec<AppSecs>,
    /// Seconds of listening per player, biggest first.
    pub listening: Vec<NameSecs>,
    /// One row per day, oldest first.
    pub days: Vec<DayInfo>,
    /// One row per calendar month, oldest first.
    pub months: Vec<MonthInfo>,
    /// Seconds per website, biggest first (empty when no site time exists).
    pub sites: Vec<SiteSecs>,
}

/// Where the CouchDB server is and how to log in, typed on the sync screen.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SyncServer {
    /// Server address, such as `http://myserver:5984`.
    pub url: String,
    /// Database name on the server.
    pub database: String,
    /// CouchDB user.
    pub user: String,
    /// CouchDB password.
    pub password: String,
}

/// What one sync did.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SyncOutcome {
    /// Days of this phone's data sent to the server.
    pub uploaded: u32,
    /// Days removed from the server.
    pub removed: u32,
    /// Days of other devices' data brought in.
    pub downloaded: u32,
    /// Things worth a look that did not stop the sync.
    pub warnings: Vec<String>,
    /// Whether this phone's app names and categories were sent.
    pub lists_uploaded: bool,
    /// How many other computers' name lists were received.
    pub lists_downloaded: u32,
}

/// How syncing has been going, for the sync screen.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SyncStatus {
    /// When the last successful sync finished (Unix seconds), if ever.
    pub last_ok: Option<i64>,
    /// When the last failed attempt happened, if the latest attempt failed.
    pub last_failed: Option<i64>,
    /// Days of this phone's data not yet sent.
    pub pending_days: u32,
}

/// What happened to the foreground app, as Android's usage log reports it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum UsageKind {
    /// An app came to the front (Android's "activity resumed").
    Resumed,
    /// An app left the front (Android's "activity paused").
    Paused,
    /// The screen went off or was locked: whatever was in front stopped.
    ScreenOff,
    /// Something that is not an app took the front (the home screen, the
    /// system bar): whatever was in front stopped, and nothing is counted.
    Nothing,
}

/// One entry of Android's usage log.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct UsageEvent {
    /// When it happened, in milliseconds since 1970.
    pub ts_ms: i64,
    /// The app's package name, such as `com.android.chrome` (unused for
    /// `ScreenOff` and `Nothing`).
    pub package_name: String,
    /// What happened.
    pub kind: UsageKind,
}

/// An app's own name as Android shows it, such as `Google Maps`.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppLabel {
    /// The package name.
    pub package_name: String,
    /// The label the user sees under the app's icon.
    pub label: String,
}

/// What importing the usage log did.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct UsageImport {
    /// Stretches of app use that were recorded (new rows or longer rows).
    pub recorded: u32,
    /// The app still in front at the end of the import, if any. Kotlin hands
    /// it back with the next import so the stretch carries on unbroken.
    pub open_package: Option<String>,
}

/// The built-in application names and categories used until the phone has its
/// own editable list. It is the same shape as the terminal app's `apps.conf`:
/// raw class, display name, category.
const DEFAULT_APPS: &str = "\
org.wezfurlong.wezterm   wezterm        Terminal
zen                      zen            Browser
eu.betterbird.Betterbird betterbird     Email
md.obsidian.Obsidian     obsidian       Notes
discord                  discord        Chat
hexchat                  hexchat        Chat
cider                    cider          Music
io.github.lgse.Strata*   strata         Files
org.gnome.Nautilus       nautilus       Files
jetbrains-studio         android-studio Development
bitwarden                bitwarden      Passwords
com.gabm.satty           satty          Utilities
com.android.chrome       chrome         Browser
org.mozilla.firefox      firefox        Browser
com.google.android.gm    gmail          Email
com.google.android.apps.messaging messages Chat
com.discord              discord        Chat
com.spotify.music        spotify        Music
com.google.android.youtube youtube      Video
com.google.android.apps.maps maps       Utilities
com.android.settings     settings       Utilities
";

/// Built-in site names and categories (the `domains.conf` format: site, display
/// name, category). Used for the Sites panel until sites become editable.
const DEFAULT_SITES: &str = "\
github.com         github         Development
stackoverflow.com  stackoverflow  Development
wikipedia.org      wikipedia      Reference
youtube.com        youtube        Video
reddit.com         reddit         Social
espn.com           espn           Sports
";

// ---------------------------------------------------------------------------
// The database
// ---------------------------------------------------------------------------

/// The phone's timesink database, opened once and shared with Kotlin.
///
/// Kotlin holds a reference to this object and calls its methods. Because
/// Kotlin may call from several threads, the real database handle sits behind
/// a `Mutex`: a thread must lock it before using it, so two threads never use
/// the SQLite connection at the same instant.
#[derive(uniffi::Object)]
pub struct Database {
    /// The real database from timesink-core, behind the lock.
    db: Mutex<Db>,
    /// Application names and categories: the built-in list followed by the
    /// user's own edits. Behind a lock because `set_app` replaces it while
    /// other threads may be reading.
    map: RwLock<AppMap>,
    /// Where the user's own app edits are saved (an `apps.conf` file).
    conf_path: PathBuf,
    /// Site names and categories for the Sites panel (built-in list plus the ones
    /// shared by other computers). Behind a lock because a sync replaces it.
    sites: RwLock<DomainConfig>,
    /// The database file, so a sync can use a connection of its own.
    path: PathBuf,
    /// This phone's name in the data (the device name typed in the app).
    device_id: String,
}

/// Helpers used only inside this crate (not exported to Kotlin).
impl Database {
    /// Wraps an open core database together with the built-in app list.
    fn from_db(db: Db, conf_path: PathBuf, path: PathBuf, device_id: String) -> Database {
        // Built before the database goes behind its lock: the lists include the
        // ones other computers have shared (kept in the database).
        let map = build_map(&conf_path, &db);
        let sites = build_sites(&db);
        Database {
            db: Mutex::new(db),
            map: RwLock::new(map),
            conf_path,
            sites: RwLock::new(sites),
            path,
            device_id,
        }
    }

    /// Builds the app and site lists again from the built-in lists, the phone's
    /// own `apps.conf` and the lists other computers shared. Called after anything
    /// that could change them.
    fn rebuild_lists(&self) -> Result<(), FfiError> {
        let (map, sites) = {
            let db = self.lock()?;
            (build_map(&self.conf_path, &db), build_sites(&db))
        };
        let poisoned = || FfiError::Failed {
            detail: "a list lock was poisoned by an earlier panic".to_string(),
        };
        *self.map.write().map_err(|_| poisoned())? = map;
        *self.sites.write().map_err(|_| poisoned())? = sites;
        Ok(())
    }

    /// Reads the app list. A poisoned lock is reported rather than crashing.
    fn map(&self) -> Result<RwLockReadGuard<'_, AppMap>, FfiError> {
        self.map.read().map_err(|_| FfiError::Failed {
            detail: "the app list lock was poisoned by an earlier panic".to_string(),
        })
    }

    /// Takes the lock and returns a guard giving access to the `Db`. The guard
    /// unlocks automatically when it goes out of scope. A lock is "poisoned" if
    /// a thread panicked while holding it; that is reported as an error rather
    /// than crashing the app.
    fn lock(&self) -> Result<MutexGuard<'_, Db>, FfiError> {
        self.db.lock().map_err(|_| FfiError::Failed {
            detail: "the database lock was poisoned by an earlier panic".to_string(),
        })
    }

    /// The position of a category in the app list, which decides its colour.
    fn color_index(&self, category: &str) -> Option<u32> {
        let map = self.map.read().ok()?;
        map.categories()
            .iter()
            .position(|c| c == category)
            .map(|i| i as u32)
    }

    /// The same, for a site category.
    fn site_color_index(&self, category: &str) -> Option<u32> {
        let config = self.sites.read().ok()?;
        config
            .sites
            .categories()
            .iter()
            .position(|c| c == category)
            .map(|i| i as u32)
    }
}

#[uniffi::export]
impl Database {
    /// Opens (creating if needed) the database file at `path`. `device_id` names
    /// this phone in the data; it is stored on every row it records.
    #[uniffi::constructor]
    ///
    /// `apps_conf_path` is the file where the user's own app names and
    /// categories are saved (it need not exist yet).
    pub fn open(
        path: String,
        device_id: String,
        apps_conf_path: String,
    ) -> Result<Arc<Database>, FfiError> {
        let db = Db::open(Path::new(&path), &device_id)?;
        Ok(Arc::new(Database::from_db(
            db,
            PathBuf::from(apps_conf_path),
            PathBuf::from(path),
            device_id,
        )))
    }

    /// Syncs with the CouchDB server: sends this phone's changed days and brings
    /// in the other devices' days (never window titles; the phone has none).
    ///
    /// A sync can take seconds on a slow network, so it uses its own connection
    /// to the database file instead of the shared one; the dashboard keeps
    /// loading while it runs. The outcome is remembered (see `sync_status`).
    /// Kotlin must call this off the main thread.
    pub fn sync_now(&self, server: SyncServer) -> Result<SyncOutcome, FfiError> {
        let url = server.url.trim().trim_end_matches('/').to_string();
        if !(url.starts_with("http://") || url.starts_with("https://")) {
            return Err(FfiError::Failed {
                detail: "the server address must start with http:// or https://".to_string(),
            });
        }
        let config = SyncConfig {
            url,
            database: server.database.trim().to_string(),
            user: server.user.trim().to_string(),
            password: server.password,
        };
        if config.database.is_empty() || config.user.is_empty() || config.password.is_empty() {
            return Err(FfiError::Failed {
                detail: "fill in the database, user and password first".to_string(),
            });
        }
        let db = Db::open(&self.path, &self.device_id)?;
        let mut remote = HttpRemote::new(config);
        // Share the phone's own names and categories too (the phone has no site list
        // of its own to share).
        let (apps, apps_updated) = read_user_conf(&self.conf_path);
        let own = ConfLists {
            apps,
            apps_updated,
            domains: String::new(),
            domains_updated: 0,
        };
        let outcome = sync::run_with_lists(&db, &mut remote, Some(&own));
        // Remember how it went; failing to write the note is not worth failing over.
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0);
        let _ = match &outcome {
            Ok(_) => db
                .set_sync_state("last_ok", &now.to_string())
                .and_then(|_| db.set_sync_state("last_failed", "")),
            Err(_) => db.set_sync_state("last_failed", &now.to_string()),
        };
        let report = outcome?;
        // Lists other computers shared may have just arrived: use them from now on.
        self.rebuild_lists()?;
        Ok(SyncOutcome {
            uploaded: report.uploaded as u32,
            removed: report.removed as u32,
            downloaded: report.downloaded as u32,
            warnings: report.warnings,
            lists_uploaded: report.lists_uploaded,
            lists_downloaded: report.lists_downloaded as u32,
        })
    }

    /// When syncing last worked or failed, and how much is waiting to be sent.
    pub fn sync_status(&self) -> Result<SyncStatus, FfiError> {
        let db = self.lock()?;
        let parse = |key: &str| -> Result<Option<i64>, FfiError> {
            Ok(db.sync_state(key)?.and_then(|v| v.parse::<i64>().ok()))
        };
        Ok(SyncStatus {
            last_ok: parse("last_ok")?,
            last_failed: parse("last_failed")?,
            pending_days: db.dirty_count()? as u32,
        })
    }

    /// Records stretches of app use from Android's usage log.
    ///
    /// `events` are the log entries since the last import (plus, if an app was
    /// still in front then, a `Resumed` for it at the start). They are turned
    /// into focus stretches here, in Rust, so the rules are tested. A stretch
    /// still going at `end_ms` is cut there and its app is returned as
    /// `open_package`. Stretches join the previous row when they carry on
    /// from it. `labels` give the apps' own names; an app with no name of its
    /// own yet gets its label, in the category "Other", until the user edits it.
    pub fn record_usage(
        &self,
        events: Vec<UsageEvent>,
        labels: Vec<AppLabel>,
        end_ms: i64,
    ) -> Result<UsageImport, FfiError> {
        let (stretches, open_package) = usage_stretches(events, end_ms);
        let mut recorded = 0u32;
        {
            let db = self.lock()?;
            for (package, start, end) in &stretches {
                let snapshot = WindowSnapshot {
                    app_class: package.clone(),
                    title: String::new(),
                    workspace: "phone".to_string(),
                };
                match db.find_mergeable(*start, &snapshot, MERGE_GAP_SECS)? {
                    // Carries on from the last row: just make that one longer.
                    Some(id) => db.set_end(id, *end)?,
                    None => {
                        let id = db.insert_open(*start, &snapshot)?;
                        db.finish(id, *end)?;
                    }
                }
                recorded += 1;
            }
        }
        // Give apps their own names, without touching ones already named.
        let mut named_any = false;
        for l in &labels {
            let known = self.map()?.lookup(&l.package_name).mapped;
            if !known
                && appmap::append_entry(&self.conf_path, &l.package_name, &l.label, "Other").is_ok()
            {
                named_any = true;
            }
        }
        if named_any {
            self.rebuild_lists()?;
        }
        Ok(UsageImport {
            recorded,
            open_package,
        })
    }

    /// The devices that have data in the database (this phone, and any synced
    /// from elsewhere), for the device filter.
    pub fn devices(&self) -> Result<Vec<String>, FfiError> {
        Ok(self.lock()?.devices()?)
    }

    /// The category names the app list knows, in colour order. "Other" is not
    /// included; the editor adds it and the "new category" action itself.
    pub fn categories(&self) -> Result<Vec<String>, FfiError> {
        Ok(self.map()?.categories())
    }

    /// Gives every app id in `raws` the display name and category, saving the
    /// change to the phone's `apps.conf` and re-sorting all history at once.
    /// Nothing recorded is rewritten; only the names shown change.
    pub fn set_app(
        &self,
        raws: Vec<String>,
        display: String,
        category: String,
    ) -> Result<(), FfiError> {
        // Check every line first so a bad name saves nothing at all.
        for raw in &raws {
            if appmap::entry_line(raw, &display, &category).is_none() {
                return Err(FfiError::Failed {
                    detail: "the name and category must not be empty".to_string(),
                });
            }
        }
        for raw in &raws {
            appmap::append_entry(&self.conf_path, raw, &display, &category)?;
        }
        self.rebuild_lists()
    }

    /// True when nothing has been recorded or synced yet.
    pub fn is_empty(&self) -> Result<bool, FfiError> {
        Ok(self.lock()?.earliest_start()?.is_none())
    }

    /// Fills an EMPTY database with `days` days of made-up data ending at `now`.
    /// Returns how many rows were made. It refuses to touch a database that
    /// already has data, so real data can never be mixed with demo data.
    ///
    /// The Kotlin side only calls this in debug builds.
    pub fn fill_demo_data(
        &self,
        now: i64,
        days: i64,
        seed: u64,
        zones: Arc<dyn ZoneOffsets>,
    ) -> Result<u32, FfiError> {
        let db = self.lock()?;
        // Never pile demo data on top of existing rows.
        if db.earliest_start()?.is_some() {
            return Err(FfiError::Failed {
                detail: "the database already has data; demo data was not added".to_string(),
            });
        }
        let data = demo::generate(now, days, seed, zones.offset_at(now));
        // Each focus row is opened and then closed at its end time, which is
        // how the real recorder writes them.
        for i in &data.intervals {
            let id = db.insert_open(
                i.start,
                &WindowSnapshot {
                    app_class: i.class.clone(),
                    title: i.detail.clone(),
                    workspace: "1".to_string(),
                },
            )?;
            db.finish(id, i.end)?;
        }
        for (player, start, end) in &data.listening {
            let id = db.insert_listen_open(*start, player)?;
            db.finish_listen(id, *end)?;
        }
        for (site, start, end) in &data.sites {
            db.insert_domain_stretch(site, *start, *end)?;
        }
        Ok((data.intervals.len() + data.listening.len() + data.sites.len()) as u32)
    }

    /// Everything the Today screen needs for the day `offset` days from today
    /// (0 = today, -1 = yesterday, ...), as of `now`.
    pub fn day_summary(
        &self,
        offset: i64,
        device: Option<String>,
        now: i64,
        zones: Arc<dyn ZoneOffsets>,
    ) -> Result<DaySummary, FfiError> {
        // The time-zone callback as the plain function core expects.
        let tz = |ts: i64| zones.offset_at(ts);
        let db = self.lock()?;
        let loaded = self.load(&db, View::Today, offset, None, device.as_deref(), now, &tz)?;
        let snap = &loaded.snapshot;

        // Twenty-four hour slots for the day: the only day row.
        let mut hours = vec![0i64; 24];
        if let Some(day) = snap.days.last() {
            hours.copy_from_slice(&day.hours);
        }
        Ok(DaySummary {
            day: snap.range.last,
            label: loaded.label,
            can_back: loaded.can_back,
            can_forward: loaded.can_forward,
            total_secs: snap.total,
            listening_secs: snap.listening_total,
            categories: self.category_rows(snap),
            apps: self.app_rows(snap),
            hours,
            now_secs: snap.now_secs,
            timeline: snap
                .timeline
                .iter()
                .map(|b| TimelineSpan {
                    start: b.start,
                    end: b.end,
                    category: b.category.clone(),
                    color_index: self.color_index(&b.category),
                })
                .collect(),
            listening: listening_rows(snap),
            sites: self.site_rows(snap),
        })
    }

    /// Everything a longer period's screen needs. `offset` steps back from the
    /// current period (0 = the current one, -1 = the one before, ...).
    /// `custom` is the days chosen for `Period::Custom` (the last 30 days when
    /// none has been chosen); it is ignored for the other periods.
    pub fn period_summary(
        &self,
        period: Period,
        offset: i64,
        custom: Option<CustomSpan>,
        device: Option<String>,
        now: i64,
        zones: Arc<dyn ZoneOffsets>,
    ) -> Result<PeriodSummary, FfiError> {
        let tz = |ts: i64| zones.offset_at(ts);
        let db = self.lock()?;
        let custom = custom.map(|c| Range::new(c.first, c.last));
        let loaded = self.load(
            &db,
            period.into(),
            offset,
            custom,
            device.as_deref(),
            now,
            &tz,
        )?;
        let snap = &loaded.snapshot;
        Ok(PeriodSummary {
            label: loaded.label,
            can_back: loaded.can_back,
            can_forward: loaded.can_forward,
            by_month: snap.by_month(),
            total_secs: snap.total,
            listening_secs: snap.listening_total,
            active_days: snap.days.iter().filter(|d| d.total > 0).count() as i64,
            categories: self.category_rows(snap),
            apps: self.app_rows(snap),
            listening: listening_rows(snap),
            days: snap
                .days
                .iter()
                .map(|d| DayInfo {
                    day: d.day,
                    total: d.total,
                    listening: d.listening,
                    hours: d.hours.to_vec(),
                })
                .collect(),
            months: snap
                .months
                .iter()
                .map(|m| MonthInfo {
                    year: m.year,
                    month: m.month,
                    total: m.total,
                    days: m.days,
                    active_days: m.active_days,
                })
                .collect(),
            sites: self.site_rows(snap),
        })
    }
}

/// A period's numbers together with the facts the screen needs about stepping.
struct Loaded {
    /// The numbers.
    snapshot: Snapshot,
    /// A short description of the days covered.
    label: String,
    /// Is there an earlier period?
    can_back: bool,
    /// Is there a later period?
    can_forward: bool,
}

/// More helpers used only inside this crate.
impl Database {
    /// Loads the numbers for `view`, `offset` periods back, and works out whether
    /// the user can step further either way. Shared by `day_summary` and
    /// `period_summary` so the two follow the same stepping rules as the
    /// terminal app (which are in core).
    // Eight inputs, all of them plain facts about one request; a wrapper struct
    // would only rename them.
    #[allow(clippy::too_many_arguments)]
    fn load(
        &self,
        db: &Db,
        view: View,
        offset: i64,
        custom: Option<Range>,
        device: Option<&str>,
        now: i64,
        tz: &dyn Fn(i64) -> i64,
    ) -> Result<Loaded, FfiError> {
        let today = stats::local_day(now, tz);
        // The first recorded day, which the periods may not step before.
        let earliest = db.earliest_start()?.map(|t| stats::local_day(t, tz));
        let range = view.range(offset, today, earliest, custom);
        let mut snapshot = {
            let map = self.map()?;
            Snapshot::load(db, &map, view, range, device, now, tz)?
        };
        // Website time comes from the same database (synced from the desktop).
        {
            let sites = self.sites.read().map_err(|_| FfiError::Failed {
                detail: "the site list lock was poisoned by an earlier panic".to_string(),
            })?;
            snapshot.attach_sites(db, &sites, device, now, tz)?;
        }
        Ok(Loaded {
            snapshot,
            label: if view == View::All {
                "All time".to_string()
            } else {
                view.label(range)
            },
            can_back: view.step(offset, -1, today, earliest, custom).is_some(),
            can_forward: view.step(offset, 1, today, earliest, custom).is_some(),
        })
    }

    /// The category bars of a snapshot, with their colour slots.
    fn category_rows(&self, snap: &Snapshot) -> Vec<NameSecs> {
        snap.categories
            .iter()
            .map(|(name, secs)| NameSecs {
                name: name.clone(),
                secs: *secs,
                color_index: self.color_index(name),
            })
            .collect()
    }

    /// The application bars of a snapshot, with their category colour slots.
    fn app_rows(&self, snap: &Snapshot) -> Vec<AppSecs> {
        snap.apps
            .iter()
            .map(|a| AppSecs {
                name: a.display.clone(),
                category: a.category.clone(),
                secs: a.secs,
                color_index: self.color_index(&a.category),
                raws: a.raws.clone(),
            })
            .collect()
    }

    /// The website bars of a snapshot, with their category colour slots.
    fn site_rows(&self, snap: &Snapshot) -> Vec<SiteSecs> {
        snap.sites
            .iter()
            .map(|s| SiteSecs {
                name: s.display.clone(),
                category: s.category.clone(),
                secs: s.secs,
                color_index: self.site_color_index(&s.category),
            })
            .collect()
    }
}

/// Turns Android's usage log entries into stretches of app use:
/// `(package, start_secs, end_secs)`, oldest first, never overlapping.
///
/// An app coming to the front ends whatever was in front before it; the screen
/// going off, or something that is not an app, ends it too. A stretch still
/// open at `end_ms` is cut there and its package is returned so the next
/// import can carry on from it. Two stretches of the same app with at most
/// `MERGE_GAP_SECS` between them are joined (moving between an app's own
/// screens leaves tiny gaps).
fn usage_stretches(
    mut events: Vec<UsageEvent>,
    end_ms: i64,
) -> (Vec<(String, i64, i64)>, Option<String>) {
    // Android lists events in time order already; sorting is cheap insurance.
    events.sort_by_key(|e| e.ts_ms);
    let mut out: Vec<(String, i64, i64)> = Vec::new();
    // The app in front now and when it came to the front (milliseconds).
    let mut open: Option<(String, i64)> = None;

    // Closes the open stretch at `at_ms`, joining it to the one before if it
    // is the same app and nearly touching.
    fn close(open: &mut Option<(String, i64)>, at_ms: i64, out: &mut Vec<(String, i64, i64)>) {
        if let Some((package, start_ms)) = open.take() {
            let (start, end) = (start_ms.div_euclid(1000), at_ms.div_euclid(1000));
            if end <= start {
                return;
            }
            match out.last_mut() {
                Some(last) if last.0 == package && start - last.2 <= MERGE_GAP_SECS => {
                    last.2 = last.2.max(end);
                }
                _ => out.push((package, start, end)),
            }
        }
    }

    for e in events {
        match e.kind {
            UsageKind::Resumed => {
                // The same app resuming again changes nothing.
                if open.as_ref().is_some_and(|(p, _)| *p == e.package_name) {
                    continue;
                }
                close(&mut open, e.ts_ms, &mut out);
                open = Some((e.package_name, e.ts_ms));
            }
            UsageKind::Paused => {
                // Only the app in front can leave the front.
                if open.as_ref().is_some_and(|(p, _)| *p == e.package_name) {
                    close(&mut open, e.ts_ms, &mut out);
                }
            }
            UsageKind::ScreenOff | UsageKind::Nothing => close(&mut open, e.ts_ms, &mut out),
        }
    }
    // Cut whatever is still going at the end, and remember the app.
    let still = open.as_ref().map(|(p, _)| p.clone());
    close(&mut open, end_ms, &mut out);
    (out, still)
}

/// The phone's own `apps.conf` text and when it last changed (Unix seconds); empty
/// and 0 when there is no file yet.
fn read_user_conf(conf_path: &Path) -> (String, i64) {
    let text = std::fs::read_to_string(conf_path).unwrap_or_default();
    let changed = std::fs::metadata(conf_path)
        .and_then(|m| m.modified())
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map_or(0, |d| d.as_secs() as i64);
    (text, changed)
}

/// The app list: the built-in apps, the phone's own edits, and the lists other
/// computers shared. For the same app the list changed most recently wins (the
/// built-in list counts as oldest of all); colours follow the order built-in, own,
/// then others.
fn build_map(conf_path: &Path, db: &Db) -> AppMap {
    let (user, user_updated) = read_user_conf(conf_path);
    let remote = db.remote_confs().unwrap_or_default();
    let mut layers: Vec<(&str, i64)> = vec![(DEFAULT_APPS, 0), (user.as_str(), user_updated)];
    layers.extend(
        remote
            .iter()
            .map(|(_, l)| (l.apps.as_str(), l.apps_updated)),
    );
    AppMap::from_layers(&layers).with_browser_defaults()
}

/// The site list: the built-in sites plus the ones other computers shared, newest
/// change winning.
fn build_sites(db: &Db) -> DomainConfig {
    let remote = db.remote_confs().unwrap_or_default();
    let mut layers: Vec<(&str, i64)> = vec![(DEFAULT_SITES, 0)];
    layers.extend(
        remote
            .iter()
            .map(|(_, l)| (l.domains.as_str(), l.domains_updated)),
    );
    DomainConfig::default().with_site_layers(&layers)
}

/// The listening rows of a snapshot (players have no colour slot of their own).
fn listening_rows(snap: &Snapshot) -> Vec<NameSecs> {
    snap.listening
        .iter()
        .map(|(name, secs)| NameSecs {
            name: name.clone(),
            secs: *secs,
            color_index: None,
        })
        .collect()
}

/// Checks two typed dates and turns them into a custom range.
///
/// Dates are written `YYYY-MM-DD`. An empty `to` means "until today"; the two are
/// swapped if given the wrong way round; an end after today is cut back to today;
/// and a start in the future is refused. These are the same rules as the terminal
/// app's `c` prompt.
#[uniffi::export]
pub fn custom_range(
    from: String,
    to: String,
    now: i64,
    zones: Arc<dyn ZoneOffsets>,
) -> Result<CustomSpan, FfiError> {
    let today = stats::local_day(now, &|ts| zones.offset_at(ts));
    let bad = |what: &str| FfiError::Failed {
        detail: format!("{what}: type a date like 2026-10-01"),
    };
    let first = period::parse_date(&from).ok_or_else(|| bad("the first day"))?;
    // An empty end means "until today". A start after today is refused here,
    // before the two days are put in order: `Range::new` would otherwise swap a
    // future start with today and quietly accept it.
    let last = if to.trim().is_empty() {
        if first > today {
            return Err(FfiError::Failed {
                detail: "that range starts in the future".to_string(),
            });
        }
        today
    } else {
        period::parse_date(&to).ok_or_else(|| bad("the last day"))?
    };
    let range = Range::new(first, last);
    if range.first > today {
        return Err(FfiError::Failed {
            detail: "that range starts in the future".to_string(),
        });
    }
    Ok(CustomSpan {
        first: range.first,
        last: range.last.min(today),
    })
}

/// The colour for a category (or `None`, a name with no colour) in the named
/// theme: the same stable colour the terminal app draws it in.
#[uniffi::export]
pub fn category_color(theme_name: String, color_index: Option<u32>) -> RgbColor {
    theme::find_or_default(&theme_name)
        .category_color(color_index.map(|i| i as usize))
        .into()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The list is not empty and starts with the default theme, as the app
    /// relies on that when nothing has been chosen.
    #[test]
    fn themes_start_with_the_default() {
        let themes = list_themes();
        assert!(!themes.is_empty());
        assert_eq!(themes[0].name, default_theme_name());
    }

    /// Every theme has bar colours, because the dashboard picks bar colours by
    /// position in `accents`.
    #[test]
    fn every_theme_has_bar_colours() {
        for t in list_themes() {
            assert!(!t.accents.is_empty(), "{} has no accents", t.name);
        }
    }

    /// Colours survive the conversion channel for channel.
    #[test]
    fn colours_convert_unchanged() {
        let c: RgbColor = Rgb(1, 2, 3).into();
        assert_eq!((c.r, c.g, c.b), (1, 2, 3));
    }

    /// Errors keep the whole chain of causes in their text.
    #[test]
    fn errors_keep_their_causes() {
        let err = anyhow::anyhow!("inner").context("outer");
        let FfiError::Failed { detail } = FfiError::from(err);
        assert_eq!(detail, "outer: inner");
    }

    /// A time zone that is always UTC, so the tests do not depend on where they run.
    struct Utc;

    impl ZoneOffsets for Utc {
        /// No offset at any time.
        fn offset_at(&self, _epoch_secs: i64) -> i64 {
            0
        }
    }

    /// A Friday afternoon (UTC), after the demo data's morning work block.
    const NOW: i64 = 20_000 * 86_400 + 14 * 3_600;

    /// A path for a throwaway `apps.conf`, different for every test.
    fn temp_conf() -> PathBuf {
        use std::sync::atomic::{AtomicU32, Ordering};
        static NEXT: AtomicU32 = AtomicU32::new(0);
        std::env::temp_dir().join(format!(
            "timesink-ffi-test-{}-{}.conf",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ))
    }

    /// A fresh in-memory database, wrapped the way Kotlin would hold it.
    fn memory_database() -> Database {
        Database::from_db(
            Db::open_in_memory("phone").unwrap(),
            temp_conf(),
            PathBuf::new(),
            "phone".to_string(),
        )
    }

    /// A new database is empty, and demo data makes it not empty.
    #[test]
    fn demo_data_fills_an_empty_database() {
        let db = memory_database();
        assert!(db.is_empty().unwrap());
        let rows = db.fill_demo_data(NOW, 14, 7, Arc::new(Utc)).unwrap();
        assert!(rows > 100);
        assert!(!db.is_empty().unwrap());
    }

    /// Demo data is never added on top of existing rows.
    #[test]
    fn demo_data_is_refused_when_there_is_data() {
        let db = memory_database();
        db.fill_demo_data(NOW, 3, 1, Arc::new(Utc)).unwrap();
        assert!(db.fill_demo_data(NOW, 3, 1, Arc::new(Utc)).is_err());
    }

    /// The day summary has 24 hours, lists sorted biggest first, and colours
    /// for categories that are in the app list.
    #[test]
    fn the_day_summary_is_complete() {
        let db = memory_database();
        db.fill_demo_data(NOW, 14, 7, Arc::new(Utc)).unwrap();
        let s = db.day_summary(0, None, NOW, Arc::new(Utc)).unwrap();
        assert_eq!(s.day, 20_000);
        assert!(s.total_secs > 0);
        assert_eq!(s.hours.len(), 24);
        assert_eq!(s.hours.iter().sum::<i64>(), s.total_secs);
        assert!(s.categories.windows(2).all(|w| w[0].secs >= w[1].secs));
        assert!(s.apps.windows(2).all(|w| w[0].secs >= w[1].secs));
        // The browser is in the app list, so it has a colour slot.
        let browser = s.categories.iter().find(|c| c.name == "Browser").unwrap();
        assert!(browser.color_index.is_some());
        assert!(!s.timeline.is_empty());
    }

    /// An empty database still gives a valid, all-zero summary.
    #[test]
    fn an_empty_database_gives_an_empty_summary() {
        let s = memory_database()
            .day_summary(0, None, NOW, Arc::new(Utc))
            .unwrap();
        assert_eq!(s.total_secs, 0);
        assert_eq!(s.hours, vec![0; 24]);
        assert!(s.categories.is_empty() && s.apps.is_empty());
    }

    /// Category colours follow the theme and an unknown index is the grey.
    #[test]
    fn category_colours_come_from_the_theme() {
        let first = category_color(default_theme_name(), Some(0));
        let grey = category_color(default_theme_name(), None);
        assert_ne!(first, grey);
    }

    /// A database with 14 days of demo data ending on the test's "today".
    fn demo_database() -> Database {
        let db = memory_database();
        db.fill_demo_data(NOW, 14, 7, Arc::new(Utc)).unwrap();
        db
    }

    /// Stepping Today back one day shows yesterday and allows going forward again.
    #[test]
    fn today_steps_back_to_yesterday() {
        let db = demo_database();
        let today = db.day_summary(0, None, NOW, Arc::new(Utc)).unwrap();
        assert!(today.can_back && !today.can_forward);
        let yesterday = db.day_summary(-1, None, NOW, Arc::new(Utc)).unwrap();
        assert_eq!(yesterday.day, today.day - 1);
        assert!(yesterday.can_forward);
    }

    /// Stepping stops at the first recorded day (the demo has 14 days).
    #[test]
    fn stepping_stops_at_the_first_recorded_day() {
        let db = demo_database();
        let oldest = db.day_summary(-13, None, NOW, Arc::new(Utc)).unwrap();
        assert!(!oldest.can_back);
    }

    /// The 7 day period lists seven days, oldest first, each with 24 hours.
    #[test]
    fn the_week_lists_seven_days() {
        let db = demo_database();
        let p = db
            .period_summary(Period::Week, 0, None, None, NOW, Arc::new(Utc))
            .unwrap();
        assert_eq!(p.days.len(), 7);
        assert!(p.days.windows(2).all(|w| w[0].day < w[1].day));
        assert!(p.days.iter().all(|d| d.hours.len() == 24));
        assert!(!p.by_month);
        assert!(p.total_secs > 0 && p.active_days > 0);
        assert!(p.can_back && !p.can_forward);
    }

    /// The calendar week starts on Monday and stops at today (a Friday here).
    #[test]
    fn the_calendar_week_stops_at_today() {
        let db = demo_database();
        let p = db
            .period_summary(Period::CalWeek, 0, None, None, NOW, Arc::new(Utc))
            .unwrap();
        // Monday to Friday.
        assert_eq!(p.days.len(), 5);
    }

    /// The year and the quarter are summed by month, and all time cannot step.
    #[test]
    fn long_periods_are_summed_by_month() {
        let db = demo_database();
        let year = db
            .period_summary(Period::Year, 0, None, None, NOW, Arc::new(Utc))
            .unwrap();
        assert!(year.by_month && !year.months.is_empty());
        let all = db
            .period_summary(Period::All, 0, None, None, NOW, Arc::new(Utc))
            .unwrap();
        assert_eq!(all.label, "All time");
        assert!(!all.can_back && !all.can_forward);
        let quarter = db
            .period_summary(Period::Quarter, 0, None, None, NOW, Arc::new(Utc))
            .unwrap();
        assert!(quarter.by_month);
    }

    /// A custom span is used as given.
    #[test]
    fn a_custom_span_picks_its_days() {
        let db = demo_database();
        let span = CustomSpan {
            first: 19_995,
            last: 19_997,
        };
        let p = db
            .period_summary(Period::Custom, 0, Some(span), None, NOW, Arc::new(Utc))
            .unwrap();
        assert_eq!(p.days.len(), 3);
        assert_eq!(p.days[0].day, 19_995);
    }

    /// Typed dates become a span: reversed order is fixed, an empty end means
    /// today, an end in the future is cut back, a start in the future is refused.
    #[test]
    fn typed_dates_become_a_span() {
        let zones = || -> Arc<dyn ZoneOffsets> { Arc::new(Utc) };
        // 20000 days after 1970-01-01 is 2024-10-04.
        let span = custom_range("2024-10-01".into(), "".into(), NOW, zones()).unwrap();
        assert_eq!((span.first, span.last), (19_997, 20_000));
        let swapped = custom_range("2024-10-03".into(), "2024-10-01".into(), NOW, zones()).unwrap();
        assert_eq!((swapped.first, swapped.last), (19_997, 19_999));
        let cut = custom_range("2024-10-01".into(), "2030-01-01".into(), NOW, zones()).unwrap();
        assert_eq!(cut.last, 20_000);
        assert!(custom_range("2030-01-01".into(), "".into(), NOW, zones()).is_err());
        assert!(custom_range("nonsense".into(), "".into(), NOW, zones()).is_err());
    }

    /// Renaming an app re-sorts the history, is saved to the file, and survives
    /// opening the database again.
    #[test]
    fn set_app_renames_and_is_remembered() {
        let conf = temp_conf();
        let db = Database::from_db(
            Db::open_in_memory("phone").unwrap(),
            conf.clone(),
            PathBuf::new(),
            "phone".to_string(),
        );
        db.fill_demo_data(NOW, 3, 1, Arc::new(Utc)).unwrap();
        let before = db.day_summary(0, None, NOW, Arc::new(Utc)).unwrap();
        let app = before
            .apps
            .iter()
            .find(|a| a.category == "Terminal")
            .unwrap();
        assert!(!app.raws.is_empty());
        db.set_app(app.raws.clone(), "shell".into(), "Work".into())
            .unwrap();
        let after = db.day_summary(0, None, NOW, Arc::new(Utc)).unwrap();
        assert!(
            after
                .apps
                .iter()
                .any(|a| a.name == "shell" && a.category == "Work")
        );
        assert!(db.categories().unwrap().contains(&"Work".to_string()));
        // A new database object reads the saved file.
        let again = Database::from_db(
            Db::open_in_memory("phone").unwrap(),
            conf.clone(),
            PathBuf::new(),
            "phone".to_string(),
        );
        assert!(again.categories().unwrap().contains(&"Work".to_string()));
        let _ = std::fs::remove_file(conf);
    }

    /// An empty name or category is refused and saves nothing.
    #[test]
    fn set_app_refuses_empty_names() {
        let conf = temp_conf();
        let db = Database::from_db(
            Db::open_in_memory("phone").unwrap(),
            conf.clone(),
            PathBuf::new(),
            "phone".to_string(),
        );
        assert!(
            db.set_app(vec!["a.b".into()], " ".into(), "X".into())
                .is_err()
        );
        assert!(
            db.set_app(vec!["a.b".into()], "ab".into(), "".into())
                .is_err()
        );
        assert!(!conf.exists());
    }

    /// The device filter narrows the numbers, and demo websites show up.
    #[test]
    fn device_filter_and_sites() {
        let db = memory_database();
        db.fill_demo_data(NOW, 7, 7, Arc::new(Utc)).unwrap();
        assert_eq!(db.devices().unwrap(), vec!["phone".to_string()]);
        let all = db
            .period_summary(Period::Week, 0, None, None, NOW, Arc::new(Utc))
            .unwrap();
        let phone = db
            .period_summary(
                Period::Week,
                0,
                None,
                Some("phone".into()),
                NOW,
                Arc::new(Utc),
            )
            .unwrap();
        let other = db
            .period_summary(
                Period::Week,
                0,
                None,
                Some("laptop".into()),
                NOW,
                Arc::new(Utc),
            )
            .unwrap();
        assert_eq!(all.total_secs, phone.total_secs);
        assert_eq!(other.total_secs, 0);
        assert!(other.sites.is_empty());
        assert!(!all.sites.is_empty());
        assert!(
            all.sites
                .iter()
                .any(|s| s.name == "github" && s.category == "Development")
        );
    }

    /// A sync with an unusable address or empty fields is refused before any
    /// network use, with a message that says what to fix.
    #[test]
    fn sync_checks_its_settings_first() {
        let db = memory_database();
        let server = |url: &str, pw: &str| SyncServer {
            url: url.into(),
            database: "timesink".into(),
            user: "me".into(),
            password: pw.into(),
        };
        assert!(db.sync_now(server("myserver:5984", "x")).is_err());
        assert!(db.sync_now(server("http://myserver:5984", "")).is_err());
    }

    /// Before any sync: nothing succeeded and nothing failed.
    #[test]
    fn sync_status_starts_blank() {
        let db = memory_database();
        let st = db.sync_status().unwrap();
        assert_eq!((st.last_ok, st.last_failed), (None, None));
    }

    /// Builds a usage event the short way, for the tests below.
    fn ev(ts_s: i64, package: &str, kind: UsageKind) -> UsageEvent {
        UsageEvent {
            ts_ms: ts_s * 1000,
            package_name: package.to_string(),
            kind,
        }
    }

    /// Apps switching, screen off, and a stretch still open at the end.
    #[test]
    fn usage_events_become_stretches() {
        let events = vec![
            ev(100, "a", UsageKind::Resumed),
            // Switching to b ends a, even with no Paused for a.
            ev(160, "b", UsageKind::Resumed),
            ev(200, "b", UsageKind::Paused),
            ev(300, "c", UsageKind::Resumed),
            ev(330, "c", UsageKind::ScreenOff),
            ev(400, "d", UsageKind::Resumed),
        ];
        let (s, open) = usage_stretches(events, 450_000);
        assert_eq!(
            s,
            vec![
                ("a".to_string(), 100, 160),
                ("b".to_string(), 160, 200),
                ("c".to_string(), 300, 330),
                ("d".to_string(), 400, 450),
            ]
        );
        assert_eq!(open.as_deref(), Some("d"));
    }

    /// The home screen ends an app's stretch and counts for nothing; a stray
    /// Paused for an app that is not in front changes nothing.
    #[test]
    fn home_screen_and_stray_pauses() {
        let events = vec![
            ev(10, "a", UsageKind::Resumed),
            ev(20, "zzz", UsageKind::Paused),
            ev(40, "", UsageKind::Nothing),
            ev(90, "a", UsageKind::Resumed),
            ev(100, "a", UsageKind::Paused),
        ];
        let (s, open) = usage_stretches(events, 200_000);
        assert_eq!(
            s,
            vec![("a".to_string(), 10, 40), ("a".to_string(), 90, 100)]
        );
        assert_eq!(open, None);
    }

    /// Moving between one app's own screens leaves a tiny gap that is joined.
    #[test]
    fn tiny_gaps_inside_an_app_are_joined() {
        let events = vec![
            ev(10, "a", UsageKind::Resumed),
            ev(50, "a", UsageKind::Paused),
            ev(51, "a", UsageKind::Resumed),
            ev(80, "a", UsageKind::Paused),
        ];
        let (s, _) = usage_stretches(events, 100_000);
        assert_eq!(s, vec![("a".to_string(), 10, 80)]);
    }

    /// Recording writes rows, names new apps, and a second import carrying on
    /// from the open app extends the same row instead of adding another.
    #[test]
    fn recording_usage_names_apps_and_carries_on() {
        let conf = temp_conf();
        let db = Database::from_db(
            Db::open_in_memory("phone").unwrap(),
            conf.clone(),
            PathBuf::new(),
            "phone".to_string(),
        );
        let label = AppLabel {
            package_name: "com.example.maps".into(),
            label: "Example Maps".into(),
        };
        let first = db
            .record_usage(
                vec![ev(1_000, "com.example.maps", UsageKind::Resumed)],
                vec![label.clone()],
                1_060_000,
            )
            .unwrap();
        assert_eq!(first.recorded, 1);
        assert_eq!(first.open_package.as_deref(), Some("com.example.maps"));
        // The next import starts with the carried-over app.
        let second = db
            .record_usage(
                vec![
                    ev(1_060, "com.example.maps", UsageKind::Resumed),
                    ev(1_120, "com.example.maps", UsageKind::Paused),
                ],
                vec![label],
                1_200_000,
            )
            .unwrap();
        assert_eq!(second.open_package, None);
        let day = db
            .period_summary(Period::All, 0, None, None, 1_300, Arc::new(Utc))
            .unwrap();
        // One 120 second stretch, named by its own label, not two rows.
        assert_eq!(day.apps.len(), 1);
        assert_eq!(day.apps[0].name, "Example-Maps");
        assert_eq!(day.apps[0].secs, 120);
        assert_eq!(day.apps[0].category, "Other");
        let _ = std::fs::remove_file(conf);
    }

    /// Lists shared by another computer are used, and the newest change wins:
    /// a newer desktop entry beats the built-in one, and sites follow along.
    #[test]
    fn shared_lists_are_merged_into_the_phone() {
        let db = memory_database();
        // Before: `discord` is in the built-in list under Chat.
        assert_eq!(db.map().unwrap().lookup("discord").category, "Chat");
        {
            let inner = db.lock().unwrap();
            inner
                .set_remote_conf(
                    "mangrove",
                    Some(&ConfLists {
                        apps: "discord discord Social\nzerohour zerohour Work\n".into(),
                        apps_updated: 500,
                        domains: "github.com github Research\n".into(),
                        domains_updated: 500,
                    }),
                )
                .unwrap();
        }
        db.rebuild_lists().unwrap();
        let map = db.map().unwrap();
        assert_eq!(map.lookup("discord").category, "Social");
        assert_eq!(map.lookup("zerohour").category, "Work");
        assert!(db.site_color_index("Research").is_some());
        // Built-in apps nobody overrode are still there.
        assert_eq!(map.lookup("com.android.chrome").category, "Browser");
    }
}
