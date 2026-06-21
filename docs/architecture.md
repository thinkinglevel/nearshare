# Architecture

## Direction

Use a local paired-device model with Windows and Android both able to participate as authenticated senders or receivers.

Windows is still expected to be the more stable everyday listener because it is plugged in more often, has fewer background execution restrictions, and can run a tray process. Android receiving is supported, but always-on mode must respect Android foreground-service rules and OEM battery behavior.

## High-Level Components

Windows app:

- user-facing app name: NearShare;
- local receiver/listener for authenticated paired-device requests;
- pairing UI with QR and 9-character code fallback;
- receive folder settings;
- send-to-paired-device UI;
- opt-in Explorer action target for `Send using NearShare`;
- active transfer progress, cancel, retry, and resume controls;
- private connection join flow;
- manual GitHub release update check;
- firewall/setup diagnostics.

Android app:

- Android share-target entry point;
- paired-device selector with a persistent top-of-screen dropdown for Android sends;
- send progress notification;
- manual receive mode;
- optional always-on receive foreground service;
- receive folder/settings;
- pairing scanner/code entry and Android-hosted pairing code;
- private connection create/join flow;
- manual GitHub release update check.

Shared protocol:

- device identity;
- pairing handshake;
- authenticated transfer session;
- metadata exchange;
- streaming file payload;
- checksum/result reporting.

## NearShare 1.0 Shape

- Receivers listen locally on dynamically selected HTTPS ports.
- Senders discover receiver endpoints where possible and validate them with pinned TLS plus signed reachability.
- QR/manual fallback is mandatory.
- Android share-sheet transfers and Windows send transfers both use the paired-device transfer protocol.
- For Windows to Android, Android must be in manual receive mode or always-on receive mode.
- Always-on Android receive should maintain readiness through a foreground service and reconnect logic.
- Private connection fallback provides a local route and bootstraps normal pairing where possible; transfers still require paired-device identity and signed request authentication.

## Stack Status

Windows stack is selected: C# / .NET / WPF for NearShare 1.0. See `docs/decisions/0002-windows-stack.md`.

Windows product name, UI direction, and shell integration direction are selected: NearShare, modern Windows 11-inspired UI, and `Send using NearShare` Explorer integration. See `docs/decisions/0003-windows-product-name-ui-and-shell-integration.md` and `docs/windows-ui-design.md`.

Android presentation constraints and typography are detailed in `docs/android-ui-design.md`.

Android stack is selected: native Kotlin / Android SDK for NearShare 1.0. See `docs/decisions/0001-android-stack.md`.

Pairing/security direction is selected: QR-first pairing with manual fallback, local HTTPS, pinned self-signed TLS certificate fingerprints, and per-device HMAC-SHA256 request authentication. See `docs/decisions/0004-pairing-qr-bootstrap-and-local-transport-security.md`.

Release packaging status:

- Windows 1.0 is packaged as an Inno Setup installer `.exe`.
- Android 1.0 is packaged as a direct-install `.apk`.
- Android release builds are signed with a maintainer-controlled local keystore that is not committed.
- Windows Authenticode signing remains a post-1.0 release-hardening item.

Current engineering direction:

- Android native Kotlin is the accepted 1.0 stack for share sheet, foreground services, notifications, Wi-Fi APIs, storage behavior, and long-term Android policy behavior.
- Windows .NET/WPF is the accepted 1.0 stack for tray UI, filesystem, firewall diagnostics, settings, and quick iteration.
- Rust/Tauri may still be attractive for a future shared protocol/core component, but not as the initial Windows or Android UI stack.
