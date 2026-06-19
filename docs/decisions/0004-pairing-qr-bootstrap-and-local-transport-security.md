# 0004 - Pairing, QR Bootstrap, And Local Transport Security

Status: Accepted
Date: 2026-06-09

## Context

The product needs nearby Android and Windows file sharing without cloud accounts, internet relay, or unauthenticated LAN uploads.

The user accepted the recommended pairing direction and explicitly wants the Android app to provide a QR-code scan option. The decision favors stable, simple, maintainable local transfer over clever protocol work.

## Decision

Use **QR-first pairing with manual fallback**.

Use **local HTTPS** for pairing and transfer transport.

Use a **per-install self-signed TLS certificate on each receiving endpoint** and pin the certificate fingerprint during pairing.

Use a **per-paired-device shared secret** for application-level request authentication after pairing.

Use **HMAC-SHA256 request signatures** over transfer requests using that per-device shared secret.

Use **CameraX + ZXing Core** for Android QR scanning unless later testing shows QR reliability issues that justify switching to ML Kit Barcode Scanning.

## Pairing UX

1. User opens NearShare on Windows.
2. User goes to Devices.
3. User chooses Pair a phone.
4. Windows creates a short-lived pairing offer.
5. Windows displays:
   - QR code;
   - manual fallback details.
6. User opens the Android app.
7. User chooses Scan QR code.
8. Android scans the QR code and connects to the Windows pairing endpoint over local HTTPS.
9. Windows shows a pairing confirmation prompt for the Android device.
10. After confirmation, both sides store the paired-device record.

QR code is the primary user experience. It is not, by itself, the complete security model.

## QR Payload Shape

The QR code uses a custom URI:

```text
nearshare://pair?payload=<base64url-json>
```

The decoded payload contains:

- protocol version;
- pairing offer ID;
- PC display name;
- local endpoint candidates;
- transport value, initially `https`;
- short-lived pairing token;
- TLS certificate SHA-256 fingerprint;
- expiration timestamp.

The Windows core codec for this payload is implemented in:

- `apps/windows/src/PCMobileLink.Windows.Core/PairingPayload.cs`
- `apps/windows/src/PCMobileLink.Windows.Core/PairingEndpointCandidate.cs`
- `apps/windows/src/PCMobileLink.Windows.Core/PairingPayloadCodec.cs`

## Security Model

Pairing establishes device trust. Discovery only finds reachable devices.

During pairing:

- Windows creates a short-lived token and offer ID.
- Windows presents the token and endpoint data through QR/manual fallback.
- Android validates the Windows TLS certificate fingerprint from the QR payload.
- Android and Windows create/store a per-device shared secret after Windows user confirmation.
- Pairing offers expire quickly and cannot be reused.

After pairing:

- A device is recognized by pair ID plus stored secret, not by IP address.
- Requests include a timestamp, nonce, pair ID, and HMAC-SHA256 signature.
- Receivers reject missing, expired, replayed, or invalid signatures.
- Local network reachability never implies authorization.

## Why Not Noise For 1.0

Noise is strong and elegant, but it adds less familiar dependencies and more protocol implementation risk across .NET and Android.

For NearShare 1.0, HTTPS plus pinned certificate fingerprints plus HMAC request authentication is easier to implement, easier to test, and stable on both platforms.

Noise can be reconsidered later if the protocol grows beyond what HTTPS/HMAC comfortably supports.

## Why Not Public Internet Or Cloud Relay

This remains local-link first:

- same Wi-Fi, or
- Android hotspot with Windows connected to that hotspot.

No backend server or public internet relay is part of this decision.

## Android QR Scanner Choice

Use CameraX for camera lifecycle/preview and ZXing Core for QR decoding.

Reasons:

- works offline;
- no cloud dependency;
- no Google Play Services dependency;
- small, mature QR decoding library;
- fits native Kotlin Android stack.

If QR scanning reliability is poor on real phones, the fallback option is CameraX + on-device ML Kit Barcode Scanning.

## Consequences

- Android app needs a Scan QR code entry point in the pairing flow.
- Windows Devices tab needs a Pair a phone flow that generates a short-lived offer and starts the local HTTPS pairing listener; implemented with pending request display, approve/reject actions, final status polling endpoint, paired-device persistence, and persisted PC certificate.
- QRCoder is used by the Windows WPF app to render the pairing QR image.
- Pairing code must track expiry and one-time use.
- Receivers accept file uploads only after paired identity, pinned receiver certificate, and signed request validation pass.
- Shared protocol fixtures should be added for QR payload examples and HMAC signing examples.
