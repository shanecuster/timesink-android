# timesink for Android

A companion to [timesink](https://github.com/shanecuster/timesink), the
screen-time monitor. It shows the same totals as the terminal app on your
phone, can sync with your own CouchDB, and can record the phone's own screen
time. The look imitates the terminal app.

**Status:** just started. The Today screen works (banner, period tabs,
categories, applications, timeline, listening); the other periods, sync and
phone usage come next. Debug builds fill an empty database with made-up demo
data so the screens can be tried and photographed; release builds start empty.

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
