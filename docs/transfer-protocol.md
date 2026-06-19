# Transfer Protocol

## Purpose

This document defines the local authenticated request contract used after pairing. Reachability, transfer-session creation, chunk upload, cancellation, and receiver endpoint discovery all build on the paired-device identity created during pairing.

## Scope

NearShare is local-link first. Requests are expected to travel over the same Wi-Fi network or an Android hotspot with the Windows PC connected to that hotspot.

This is not a cloud relay protocol and does not assume internet reachability.

## Transport

NearShare 1.0 transfer transport uses local HTTPS:

- the receiver presents its per-install self-signed certificate;
- the sender pins the certificate SHA-256 fingerprint learned during pairing;
- paired-device requests also use HMAC-SHA256 authentication with the shared secret created during pairing.

TLS pinning proves the sender is talking to the paired receiver identity. HMAC proves the sender still possesses the paired-device secret.

## Authenticated Request Headers

Every authenticated paired-device request must include:

- `X-NearShare-Device-Id`: paired device ID issued by the receiver during pairing;
- `X-NearShare-Timestamp`: Unix time seconds when the request was signed;
- `X-NearShare-Nonce`: random per-request nonce;
- `X-NearShare-Signature`: base64url HMAC-SHA256 signature.

Signature input format:

```text
<HTTP_METHOD>\n<PATH_AND_QUERY>\n<TIMESTAMP>\n<NONCE>\n<BODY_SHA256_BASE64URL>
```

Rules:

- `HTTP_METHOD` is uppercase, for example `GET` or `POST`.
- `PATH_AND_QUERY` starts with `/` and includes the query string if present.
- `BODY_SHA256_BASE64URL` is the SHA-256 digest of the exact request body bytes, encoded as unpadded base64url.
- Empty request bodies use the SHA-256 digest of zero bytes.
- `X-NearShare-Signature` is HMAC-SHA256 over the UTF-8 signature input using the paired shared secret bytes.
- The paired-device shared secret is stored as unpadded base64url and decoded before use as the HMAC key.

Replay protection:

- The receiver rejects timestamps outside the accepted clock-skew window.
- The receiver verifies the HMAC signature for the exact method, path, timestamp, nonce, and body hash.
- Durable repeated-nonce rejection per paired device remains a post-1.0 hardening item. NearShare 1.0 still requires paired-device secrets, pinned receiver TLS identity, timestamp windows, and per-request random nonces; it does not accept unauthenticated uploads.

## Reachability Endpoint

The first authenticated post-pairing endpoint is:

```text
GET /nearshare/paired-devices/{deviceId}/reachability
```

Success response:

```json
{
  "status": "reachable",
  "deviceId": "<paired device id>",
  "serverTimeUnixSeconds": 1234567890
}
```

Expected failures:

- `401 Unauthorized` when auth headers are missing, malformed, expired, or the signature does not match.
- `404 Not Found` when the device ID is not paired.

## Legacy Single-Request File Upload Endpoint

The first Android-to-Windows slice supported a single-request upload endpoint:

```text
POST /nearshare/paired-devices/{deviceId}/transfers/files
```

Additional headers per file:

- `X-NearShare-File-Name`: display/original file name from Android, sanitized by Windows before writing;
- `X-NearShare-File-Size`: file size in bytes;
- `Content-Type`: Android-provided MIME type or `application/octet-stream`.

The request body is the raw file bytes. The HMAC signature body hash is calculated over those exact bytes.

Success response per file:

```json
{
  "status": "received",
  "deviceId": "<paired device id>",
  "originalFileName": "photo.jpg",
  "savedFileName": "photo.jpg",
  "sizeBytes": 12345,
  "sha256": "<file sha256 base64url>"
}
```

Receiver behavior:

