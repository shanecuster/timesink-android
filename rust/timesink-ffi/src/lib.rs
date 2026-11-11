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

use std::path::Path;
use std::sync::{Arc, Mutex, MutexGuard};

use timesink_core::appmap::AppMap;
use timesink_core::data::{Snapshot, View};
use timesink_core::db::Db;
use timesink_core::model::WindowSnapshot;
use timesink_core::period::{self, Range};
use timesink_core::theme::{self, Rgb, Theme};
use timesink_core::{demo, stats};

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
    /// Application names and categories.
    map: AppMap,
}

/// Helpers used only inside this crate (not exported to Kotlin).
impl Database {
    /// Wraps an open core database together with the built-in app list.
    fn from_db(db: Db) -> Database {
        Database {
            db: Mutex::new(db),
            map: AppMap::from_conf(DEFAULT_APPS).with_browser_defaults(),
        }
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
        self.map
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
    pub fn open(path: String, device_id: String) -> Result<Arc<Database>, FfiError> {
        let db = Db::open(Path::new(&path), &device_id)?;
        Ok(Arc::new(Database::from_db(db)))
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
        now: i64,
        zones: Arc<dyn ZoneOffsets>,
    ) -> Result<DaySummary, FfiError> {
        // The time-zone callback as the plain function core expects.
        let tz = |ts: i64| zones.offset_at(ts);
        let db = self.lock()?;
        let loaded = self.load(&db, View::Today, offset, None, now, &tz)?;
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
        now: i64,
        zones: Arc<dyn ZoneOffsets>,
    ) -> Result<PeriodSummary, FfiError> {
        let tz = |ts: i64| zones.offset_at(ts);
        let db = self.lock()?;
        let custom = custom.map(|c| Range::new(c.first, c.last));
        let loaded = self.load(&db, period.into(), offset, custom, now, &tz)?;
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
    fn load(
        &self,
        db: &Db,
        view: View,
        offset: i64,
        custom: Option<Range>,
        now: i64,
        tz: &dyn Fn(i64) -> i64,
    ) -> Result<Loaded, FfiError> {
        let today = stats::local_day(now, tz);
        // The first recorded day, which the periods may not step before.
        let earliest = db.earliest_start()?.map(|t| stats::local_day(t, tz));
        let range = view.range(offset, today, earliest, custom);
        let snapshot = Snapshot::load(db, &self.map, view, range, None, now, tz)?;
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
            })
            .collect()
    }
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

    /// A fresh in-memory database, wrapped the way Kotlin would hold it.
    fn memory_database() -> Database {
        Database::from_db(Db::open_in_memory("phone").unwrap())
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
        let s = db.day_summary(0, NOW, Arc::new(Utc)).unwrap();
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
            .day_summary(0, NOW, Arc::new(Utc))
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
        let today = db.day_summary(0, NOW, Arc::new(Utc)).unwrap();
        assert!(today.can_back && !today.can_forward);
        let yesterday = db.day_summary(-1, NOW, Arc::new(Utc)).unwrap();
        assert_eq!(yesterday.day, today.day - 1);
        assert!(yesterday.can_forward);
    }

    /// Stepping stops at the first recorded day (the demo has 14 days).
    #[test]
    fn stepping_stops_at_the_first_recorded_day() {
        let db = demo_database();
        let oldest = db.day_summary(-13, NOW, Arc::new(Utc)).unwrap();
        assert!(!oldest.can_back);
    }

    /// The 7 day period lists seven days, oldest first, each with 24 hours.
    #[test]
    fn the_week_lists_seven_days() {
        let db = demo_database();
        let p = db
            .period_summary(Period::Week, 0, None, NOW, Arc::new(Utc))
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
            .period_summary(Period::CalWeek, 0, None, NOW, Arc::new(Utc))
            .unwrap();
        // Monday to Friday.
        assert_eq!(p.days.len(), 5);
    }

    /// The year and the quarter are summed by month, and all time cannot step.
    #[test]
    fn long_periods_are_summed_by_month() {
        let db = demo_database();
        let year = db
            .period_summary(Period::Year, 0, None, NOW, Arc::new(Utc))
            .unwrap();
        assert!(year.by_month && !year.months.is_empty());
        let all = db
            .period_summary(Period::All, 0, None, NOW, Arc::new(Utc))
            .unwrap();
        assert_eq!(all.label, "All time");
        assert!(!all.can_back && !all.can_forward);
        let quarter = db
            .period_summary(Period::Quarter, 0, None, NOW, Arc::new(Utc))
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
            .period_summary(Period::Custom, 0, Some(span), NOW, Arc::new(Utc))
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
}
