# timesink for Android: privacy policy

_Last updated: 23 November 2026_

timesink for Android ("the app") is a screen-time monitor. It shows how long you
use apps and websites, and it can optionally copy that data between your own devices
through a server that you run. This policy says what the app does with your data.
The short version: **the data stays on your phone, and on a server only if you set
one up yourself. The developer never receives it.**

## What the app records

- **App use on this phone.** If you allow Android's "Usage access" permission and turn
  the app's master switch on, the app reads Android's usage log and stores which apps
  were open and for how long (the app's package name, and start and end times). It
  does not read anything inside an app: no messages, screens, text, contacts, photos
  or location.
- **Installed app names.** To show "Spotify" instead of a package name, the app looks
  up the names of the apps on the phone.
- **Names and categories you choose.** If you rename an app or website or move it to
  a category, the app saves that choice.
- **A device name.** A short name for this phone (you can change it), stored with the
  data so several devices can be told apart.
- **Settings.** Your switches and, if you use sync, the server address, database name,
  user name and password that you type in.

The app has no accounts, no sign-in, no advertising, no analytics and no crash
reporting. It contains no third-party tracking.

## Where the data is kept

Everything above is stored in the app's private storage on your phone. Other apps
cannot read it, and the app has Android's cloud backup switched off, so the data is
not copied into your Google account's backup. The server password is stored in the
same private storage.

## Sync (off unless you set it up)

Sync is optional and off by default. If you enter the address of your own CouchDB
server and turn sync on, the app sends your per-day usage totals (app names, durations,
the device name, and your name and category lists) to that server and downloads the
other devices' totals from it. You choose the server and you control it. The data goes
only to that address and never to the developer or any other party. Whether the
connection is encrypted depends on the address you give: use `https://` if the data
crosses a network you do not trust.

Website time is not recorded by the phone. It appears only if your own desktop
computer (running the timesink terminal app with its browser extension) has sent it
to your server.

## Notifications and background work

The app can show a notification when it pauses recording because the battery is low
or Battery Saver is on, and when it resumes. It restarts its background check after
the phone reboots. It uses the internet only for sync.

## Keeping and deleting your data

The data on the phone stays until you remove it: uninstalling the app, or choosing
"Clear storage" for it in Android's settings, deletes everything the app stored.
Data copied to your server is on your server, so you delete it there. The developer
holds no copy and cannot delete it for you.

## Children

The app is not directed at children and does not knowingly collect anything from them.

## Changes

If this policy changes, the new version replaces this page and the date above changes.

## Contact

Questions about this policy: open an issue at
<https://github.com/shanecuster/timesink-android/issues>.
