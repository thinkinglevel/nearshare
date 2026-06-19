# Implementation Plan

## Purpose

This file is the handoff entry point for future development sessions.

Every implementation session should:

1. Read this file.
2. Read the area-specific doc.
3. Read user/release docs if the change affects installation, pairing, sending, receiving, troubleshooting, or release packaging.
4. State findings, dependent workflows, intended scope, and decisions.
5. Wait for alignment before code changes unless the user explicitly requests a narrow mechanical edit.

## Current Status

NearShare 1.0 is released with public source, Android APK, Windows installer, and checksum artifacts published through GitHub Releases.

Existing setup:

- `README.md`
- docs under `docs/`
- user docs under `docs/user/`
- release docs under `docs/releases/`
- release artifact guidance under `releases/README.md`
- Android implementation under `apps/android/`
- Windows implementation under `apps/windows/`
- Git repository on branch `main` with GitHub remote `thinkinglevel/nearshare`

Android stack is selected: native Kotlin / Android SDK. See `docs/decisions/0001-android-stack.md`.

Windows stack is selected: C# / .NET / WPF. See `docs/decisions/0002-windows-stack.md`.

Windows product name and UI direction are selected: NearShare with a modern Windows 11-inspired UI and Explorer action label `Send using NearShare`. See `docs/decisions/0003-windows-product-name-ui-and-shell-integration.md` and `docs/windows-ui-design.md`.

Pairing/security direction is selected: QR-first pairing with manual fallback, local HTTPS, pinned self-signed TLS certificate fingerprints, and per-device HMAC-SHA256 request authentication. See `docs/decisions/0004-pairing-qr-bootstrap-and-local-transport-security.md` and `docs/pairing-and-discovery.md`. Windows QR rendering uses the `QRCoder` NuGet package in the WPF app.

Windows implementation currently includes:

- `apps/windows/PCMobileLink.Windows.sln`
- `apps/windows/src/PCMobileLink.Windows.App/` WPF app shell
- `apps/windows/src/PCMobileLink.Windows.Core/` pure core library
- `apps/windows/src/PCMobileLink.Windows.Infrastructure/` infrastructure adapter library
- `apps/windows/tests/PCMobileLink.Windows.Core.Tests/` core tests
- `apps/windows/tests/PCMobileLink.Windows.Infrastructure.Tests/` infrastructure tests
- `apps/windows/README.md` with run/build/test commands

Android implementation currently includes:

- Gradle/Kotlin Android project under `apps/android/`
- app namespace/application ID `com.pcmobilelink.nearshare`
- NearShare app label and launcher icon resources
- QR-first Android pairing screen inside an edge-to-edge-safe bottom-navigation shell with Dashboard, Transfer, and Settings tabs
- CameraX + ZXing QR scanner that routes scanned values into the same pairing flow
- Kotlin QR/manual pairing payload decoder with unit tests
- endpoint URL selector with unit tests
- certificate fingerprint normalization helper with unit tests
- pinned local HTTPS pairing client for Windows pairing requests/result polling
- app-private paired-device storage
- HMAC-SHA256 paired-device request signing
- authenticated reachability check after successful pairing
- Android receive settings foundation: default Downloads receive folder, custom folder picker persistence, Always On receive toggle state, and boot-completed resume notification fallback for Android/OEM startup restrictions

Current pairing and transfer plumbing exists: Windows can create QR/short-code pairing offers, render QR codes, start a local HTTPS pairing listener with a persisted PC certificate, expose pairing-offer status, accept valid-token pairing requests, show pending requests in Devices, approve/reject them, expose final request status for polling, persist approved paired-device records with per-device shared secrets, answer HMAC-authenticated reachability checks, receive authenticated resumable uploads into the configured receive folder, send selected files to paired Android devices, and register an opt-in Explorer `Send using NearShare` menu for selected files through Settings. Android can scan pairing QR codes, enter short codes, host its own local pairing code, post pinned HTTPS pairing requests, poll approval status, store approved paired devices, verify authenticated reachability, appear in the Android share sheet for single-file and multi-file sends, show a paired-device dropdown with remembered last selection, upload files sequentially with foreground progress/cancel/retry, and receive files through manual or always-on receive mode.

Private connection plumbing also exists for local route fallback: Android can create a private connection and show NearShare/Wi-Fi QR plus manual details, Android can join a private connection through the platform Wi-Fi prompt, and Windows can join using the manual details shown by the creator. This route setup does not bypass pairing or signed transfer validation.

Manual GitHub release update checks exist on both platforms. They use GitHub Releases without embedded credentials, download platform packages to `Downloads/NearShare Updates`, and verify SHA-256 checksums when a checksum asset is published.

## Phase 0 - Accepted Foundation

Accepted decisions:

- Android stack: native Kotlin / Android SDK.
- Windows stack: C# / .NET / WPF.
- Android 1.0 release artifact: direct-install `.apk`.
- Windows 1.0 release artifact: Inno Setup installer `.exe`.

Post-1.0 product/release decisions:

- Windows Authenticode signing policy and certificate management.
- Whether same-platform transfer paths remain contributor-validation scope or become documented user scope.
- Whether additional endpoint discovery mechanisms are needed beyond UDP discovery plus signed reachability.

Recommended discussion order:

1. Pairing and protocol.
2. User workflows and UI scope.
3. Test strategy.
4. Release signing and installer/runtime hardening.

## Phase 1 - Documentation And Contracts

Create or refine:

- normal-user README and install docs when user-visible behavior changes;
- release docs when artifact/package behavior changes;
- Windows UI design and shell integration behavior;
- protocol contract draft;
- pairing flow draft;
- transfer state model;
- Android receive mode behavior;
- Windows firewall/setup behavior;
- file naming and collision policy.