- reject unknown or unauthenticated paired devices before moving a file into the receive folder;
- spool incoming bytes to a temporary upload file while calculating SHA-256;
- verify the HMAC using the calculated body hash;
- delete temporary upload files on failed auth or metadata mismatch;
- sanitize the incoming file name using Windows filename rules;
- prevent path traversal by using only the sanitized leaf name;
- avoid overwrite surprises by appending ` (1)`, ` (2)`, etc. when a file already exists;
- create the receive folder if needed.

Sender behavior:

- accept `ACTION_SEND` and `ACTION_SEND_MULTIPLE` shares;
- send files sequentially, not concurrently;
- spool each Android content URI to an app-cache temporary file to determine size and SHA-256 before signing;
- stream each temporary file to Windows with byte-level sent progress;
- report per-file success/failure in the share-target UI.

Failure responses:

- `400 Bad Request` for missing metadata or invalid content length;
- `401 Unauthorized` for missing/malformed/expired/bad signatures;
- `404 Not Found` for unknown paired devices.

## Resumable Transfer Sessions

Progress, cancel, retry, and resume use temporary per-file transfer sessions. This is not a completed-transfer history feature. Session state exists only for active or incomplete transfers and is deleted after successful completion or user cancellation.

This is the preferred transfer path for current sends.

### Create Or Resume Session

```text
POST /nearshare/paired-devices/{deviceId}/transfer-sessions
```

Request body:

```json
{
  "clientSessionId": "<android-generated stable id for this file attempt>",
  "fileName": "photo.jpg",
  "fileSizeBytes": 12345,
  "sha256": "<whole file sha256 base64url>",
  "contentType": "image/jpeg",
  "fileIndex": 3,
  "totalFiles": 14
}
```

Success response:

```json
{
  "status": "ready",
  "sessionId": "<windows session id>",
  "offsetBytes": 0,
  "chunkSizeBytes": 262144,
  "fileSizeBytes": 12345,
  "originalFileName": "photo.jpg"
}
```

Rules:

- `clientSessionId` lets Android retry session creation without creating duplicate incomplete sessions.
- `fileIndex` is 1-based and `totalFiles` is the total number of files in the current Android share batch, so Windows can display true batch progress instead of treating every file as `1 of 1`.
- If a matching incomplete session already exists for the paired device and client session ID, Windows returns the current `offsetBytes` so Android can resume.
- Windows stores session manifests under the transfer temp folder, not in the receive folder.

### Query Session

```text
GET /nearshare/paired-devices/{deviceId}/transfer-sessions/{sessionId}
```

Returns the same session status shape and current accepted `offsetBytes`.

### Upload Chunk

```text
PUT /nearshare/paired-devices/{deviceId}/transfer-sessions/{sessionId}/chunks
```

Additional headers:

- `X-NearShare-Chunk-Offset`: byte offset of this chunk in the file;
- `X-NearShare-Chunk-Size`: chunk body length in bytes;
- `Content-Type`: `application/octet-stream`.

The HMAC body hash is calculated over the exact chunk bytes.

Chunk success response while incomplete:

```json
{
  "status": "in_progress",
  "sessionId": "<windows session id>",
  "offsetBytes": 262144,
  "fileSizeBytes": 12345
}
```

Final chunk success response:

```json
{
  "status": "completed",
  "sessionId": "<windows session id>",
  "offsetBytes": 12345,
  "fileSizeBytes": 12345,
  "savedFileName": "photo.jpg",
  "sha256": "<whole file sha256 base64url>"
}
```

Rules:

- Windows rejects chunks whose offset does not match the current session offset.
- Windows appends valid chunks to the session temp file.
- After the final byte, Windows verifies the whole-file SHA-256 from the session manifest.
- Only after final verification does Windows move/copy the file into the receive folder using sanitized, collision-safe naming.
- Completed sessions are removed from the temp/session store.

### Cancel Session

```text
DELETE /nearshare/paired-devices/{deviceId}/transfer-sessions/{sessionId}
```

The receiver deletes the temp file and manifest. The sender uses this when the user taps Cancel. Cancelled transfers are not shown as history.

### Retry And Resume UX

