# Pairing And Discovery

## Principles

Pairing establishes trust. Discovery only finds reachable devices.

A discovered device must not be allowed to send files unless it is paired and authenticated.

Accepted pairing/security decision: `docs/decisions/0004-pairing-qr-bootstrap-and-local-transport-security.md`.

## NearShare 1.0 Pairing

Recommended pairing flow uses QR code as the primary user experience and manual code/details as the fallback.

Important distinction: **QR code is the pairing UI/bootstrap method, not the full security model.** The security model defines what the QR contains, how TLS identity is pinned, how the short-lived offer expires, and how subsequent transfers prove they come from the same paired device.

1. Windows app generates a short-lived pairing offer.
2. Windows displays QR code and manual fallback details.
3. Android user chooses Scan QR code in the Android app.
4. Android scans the QR or enters manual details.
5. Android connects to Windows over local HTTPS and validates the TLS certificate fingerprint from the payload.
6. Windows asks the user to confirm the pairing request.
7. Both sides store a paired-device record and per-device shared secret.
8. Pairing offer expires and cannot be reused.
9. Subsequent sessions use persistent paired-device identity plus HMAC request authentication.

The QR/manual payload uses:

```text
nearshare://pair?payload=<base64url-json>
```

The decoded payload contains:

- protocol version;
- pairing offer ID;
- PC display name;
- local address candidates if known;
- port;
- transport value, initially `https`;
- short-lived pairing token;
- TLS certificate SHA-256 fingerprint;
- expiration timestamp.

Windows core has an initial payload codec in:

- `apps/windows/src/PCMobileLink.Windows.Core/PairingPayload.cs`
- `apps/windows/src/PCMobileLink.Windows.Core/PairingEndpointCandidate.cs`
- `apps/windows/src/PCMobileLink.Windows.Core/PairingPayloadCodec.cs`

## Transport And Authentication

NearShare 1.0 transport direction:

- local HTTPS for pairing and transfers;
- per-install self-signed TLS certificate on each receiving endpoint;
- certificate fingerprint pinned during pairing;
- per-paired-device shared secret;
- HMAC-SHA256 request signatures using timestamp and nonce to prevent unauthenticated uploads and replay.

Do not rely on IP address alone. Do not accept unsigned upload requests.

## Pairing Code Hosts

Windows WPF uses the `QRCoder` NuGet package to render the QR payload as a PNG-backed WPF image.

The Windows Devices tab starts a short-lived local HTTPS pairing listener when Pair device is clicked. The QR payload points the other device at that listener and includes the listener certificate SHA-256 fingerprint for pinning. The normal visible manual fallback is a 9-character pairing code, not the full setup URI.

The Android Dashboard can also show this device's pairing code. That starts an Android local HTTPS pairing host using the Android receive certificate. While the pairing code is active, the same endpoint can approve a pending pairing request and receive authenticated transfer requests from already-paired devices. This supports Windows-to-Android pairing and contributor validation paths without requiring users to leave NearShare.

The full `nearshare://pair?...` setup URI is protocol data. It must be carried by QR or hidden behind an advanced copy action; it should not be presented as the normal user-facing code.

## Windows Pairing Listener

When Pair device is clicked, Windows starts a local Kestrel HTTPS listener using a generated self-signed certificate and creates a QR/manual payload whose TLS fingerprint matches that listener.

The accepted production direction remains a per-install certificate. Windows now persists the pairing listener certificate under the user's app data so the PC fingerprint remains stable across pairing listener restarts.

Current pairing endpoints:

- `GET /nearshare/pairing/offers/{offerId}` returns offer status when the offer exists and has not expired.
- `POST /nearshare/pairing/requests` accepts `{ offerId, pairingToken, deviceName, devicePublicKey, receiveEndpoints?, receiveTlsCertificateSha256? }` and returns `202 Accepted` with `pending_confirmation` only when the offer ID and token match.
- `GET /nearshare/pairing/requests/{requestId}` returns `pending_confirmation`, `approved`, or `rejected` so Android can finish the pairing flow after Windows user confirmation.

Invalid offer IDs are rejected, invalid tokens return unauthorized, and expired offers return gone. A valid request is shown in the Windows Devices UI with Approve/Reject actions. Approval creates and persists a paired-device record with a per-device shared secret for subsequent authenticated transfer requests.

## Pairing Clients

