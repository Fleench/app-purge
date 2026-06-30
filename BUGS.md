# UX Bugs

## High priority

### Existing installs signed with old debug keys cannot migrate to the new release key

- Where: `app/build.gradle.kts` release signing and `.github/workflows/build-and-release.yml`.
- What the user sees: Android may refuse to update an installed app that was signed by an older debug or CI-generated key, so the user has to uninstall and reinstall, losing App Purge data.
- Why this is a bug: Android will not install an update over an app signed by a different certificate. Stable release signing fixes future releases, but it cannot retroactively migrate installs signed with a different private key.
- Evidence: the package id is stable (`com.apppurge`) and the current local APK reports versionCode `9`, but its debug signing cert SHA-256 is `0081f46fd5a8be83fcc8c72d082898500913678b341b7159394a00ca46968a52`. A release built on another machine/runner can have a different debug cert.
- Expected behavior: warn users that one final reinstall may be required when moving from the old debug-signed builds to the stable release key, then keep all future releases signed with the stable key.

### Apps list can remove a lock without Gemini review

- Where: `MainActivity.kt`, app row `Unlock` chip.
- What the user sees: a locked app can be unlocked directly from the Apps list with one tap.
- Why this is a bug: the lock model teaches users that removing or bypassing a lock should go through Gemini or an emergency unlock, but this chip removes the lock immediately.
- Expected behavior: direct removal should require confirmation at minimum, and probably use the same Gemini remove-lock flow as the overlay.

### App details can remove a lock without Gemini review

- Where: `MainActivity.kt`, app details `Remove lock` button.
- What the user sees: opening an app's info page and tapping `Remove lock` deletes the lock immediately.
- Why this is a bug: this bypasses the stricter Gemini remove-lock policy used when a locked app is opened.
- Expected behavior: the details screen should request Gemini approval, or clearly label this as an admin override with confirmation.

### There is no dedicated Locked Apps view

- Where: Home, Apps, and Settings screens.
- What the user sees: Settings shows a text list of locked app names, but there is no way to tap into a filtered list of locked apps.
- Why this is a bug: after locking several apps, users have to hunt through All apps to inspect or manage them.
- Expected behavior: add a `Locked apps` entry point from Home or Settings that opens a filtered apps list.

### Cooldown is not enforced in the overlay unlock form

- Where: `AppLockOverlayService.kt`, request form.
- What the user sees: after Gemini denies a request and applies a cooldown, the overlay still offers `Temporary unlock` and `Remove lock` buttons.
- Why this is a bug: users can keep submitting requests from the overlay even while the in-app fallback disables `Ask Gemini` during cooldown.
- Expected behavior: overlay should show the cooldown time and disable Gemini request actions until it expires.

### Overlay permission notification sends users to the wrong context

- Where: `Notifications.kt`, `showOverlayPermissionNotification`.
- What the user sees: the notification says overlay permission is needed, but tapping it opens App Purge instead of the Android overlay permission page.
- Why this is a bug: users are told a permission is required but are not taken to the setting that fixes it.
- Expected behavior: notification tap should deep-link to `ACTION_MANAGE_OVERLAY_PERMISSION` for App Purge, or the app should immediately show a clear permission action.

## Medium priority

### App lock cannot store a user's original lock reason

- Where: Apps list and app details lock actions.
- What the user sees: `Lock app` creates a lock with no reason prompt.
- Why this is a bug: Gemini decisions are based on the original lock reason, so blank reasons make unlock approval less meaningful and less predictable.
- Expected behavior: locking an app should ask why it is being locked, and details should show/edit that reason.

### The app row still has a `Purge` chip that opens app info

- Where: `MainActivity.kt`, app row trailing chips.
- What the user sees: tapping the row and tapping `Purge` both go to the same app info screen.
- Why this is a bug: the label implies it will configure a purge directly, but it no longer does.
- Expected behavior: rename the chip to `Info`, or make it open purge configuration directly while row tap opens app info.

### App details says another app is scheduled but does not let users open it

- Where: App details purge card.
- What the user sees: `Another app is scheduled: <name>`.
- Why this is a bug: users cannot jump to the scheduled app, inspect the time, or cancel it from that message.
- Expected behavior: show the scheduled purge time and provide an action to view or cancel the existing purge.

### Only one purge can exist, but the UI does not explain that before scheduling

- Where: App details and configure purge screens.
- What the user sees: every app has `Set purge`, even when another app is already scheduled.
- Why this is a bug: scheduling a purge for a second app silently replaces the previous purge.
- Expected behavior: warn before replacing the active purge, or support multiple purges.

### Emergency unlock has no confirmation or result feedback in app details