- Retry reuses the prepared Android cache file and the same `clientSessionId` when possible.
- If Windows still has an incomplete session, Android resumes from the returned offset.
- If Windows no longer has the session, Android starts again from offset 0 with a new server session.
- Android shows current-file and total-batch progress bars, plus Cancel and Retry actions.
- Windows shows the Android-provided batch count and the current file position for receiver-side progress. Its progress bar is count-based across the batch because the receiver only receives one session at a time.

### Durable Active Transfer State

Android may persist active transfer manifests so a transfer can continue or resume after the share Activity is destroyed or the process restarts. This is not completed-transfer history.

Android durable state rules:

- copy shared `content://` files into app-owned cache before the background transfer starts;
- persist only active/incomplete transfer metadata: target PC ID, original file name, MIME type, cached file path, file size, SHA-256, client session ID, and current status;
- run actual transfer work in a foreground data-sync service with a visible notification;
- delete cached files and manifests after success, user cancellation, or expiry;
- never show completed transfers as a history list unless a future user-visible history feature is explicitly approved.

Windows receiver lifecycle rules:

- file-transfer receiver endpoints must be available from the normal receive listener, not only from a short-lived pairing listener;
- Manual receive mode may require the app window/user action to start the listener;
- Always On receive mode should keep the Windows listener active while the app/tray process is running;
- when Always On is enabled, closing the main Windows window hides NearShare to the system tray and keeps the listener active;
- selecting Exit NearShare from the tray menu is the explicit action that stops listening and exits;
- when Start with Windows is enabled, the app stores a current-user startup entry that launches `NearShare.exe --background`; enabling this setting also enables Always On after an in-app warning/confirmation;
- the receiver dynamically chooses an available local HTTPS port on listener startup;
- Windows also runs a lightweight UDP discovery responder on port `53318` while the receiver is active;
- Senders treat saved endpoints as cached hints. They should discover a current endpoint when possible, accept only matching certificate/device responses, then validate the candidate with the signed `/nearshare/paired-devices/{deviceId}/reachability` request before sending;
- QR/manual pairing remains the fallback/bootstrap path if discovery cannot find the current endpoint;
- the receiver uses a persistent per-install certificate so certificate pinning remains valid across listener restarts even when the HTTPS port changes.

Android receiver lifecycle rules:

- Manual receive starts an Android foreground service and local HTTPS receive endpoint.
- Always-on receive is opt-in and visible through a foreground notification.
- Android publishes receive endpoint discovery responses with type `nearshare.android-receive.discovery.response.v1`.
- Windows validates Android receive reachability before sending.
- Android stores received files in Downloads or the configured Storage Access Framework folder.

### Direct Android Share Targets

Android can publish paired devices as Android Sharesheet direct targets. When the user shares files:

- selecting a direct paired-device target starts the NearShare transfer screen for that device immediately;
- selecting the generic NearShare target opens the send screen with the paired-device dropdown;
- the generic send screen remembers the last selected paired device by device ID and preselects it only while that device remains paired;
- if the remembered device was deleted, Android leaves the dropdown on `Select device` instead of choosing another paired device;
- reachability is checked when the transfer starts, not when preselecting the dropdown;
- if the target device is unreachable, Android shows a clear local-network/private-connection alert and offers retry;
- if multiple paired devices exist, the user can choose between direct targets in the Android Sharesheet or use the in-app dropdown.

### Private Connection Route

Private connection is route setup only. It does not change the authentication contract.

- Android can create a private connection and show NearShare QR, Wi-Fi QR, and manual details.
- Android can join a private connection through QR/manual details via the Android system Wi-Fi prompt.
- Windows can join using connection name, password, and the 9-character security code shown by the creating device.
- After joining, sender endpoint resolution and signed reachability validation still run before transfer.

### Post-1.0 Hardening

Post-1.0 hardening backlog:

- completed-transfer history UI;
- durable nonce replay storage;
- additional endpoint discovery mechanisms beyond the current UDP discovery and signed reachability checks.