Android can decode a `nearshare://pair?payload=...` value from the scanner, connect to the pairing listener over pinned local HTTPS, post a pairing request, poll for approval/rejection, and store the approved paired-device metadata locally. Before it submits the request, Android starts a receive endpoint so the pairing request can include Android's receive endpoint and certificate for reverse sending.

Windows can enter a 9-character pairing code shown by another Windows PC or Android device. Android can also use Enter code. The short code is resolved through local UDP discovery on the current LAN/private connection into the hidden setup URI. The sender then starts its own local receiver, submits a pairing request with its receive endpoint/certificate metadata, and stores the approved device for later sends.

After an approval result, the sending side signs a `GET /nearshare/paired-devices/{deviceId}/reachability` request with the paired-device shared secret. The receiver verifies that HMAC request before returning `reachable`. See `docs/transfer-protocol.md` for the shared header/signature contract.

The raw manual payload parser remains for protocol and test coverage. The full setup URI can still be copied as an advanced fallback for troubleshooting, but normal users should scan QR or enter the short code. If short-code discovery fails, guide the user to keep both devices on the same Wi-Fi/private connection or scan the QR.

Scanner implementation:

- CameraX for camera lifecycle/preview;
- ZXing Core for QR decoding;
- no Google Play Services requirement;
- no cloud scanning.

If real-device testing shows poor scanning reliability, reassess CameraX + on-device ML Kit Barcode Scanning.

## Discovery

Discovery should be convenience, not the only path.

Candidates:

- mDNS/NSD for same Wi-Fi;
- manual IP/port entry;
- QR-provided address candidates;
- recent paired device addresses.

Required fallback:

- manual IP/port or QR pairing details.

Reason: routers, guest Wi-Fi, Windows firewall, and Android hotspot behavior can break discovery.

## Hotspot Case

When Android creates a hotspot and PC joins it:

- transfer can still be local;
- internet/mobile data is irrelevant to file path;
- discovery may be less reliable than normal Wi-Fi;
- manual/QR fallback is mandatory.

## Private Connection Automation

Private connection setup is route setup, not pairing/trust. It must never bypass paired-device identity, pinned receiver certificates, or signed transfer requests.

NearShare 1.0 supported automation:

- Android can create a Local Only Hotspot from the Transfer tab.
- Android shows QR plus manual details: connection name, password, and a 9-character alphanumeric security code.
- Android shows both a NearShare QR payload and a standard Wi-Fi QR payload. Phone cameras can use the Wi-Fi QR; NearShare can use the richer app payload when scanner support exists on the joining device.
- Android can scan a NearShare private-connection QR or enter the details manually to request an app-scoped Wi-Fi join through the Android system prompt.
- Windows can accept those manual details in-app and ask Windows Wi-Fi to connect.
- After the route is available, normal resolver discovery and signed reachability checks still decide whether transfer can start.

The 9-character security code is a human confirmation/offer code. It is intentionally not the whole credential set because it cannot safely encode the private connection name, password, and routing metadata.

Do not promise silent cross-platform joining. Android and Windows may still show OS-controlled permission or network prompts.

## Port Policy

Windows receive listeners bind to a dynamically selected available local HTTPS port (`ListenPort = 0`) whenever the listener starts. The selected HTTPS port is runtime state, not a persisted user setting.

NearShare uses a lightweight local UDP discovery responder on port `53318` to advertise the current HTTPS endpoint while the receiver is running. Discovery requests include the expected receiver TLS certificate fingerprint, and receivers only reply when the requested fingerprint matches their persisted certificate. Senders must still validate any discovered endpoint with the existing signed HTTPS reachability request before sending files.

Because the HTTPS receive port is dynamic, paired devices should treat saved endpoints as cached hints. A paired device may disconnect/reconnect, move between Wi-Fi and phone hotspot, or see the receiver at a new IP/port without re-pairing. It should rediscover by the stable pinned TLS certificate fingerprint, validate the candidate endpoint with the signed paired-device reachability request, then update its cached endpoint before sending.

Re-pairing is only required when the trust relationship changes: the user removes the paired device, app data/trust stores are reset, the PC certificate identity changes unexpectedly, or the shared secret/device identity no longer validates.

## Deferred Decisions

- Exact paired-device storage encryption approach per platform.
- How much network diagnostic detail to expose to normal users.
