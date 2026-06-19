# Pairing

Pairing is required so only trusted devices can send files to each other.

Being on the same Wi-Fi is not enough. A paired device identity is required.

## Pairing Methods

NearShare supports QR-first pairing plus a human-sized manual fallback.

- QR carries the full local setup payload.
- The visible manual fallback is a 9-character uppercase code formatted as `XXX-XXX-XXX`.
- The full `nearshare://pair?...` URI is protocol data. It is not meant to be typed by normal users.

## Windows To Android Or Android To Windows

1. Open NearShare on the receiving device.
2. Start pairing.
3. Show the QR code or 9-character pairing code.
4. On the other device, scan the QR code or enter the code.
5. Approve the pending request on the receiving device.
6. Confirm the paired device names.

## Same-Platform Notes

NearShare 1.0 user guides focus on Windows and Android pairing. Same-platform pairing paths are available for contributor validation:

- Windows can show a pairing code, and another Windows device can enter it.
- Android can show this device's code, and another Android device can scan or enter it.

## If Code Lookup Fails

The short code is resolved by local discovery. If the app cannot find that code:

- keep both devices on the same Wi-Fi; or
- create and join a private connection first; or
- scan the QR code instead.

## Why Pairing Matters

Local networks are not automatically safe. Other devices may be connected to the same Wi-Fi or hotspot.

The app rejects file transfers from unpaired devices.
