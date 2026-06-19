# Android UI Design

## Architecture

The NearShare Android app uses native Android Views, programmatically instantiated (e.g., in `MainActivity`, `ShareActivity`, `QrScannerActivity`), rather than XML layouts or Jetpack Compose. This keeps the application incredibly lightweight, fast to compile, and easy to maintain without requiring heavy external UI dependencies.

## Typography

To guarantee consistent branding across devices and circumvent manufacturer-specific font overrides, NearShare employs a unified typography system:

- Typography is handled globally via `AppTypeface.kt`.
- `AppTypeface` explicitly attempts to load the standard system Roboto/DroidSans typefaces (`/system/fonts/DroidSans.ttf` for regular, `/system/fonts/DroidSans-Bold.ttf` for bold).
- If unavailable, it falls back to the `sans-serif` default, applying the correct bold or normal `Typeface` attributes.

This ensures all dynamically generated text blocks—whether in the share sheet destination dropdown or the pairing scanner—maintain a coherent look, irrespective of OEM themes.

## Main App Shell

The launcher `MainActivity` uses a lightweight native bottom-navigation shell with three sections:

- `Dashboard` is the default first tab. It shows pairing actions, current status, private connection actions, and paired/trusted devices.
- `Transfer` is the middle tab. It is the home for manual receive, Always On receive state, private connection controls, and current transfer progress outside of the Android sharesheet flow.
- `Settings` is the third tab. It shows receive folder controls, the Always On toggle, and restart-after-phone-boot guidance.

All three tabs are programmatic native views and must keep the same typography contract as the share target: every `TextView`, custom navigation item, and app-created `Button` explicitly applies `AppTypeface.regular` or `AppTypeface.bold`. The shell runs edge-to-edge, but bottom navigation and content padding must add all system-bar insets so controls never sit under status, gesture, or navigation bars.

Settings stores Android receive preferences separately from paired-device records:

- default receive folder is Android Downloads;
- custom receive folders are selected through Android's folder picker / Storage Access Framework and persisted as a tree URI;
- folder changes apply to new incoming files without closing or restarting the app;
- Always On receive is opt-in and represented as a visible setting backed by the foreground receiver service.

After phone restart, NearShare should not pretend guaranteed background startup. The app stores the Always On preference and registers for boot completion, but the safe Android 15+ behavior is to show a resume notification when automatic foreground receiver startup is restricted by the OS or OEM battery policy.

## Share Target UI

The Android app acts as a share target for files:
- When sharing one or more files to NearShare, the user is presented with the `ShareActivity`.
- The top of the screen contains a paired-device dropdown and send button.
- The dropdown uses a custom adapter so both the collapsed row and popup rows explicitly apply `AppTypeface`, avoiding OEM font overrides.
- The last selected paired device is remembered by paired-device ID and auto-selected next time; if that device is deleted, the dropdown returns to the `Select device` placeholder instead of choosing a different device.
- Reachability is not used for preselection. If the selected paired device cannot be reached when sending starts, the issue is shown in an alert and the retry/private-connection path remains available.
- The screen displays overall batch progress, current file progress, and allows the user to cancel or retry transfers, communicating cleanly with the underlying transfer components via the unified `AppTypeface` styling.