No new app foundation work is required for this phase.

## Phase 2 - Platform Packaging And Maintenance

Current status:

- Windows implementation exists under `apps/windows/`.
- Android implementation exists under `apps/android/`.
- Windows 1.0 is packaged as an Inno Setup installer `.exe`.
- Android 1.0 is packaged as a signed direct-install `.apk`.

Platform commands are documented in:

- `apps/windows/README.md`
- `apps/android/README.md`

Post-1.0 platform work:

- add Windows Authenticode signing when a signing certificate is available;
- decide whether Explorer context menu registration should remain opt-in in Settings or gain installer-managed automatic registration;
- keep UI docs aligned as Dashboard/Devices/Settings continue to evolve.

Do not add heavyweight frameworks just because they are familiar.

## Phase 3 - Pairing

Current Windows status:

- Windows pairing offer UI exists.
- Windows pairing listener uses a persisted PC certificate.
- Windows can show pending Android pairing requests and approve/reject them.
- Approved devices are persisted with a per-device shared secret.
- Android can poll a final pairing request status endpoint after posting a pairing request.

Current Android status:

- Android Gradle/Kotlin app exists.
- Android can decode QR `nearshare://pair?payload=...` payloads and resolve 9-character pairing codes.
- Android can build pairing request/result URLs.
- Android has certificate fingerprint normalization tests.
- Android has ZXing QR luminance decoding tests.
- Android can scan pairing QR codes, enter short codes, post pinned local HTTPS pairing requests, poll approval status, store approved devices, and host its own Android pairing code.

Post-1.0 pairing hardening:

- real-device end-to-end QR scan verification;
- polish pairing error states discovered during device validation.

Do not implement any receive path that bypasses paired-device authentication.

## Phase 4 - Android To PC Transfer

Current first-slice status:

- Android registers as an `ACTION_SEND` and `ACTION_SEND_MULTIPLE` share target for shared files.
- Android shows a compact paired-device dropdown in the send screen, remembers the last selected paired device by device ID, and falls back to `Select device` if that device is later deleted.
- Android uploads shared files sequentially with pinned local HTTPS, paired-device HMAC headers, resumable per-file sessions, and chunk uploads.
- Android spools shared content into app-owned cache while hashing, persists active/incomplete transfer manifests, then streams chunks to Windows from a foreground data-sync transfer service with current-file and overall progress plus Cancel and Retry actions.
- Android publishes paired devices as direct Android Sharesheet targets where supported, while the generic NearShare target still opens the paired-device dropdown send screen.
- Windows accepts legacy `POST /nearshare/paired-devices/{deviceId}/transfers/files` uploads and resumable `transfer-sessions` create/chunk/cancel routes on a receive listener that is independent from the short-lived pairing listener.
- Windows spools uploads/session chunks to a temp folder, computes SHA-256 while streaming, verifies HMAC, and writes to the receive folder only after authentication and final checksum verification pass.
- Windows rejects unknown or unauthenticated devices before writing final receive-folder files.
- Windows sanitizes incoming file names, avoids overwrites with ` (1)` suffixes, and writes to the configured receive folder.
- Windows publishes receive progress updates from accepted resumable chunks into the WPF dashboard transfer progress card, including device, file, byte progress, per-file percent, Android-provided file index/total file count, count-based batch progress, and completion state. Transfer progress text wraps to avoid clipped long filenames/device names.
- NearShare 1.0 does not include a completed-transfer history UI; temporary session state exists only for active/incomplete resume/cancel behavior.

Post-1.0 Phase 4 hardening:

- add storage exhaustion and network interruption hardening;
- add mDNS/NSD endpoint refresh beyond stored endpoint reachability probing.

Sequential multi-file Android-to-PC transfer, progress bars, cancel, retry, active-state durable resume, direct Android share targets, and an explicit Windows receive listener exist. Post-1.0 hardening focuses on real-device interruption and hotspot validation.

## Phase 5 - Windows To Android Transfer

Current status:

- Android manual receive mode starts a foreground local HTTPS receiver.
- Android always-on receive mode is opt-in and foreground-notification backed.
- Android publishes receive endpoint discovery responses.
- Windows resolves Android receive endpoints, validates reachability, and sends through authenticated transfer sessions.
- Android stores received files in Downloads or the configured SAF folder.
- Windows queues/retries a failed send when the failure path tells the user to create a private connection.

Post-1.0 work:

- real-device validation across Android OEM background restrictions;
- interruption and storage-exhaustion hardening;
- polish PC-to-Android progress/error states after device validation.

## Phase 6 - Private Connection And Route Hardening

Current status:

- Android can create a private connection and display NearShare QR, Wi-Fi QR, and manual details.
- Android can join a private connection via QR/manual details through Android system Wi-Fi APIs.
- Windows can join using the creator's network name/password/security code.
- Transfers still require paired-device identity, pinned receiver certificates, and signed requests.

Post-1.0 work:

- real-device validation across Windows Wi-Fi adapters, Android hotspot behavior, and route changes;
- decide whether Windows-created private connection automation is worth the complexity;
- improve user-facing guidance when OS-level Wi-Fi prompts fail.

## Phase 7 - Post-1.0 Hardening

Cover:

- large files;
- many files;
- duplicate filenames;
- storage exhaustion;
- transfer cancel/retry;
- network switch during transfer;
- Windows firewall diagnostics;
- Android hotspot behavior;
- security review.

## Current Recommendation

The next highest-risk post-1.0 work is real-device validation, not more protocol surface area. Focus on QR/code pairing reliability, Windows firewall prompts, Android foreground-service behavior, hotspot/private-connection route changes, storage exhaustion, and interrupted large-file transfers.
