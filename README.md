# timesink for Android

A companion to [timesink](https://github.com/shanecuster/timesink), the
screen-time monitor. It shows the same totals as the terminal app on your
phone, can sync with your own CouchDB, and can record the phone's own screen
time. The look imitates the terminal app.

**Status:** just started. The Today screen and every period tab work (7 days,
30 days, year, all time, calendar week, quarter and custom dates, with stepping
back and forward), a device filter once more than one device has data, a Sites
panel when website time exists, and tap-to-edit names and categories for
applications and websites (they share one category list and its colours, and
website time is also shown by category; there
is no built-in site list, so sites start uncategorised until you or another
device names them). Sync with your own CouchDB, and recording the phone's own app use (from
Android's Usage access), are there but off until you turn on the master switch
in settings. Sync also shares app and site names and categories between devices (newest change wins). The battery rules are built in: tracking and sync pause below a
battery level you choose (and optionally in Battery Saver) with one notification,
nothing runs constantly, background updates are opt-in, and a Quick Settings
tile turns everything on or off. Debug builds fill an empty
database with made-up demo data so the screens can be tried and photographed;
release builds start empty.

## How it is built

The logic is written once, in Rust (`timesink-core` in the timesink repo), and
the terminal app and this app both use it. Kotlin and Jetpack Compose draw the
screens; a small bridge (`rust/timesink-ffi`, using UniFFI) lets Kotlin call
the Rust code. `scripts/build-rust.sh` compiles the Rust for Android and writes
the Kotlin side of the bridge; Gradle runs it before every build.

## Build tools

| Tool | Version |
| --- | --- |
| Rust | 1.97.0 (pinned in `rust/rust-toolchain.toml`) |
| cargo-ndk | 4.1.2 (checked by `scripts/build-rust.sh`) |
| Android NDK | 30.0.16248370 (pinned in `app/build.gradle.kts`) |
| Android Gradle Plugin | 9.5.0 |

Install cargo-ndk with
`cargo install cargo-ndk --version 4.1.2 --locked`, open the folder in Android
Studio, and press Run. Minimum Android version: 8.0 (API 26).

## Privacy

Window titles are never recorded by the terminal app's sync, and this app
records none. Nothing leaves the phone unless you turn on sync and give it your
own server. The server address, user and password are typed into the app and
kept on the phone only.

## Licence

MIT. See `LICENSE`. The bundled JetBrains Mono font is under the SIL Open Font
License (see `third_party/fonts`).
