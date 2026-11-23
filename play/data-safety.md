# Data safety form: working answers

Play Console > Policy and programs > App content > Data safety. These are the
answers that match what the app does today. Re-check them whenever the app changes,
and check each one against Google's current form before you submit: the wording of
the questions changes from time to time.

## Facts the answers rest on

- The developer receives no data: there is no developer server, no analytics SDK, no
  crash reporter and no ad SDK.
- Usage data (app package names and time open) is stored in the app's private storage.
- If the user types in a CouchDB address, per-day totals go to that server. The user
  owns and controls it. It may be `http://` or `https://`, depending on what they type.
- Android's cloud backup of the app's data is off (`allowBackup="false"`).

## Suggested answers

- Does your app collect or share any of the required user data types? **The decision is
  yours.** The app sends nothing to the developer or any third party, which supports
  "No". But it can send usage data to a server the user chooses, so the more cautious
  answer is "Yes" with the entries below. Choose one and keep the privacy policy
  consistent with it. I would take the cautious answer.
- If "Yes":
  - **App activity > App interactions** (which apps were used, and when): collected,
    **not shared**, optional (sync is off by default), purpose **App functionality**.
  - **Device or other IDs / personal info:** not collected. The device name is a
    label the user types, not an identifier.
  - Is all of the data encrypted in transit? **No**, unless you decide to require
    `https://`: the app accepts both, so it cannot promise it.
  - Can users request that data is deleted? Yes: they can clear the app's storage or
    uninstall to remove the phone's copy, and delete what is on their own server.
- Security practices: the app has no account system, so there is nothing to sign in to.

## Permissions to be ready to explain

| Permission | Why |
| --- | --- |
| `PACKAGE_USAGE_STATS` (Usage access) | The whole point of the app: read Android's log of which apps were open. Granted by the user in Android's settings; the app explains first. |
| `INTERNET` | Sync with the user's own server. |
| `RECEIVE_BOOT_COMPLETED` | Restart the background check after a reboot. |
| `POST_NOTIFICATIONS` | The "paused on low battery" and "resumed" notices. |
| `<queries>` for launcher and home apps | Show readable app names. This is not the broad `QUERY_ALL_PACKAGES` permission. |

Play Console checks permissions when you upload a bundle and tells you if a
declaration form is needed. If it asks about Usage access, the answer is that tracking
the user's own app use is the app's core purpose and the user turns it on themselves.
