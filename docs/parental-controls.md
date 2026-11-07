# Parental controls (fork)

This fork adds three parental controls to the launcher, under **Launcher settings > Parental controls**:

| Control | What it does |
|---|---|
| **PIN** | 4 to 8 digits, on the prompt's own number pad or the remote's number keys (no on-screen keyboard, which on Sony sets keeps a history of what was typed). Once set, Launcher settings, the Home settings tile and the TV's settings ask for it. Five wrong tries lock the prompt for a minute. |
| **Settings lock** | With the PIN set and `SettingsGuardService` switched on, the TV's settings apps (`parental_locked_settings`) are covered by a PIN screen however they are opened: the remote's Settings button, the Action Menu, an app's own link. Back on Home they lock again. |
| **Bedtime** | A daily window (start 18:00 to 23:30, end 05:00 to 10:00) when apps and inputs show "TV time is over" instead of opening. |
| **Lock the TV** | Locks the TV for everyone, now: 30 minutes, 1, 2 or 3 hours, until 07:00, or until a half hour you pick in the next 24 hours. Apps and inputs show "The TV is locked until …" instead of opening (exempt apps aside); Home stays. It goes on through standby and reboots, and comes before bedtime, limits, extra time and the pick. Locking signs Parent out. With no PIN yet, it asks for one first. It is its own row in Launcher settings, under the Parental controls heading; while locked it reads "The TV is locked until …" and offers **Extend** and **Unlock now**. |
| **Children** | Each child has a name and their own daily limit, 30 minutes to 4 hours or no limit, counted from midnight. The limit can differ by day: every day, weekdays, the weekend, or each day of the week on its own. With no children added there is no time limit, only bedtime. |
| **Who's watching?** | With children added, opening an app asks whose turn it is; each name shows the time left today. The time an app is in front is charged to the child picked. A pick lasts 3 hours, or until the TV is switched off (standby, the mains or a reboot): then the Home pill reads "Who's watching?" again and the next app asks for a name. The Home pill switches to someone else at any time. **Parent** takes the PIN once; while Parent is picked nothing is blocked (no bedtime, limits or allowed-apps list) and no PIN is asked, Launcher settings and the TV's settings included. On the "TV time is over" screen, **Someone else is watching** lets another child take over. |

On the "TV time is over" page, **More time (parent)** takes the PIN and adds 15, 30, 60 or 120 minutes.
During a lock that button reads **Unlock now (parent)**: the PIN ends the lock, and bedtime and the limits apply
again at once. Picking **Parent** from the Home pill (with the PIN) also lets everything open while the lock runs.
**Turn off parental controls** removes the PIN, bedtime, the children and any lock.

## Set up

1. `launcher/app/src/main/res/values/config.xml`: `parental_controls` true (false hides the row, and none of
   this runs). Put apps that should never be blocked, such as an education or music app, in
   `parental_exempt_apps`.
2. Build and install the launcher as usual (`launcher/build-and-install.sh`).
3. Grant usage access once, from the computer:

   ```sh
   adb shell appops set com.gathin.tvhome GET_USAGE_STATS allow
   ```

   Without it no time is counted, and apps opened another way are not caught. The Parental controls screen
   says so in its top line.
4. Switch on the settings lock once, from the computer (this replaces the list of enabled accessibility services;
   check it first with `adb shell settings get secure enabled_accessibility_services` and append if it is not
   empty):

   ```sh
   adb shell settings put secure enabled_accessibility_services com.gathin.tvhome/com.gathin.tvhome.SettingsGuardService
   adb shell settings put secure accessibility_enabled 1
   ```
5. On the TV: Launcher settings > Parental controls > Set a PIN, then choose a bedtime and add each child
   with their limit.

## How it works

- Time comes from Android's usage events, split between children by who was picked when, so it includes
  apps opened with the remote's own Netflix or YouTube buttons, the Live TV app and HDMI inputs. The launcher, system UI and system settings never count.
- While a rule is set, the launcher keeps one non-waking alarm (`ParentalCheck`) for the next moment a rule
  could start. During a lockout it checks once a minute and puts the "TV time is over" page in front of
  any app that counts.
- The PIN is stored salted and hashed (SHA-256) in the launcher's private storage.

## Limits

This is a soft lock for a launcher with no root and no device-owner rights:

- An app opened with a remote shortcut button during a lockout plays for up to a minute before the page
  covers it.
- Children pick their own names, so one could pick a sibling who has time left.
- Without the settings lock switched on, the remote's own Settings button reaches the TV's settings without
  the PIN, and from there someone can clear the launcher's data (which removes the PIN) or change the clock.
  With it on, a settings screen shows for a moment before the PIN screen covers it.
- For a hard lock, combine it with the TV's own restricted profile or parental settings.
