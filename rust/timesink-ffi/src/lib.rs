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
//! Later steps add the database, the views and totals, and sync.
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

use timesink_core::theme::{self, Rgb, Theme};

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
}
