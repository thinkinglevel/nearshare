# Product Scope

## Current Product Definition

NearShare is a nearby, bidirectional file sharing tool for Windows PCs and Android phones.

The product is for lightweight nearby-device sharing and Apache-2.0 open-source distribution. It should be understandable and usable without a cloud account.

## Supported Network Shapes

NearShare 1.0 supported:

- Android and Windows PC on the same Wi-Fi network.
- Android phone hotspot enabled, Windows PC connected to that hotspot.
- NearShare-created private connection where the platform allows it.

Out of scope for NearShare 1.0:

- internet transfer across unrelated networks;
- cloud relay;
- public PC server exposure;
- Bluetooth-first transfer;
- account-based sync;
- telemetry;
- unrelated runtime intelligence or memory features.

## Core Workflows

Pairing:

1. One device starts pairing and shows a QR code plus a 9-character short code.
2. The other device scans the QR code or enters the short code.
3. The receiving device approves or rejects the request.
4. Both sides store paired-device identity, receiver endpoint metadata, TLS fingerprint, and shared secret.
5. Subsequent transfers require the paired identity and signed requests.

Android share sheet to paired device:

1. User shares one or more files from Android through the system share sheet.
2. App shows paired-device direct targets where Android supports them, plus the generic NearShare target.
3. User selects or confirms the paired destination.
4. Android streams files through authenticated resumable transfer sessions.
5. The receiver stores completed files in its configured receive folder.

Windows to paired device:

1. User selects files in the Windows app or supported Explorer entry point.
2. User chooses a paired destination.
3. Windows resolves the destination's current receive endpoint.
4. If the destination receive mode is active and reachable, transfer starts.
5. If not, Windows asks the user to open receive mode or create/connect a private connection.

Private connection fallback:

1. One device creates a local private connection and shows QR/manual details.
2. The other device joins it where the platform allows in-app joining.
3. NearShare uses the same 9-character code to bootstrap the normal pairing request where supported.
4. The creating device must approve pairing before a paired-device record is stored.
5. The normal paired-device resolver and signed transfer checks still decide whether sending can start.

## Android Receive Modes

Manual receive mode:

- User opens Android app and enables temporary receive readiness.
- Lower background footprint.
- Better default for battery-sensitive users.

Always-on receive mode:

- User explicitly enables a persistent receive toggle.
- Android keeps the paired local receive path ready when possible.
- Must use a visible foreground notification while active.
- Must handle reconnects when Wi-Fi or hotspot state changes.

## Same-Platform Validation Paths

Same-platform paths are contributor-validation scope in NearShare 1.0:

- Windows to Windows through pairing code and Windows receive endpoint metadata.
- Android to Android through Android-hosted pairing and Android receive endpoint metadata.

These paths are not the primary user scope, but they are useful for validating the paired-device model and transfer protocol without special-casing Windows as the only receiver.

## Non-Negotiables

- No unauthenticated receiving.
- No path traversal or unsafe file name behavior.
- No silent always-on receiving.
- No internet exposure as a default.
- No private connection behavior that bypasses pairing or signed transfer authentication.
