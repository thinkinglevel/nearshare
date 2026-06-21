# Security Model

## Threat Model

Assume local networks are not trusted.

Attackers may be:

- another device on the same Wi-Fi;
- a guest on the same hotspot;
- malware trying to upload unsafe paths;
- a stale paired device;
- a scanner probing open ports.

## Required Protections

- All transfers require paired-device authentication.
- Same network is not authorization.
- Pairing offers must be short-lived.
- Device identity must survive IP changes.
- File names must be sanitized on both platforms.
- Path traversal must be impossible.
- Reserved Windows names must be handled.
- Duplicate file behavior must be explicit.
- Transfer metadata must be validated before writing.
- Large transfers must enforce size/storage checks where possible.

## Transport Security

Preferred direction:

- authenticated encrypted channel even on LAN;
- per-device keys after pairing;
- checksum or digest verification for completed files.

Prototype-only exceptions must be labeled as insecure. Do not present plaintext unauthenticated transfer as production-ready.

## Private Connection Security

Private connection only creates or joins a local network route.

It must never bypass:

- paired-device identity;
- pinned receiver certificate validation;
- signed reachability checks;
- signed transfer-session and chunk requests.

The 9-character private-connection code is a human confirmation value and, where supported, a bootstrap into the normal pairing flow. It is not transfer authorization by itself; the receiving device still has to approve pairing, and transfers still require paired identity, pinned TLS, and signed requests.

## Android Always-On Receiving

Always-on receiving must be:

- opt-in;
- visible via persistent foreground notification;
- limited to paired devices;
- easy to disable;
- clear about which PC/device can send.

Do not hide background receiving behavior.

## Windows Receiver Exposure

The Windows receiver should be local-network only by product design.

Do not add UPnP, port forwarding, public internet listening, or cloud relay behavior without a separate architecture decision.

Windows firewall prompts and network profile behavior need explicit UX handling.
