# Play Store listing text

Paste these into Play Console (Grow > Store presence > Main store listing).
Limits: app name 30 characters, short description 80, full description 4000.

## App name

timesink

## Short description

Screen time on your phone, in the style of the timesink terminal app.

## Full description

timesink shows where your time goes: which apps you use, for how long, and what share
of the day each category takes. It is the phone companion to timesink, a screen-time
monitor for the Linux terminal, and it looks like it: a monospaced, terminal-style
screen with bars for categories, applications and websites.

What it does
- Records which apps you use on this phone and for how long (it reads Android's own
  usage log once you allow Usage access).
- Shows today, 7 days, 30 days, the year, all time, a calendar week, a quarter or any
  dates you pick, with a bar chart of the hours of the day.
- Lets you name apps and websites and sort them into categories. The same categories
  and colours are used for apps and websites.
- Optional sync with a CouchDB server you run yourself, so one screen shows your phone
  and your computers together. Pick "all" or a single device.
- Pauses recording when the battery is low or Battery Saver is on, with an optional
  notification and a Quick Settings tile.

Private by design
- No account, no ads, no analytics, no tracking.
- Your data stays on your phone. Nothing is sent anywhere unless you turn on sync and
  type in the address of your own server.
- Only app names and times are recorded; nothing inside an app is read.
- Free and open source (MIT): https://github.com/shanecuster/timesink-android

Good to know
- Usage access is a special permission you grant in Android's settings; the app
  explains why before sending you there.
- Sync needs your own CouchDB server. The app is complete without it.

## Details

- Category: Productivity (or Tools)
- Tags: productivity, digital wellbeing
- Contact email: use a developer contact address you are happy to show publicly
- Website: https://github.com/shanecuster/timesink-android
- Privacy policy: https://github.com/shanecuster/timesink-android/blob/master/PRIVACY.md
- Content rating: answer the questionnaire honestly; the app has no violence, no
  user-generated content, no purchases and no ads, so it should come out as everyone.
- Ads: none. In-app purchases: none.
- Target audience: adults and teens, not children.

## Graphics still to make

- Icon: `art/timesink-icon-512.png` (done).
- Feature graphic, 1024 x 500: the three bars and the word "timesink" on the
  `#24273a` background would match the icon.
- Phone screenshots, at least two (up to eight): Today, a 7-day view, the categories
  and sites panels, and the settings screen. Take them in the emulator with the demo
  data so no real app names show.
