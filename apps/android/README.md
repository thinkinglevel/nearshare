# Android App

User-facing app name: **NearShare**.

Android MVP stack: native Kotlin / Android SDK.

Decision record:

- `../../docs/decisions/0001-android-stack.md`

## Current Scope

The Android app implements QR/code pairing, Android share-sheet sending, Android receiving, private connection create/join flows, transfer sounds, and manual GitHub release update checks. QR scanning uses CameraX + ZXing and still needs ongoing real-device validation against Windows and Android receivers.

Implemented:

- Gradle/Kotlin Android scaffold with app namespace `com.pcmobilelink.nearshare`.
- NearShare app label and launcher icon resources.
- QR-first Android pairing screen with edge-to-edge-safe flat UI.
- CameraX + ZXing QR scanner routed into the same pairing flow as the protocol parser.
- Pairing payload decoder, 9-character short-code handling, and unit tests.
- Pairing endpoint URL selector and unit tests.
- Certificate fingerprint normalization helper and unit tests.
- Pinned local HTTPS pairing request/result polling client.
- Android-hosted local pairing code for reverse and same-platform testing.
- App-private paired-device storage.
- Android share-sheet support for one or more files.
- Paired-device dropdown at the top of the send screen.
- Last selected paired-device persistence by device ID, with `Select device` fallback if that device is deleted.
- Sequential authenticated uploads with progress, cancel, retry, and resumable session plumbing.
- Foreground transfer service so sends can continue after the share screen is backgrounded.
- Manual and always-on Android receive foreground service.
- Android receive endpoint discovery responder/client for Windows-to-Android and Android-to-Android sends.
- Receive storage to Downloads or configured Storage Access Framework folder.
- Private connection create/join UI and route setup helpers.
- Transfer sound toggle and batch-level completion/failure sounds.
- Manual GitHub Releases update check and package download flow.

Next target:

1. install the debug APK on real Android devices;
2. run end-to-end QR/code pairing, Android-to-Windows, Windows-to-Android, and Android-to-Android physical tests;
3. harden storage-exhaustion, network interruption, private connection, hotspot, and Wi-Fi switching behavior based on device testing;
4. add broader endpoint discovery only if the current UDP discovery plus signed reachability path proves too fragile.

## Commands

From `apps/android/`:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Use a real Android device for reliable QR, share-sheet, paired-device dropdown, and transfer validation.