- Where: Apps list and app details emergency actions.
- What the user sees: tapping `Emergency` spends a coin immediately.
- Why this is a bug: emergency coins are limited, and the UI does not confirm the spend or show the resulting unlock duration.
- Expected behavior: show a confirmation first, then a result message with remaining coins and unlock expiration.

### Emergency unlock is hidden from the lock overlay

- Where: `AppLockOverlayService.kt`.
- What the user sees: when blocked by a locked app, the overlay only offers Gemini request and quit.
- Why this is a bug: users may have emergency coins but cannot spend them at the moment they need access.
- Expected behavior: show emergency unlock in the overlay, with confirmation and coin count.

### Unlock notification may outlive the actual lock state

- Where: `Notifications.kt` and lock state updates.
- What the user sees: an ongoing "app unlocked" notification can remain even if the lock is removed, another unlock replaces it, or the app is relocked manually.
- Why this is a bug: the notification can claim an app is unlocked when that is no longer true.
- Expected behavior: cancel or update the unlocked notification whenever lock state changes.

### Live notification progress is effectively static

- Where: `Notifications.kt`, promoted unlocked notification path.
- What the user sees: on devices that support live notifications, the countdown text updates via chronometer, but the promoted progress value is calculated from the remaining time as if the current remaining time were the full duration.
- Why this is a bug: the live progress indicator may appear wrong or not useful.
- Expected behavior: store/pass the original unlock duration and calculate progress from elapsed time over total granted time.

### Locked state in the Apps list does not distinguish temporarily unlocked apps

- Where: `MainActivity.kt`, app rows.
- What the user sees: app rows show `Locked` even while an app has a temporary unlock window.
- Why this is a bug: users cannot scan the list and tell which locked apps are currently accessible.
- Expected behavior: show `Unlocked until <time>` or a distinct temporary-unlock state.

### Settings locked app list is not interactive

- Where: Settings app lock card.
- What the user sees: locked app names are shown as bullet text only.
- Why this is a bug: users naturally expect to tap a listed locked app to manage it.
- Expected behavior: make each locked app row open app details, or provide a `View locked apps` action.

### Settings API key save has no visible success or error feedback

- Where: Settings Gemini API key card.
- What the user sees: tapping `Save API key` leaves the screen unchanged.
- Why this is a bug: users cannot tell whether the key was saved.
- Expected behavior: show a short saved confirmation, and consider obscuring the key by default.

### App lock readiness does not include accessibility status

- Where: Home readiness panel and Settings app lock card.
- What the user sees: Settings tells users to enable accessibility, but Home readiness does not warn when the accessibility service is off.
- Why this is a bug: app lock appears configured but will not detect locked apps until accessibility is enabled.
- Expected behavior: show accessibility service state in readiness and provide the settings action there.

### Notification permission is requested only from Home readiness

- Where: Home, unlock notification, overlay notification flows.
- What the user sees: if notification permission is denied or skipped, unlock countdown notifications silently do not appear.
- Why this is a bug: users may expect a live/regular notification after unlock but get nothing.
- Expected behavior: when an unlock notification cannot be posted, show an in-app hint or action to enable notifications.

## Low priority

### `New this week` only shows five apps without saying so

- Where: `appsForMode`, `NewThisWeek`.
- What the user sees: the card count can say more than five new apps, but the list only contains five.
- Why this is a bug: the list appears incomplete with no explanation.
- Expected behavior: show all new apps, or label it as recent/top five.

### Configure purge allows scheduling in the past via picker combinations

- Where: Configure purge date/time screen.
- What the user sees: users can choose a date/time that is already past.
- Why this is a bug: saving a past time immediately triggers purge behavior, which can surprise users.
- Expected behavior: prevent past selections or show a confirmation that the purge will run immediately.

### Destructive actions lack confirmation

- Where: cancel purge, remove lock, emergency unlock.
- What the user sees: single taps immediately cancel or modify state.
- Why this is a bug: these actions change important user commitments and can be triggered accidentally.
- Expected behavior: add confirmation dialogs for destructive or limited-resource actions.

### Some result dialogs use generic button labels

- Where: overlay and in-app Gemini result dialogs.
- What the user sees: approved and denied results use generic labels like `Continue` and `Back`.
- Why this is a bug: the next destination is not always clear.
- Expected behavior: use labels such as `Open app`, `Return to lock`, or `Back to App Purge`.

### Long package names and reasons may crowd compact rows

- Where: app rows, status rows, settings lock list, overlay result.
- What the user sees: long text can dominate or wrap awkwardly in constrained space.
- Why this is a bug: important actions may be pushed down or become harder to scan.
- Expected behavior: apply max lines, overflow behavior, and clearer hierarchy for long values.
