# Transfer Workflows

## Android To Paired Device

Entry:

- Android system share sheet.
- App receives one or more content URIs.

Expected flow:

1. Resolve shared items without unnecessary full-file copies.
2. Show the top paired-device dropdown, with the last selected paired device preselected when it is still paired.
3. If the remembered device was deleted or no device was previously selected, leave the dropdown on `Select device` rather than auto-picking another device.
4. Start authenticated transfer only after the user has a selected paired device.
5. Resolve/refresh reachability when sending starts, not when deciding the initial dropdown selection.
6. Copy the shared content URI into app-owned cache, persist active transfer metadata, then stream files from an Android foreground data-sync service with notification progress.
7. Keep transfer work running if the Android send screen is closed; progress can continue from the notification, and the screen can still show progress while visible.
8. Show success/failure notification and alert actionable transfer issues such as an unreachable device.
9. The receiver shows batch progress using the Android-provided `fileIndex`/`totalFiles` metadata and writes completed files to the configured receive folder.
10. If the paired device cannot be reached, guide the user to create/connect a private connection, then retry the same paired-device send flow.

Required edge cases:

- multiple files;
- large files;
- content URIs without simple filesystem paths;
- duplicate filenames;
- insufficient storage;
- transfer interruption;
- selected device unreachable after send starts;
- deleted remembered device should fall back to `Select device`, not another paired device.

## PC To Paired Device

NearShare 1.0 entry options:

- Windows app file picker;
- command-line `send` entry point;
- Windows Explorer `Send using NearShare` context menu integration for selected files.

NearShare 1.0 supports Windows app file selection, command-line send entry, and opt-in Explorer registration from NearShare Settings. Installer-managed automatic registration remains a post-1.0 packaging decision.

Future entry options can include drag and drop or tray send actions, but they should not be described as current behavior until implemented.

Expected flow:

1. User selects one or more files on Windows.
2. User selects the paired device in NearShare or from the Explorer submenu.
3. Windows checks paired-device reachability/readiness.
4. If the receiver is ready, transfer starts.
5. If the receiver is not ready, Windows shows a clear prompt to enable receive mode or create/connect a private connection.
6. The receiver shows progress and stores files in configured receive location.
7. If the paired device is not reachable on the current route, Windows offers an in-app private connection form for the details shown by the other device.

## Same-Platform Validation Paths

Windows to Windows:

1. On the receiving PC, open Devices and choose Pair device.
2. On the sending PC, paste the receiving PC's pairing code under Pair from another device.
3. Approve the request on the receiving PC.
4. Both PCs store the shared secret and receive endpoint metadata, so either PC can select the other from Send.

Android to Android:

1. On the receiving phone, open Dashboard and choose Show this code.
2. On the sending phone, choose Scan pairing QR and approve the request on the receiving phone.
3. Android starts a receive endpoint during pairing so reverse receive metadata is included.
4. Use the Android share sheet, select the paired phone, and send.

Windows to Android:

1. On Android, choose Show this code.
2. On Windows, paste that code under Pair from another device.
3. Approve on Android, then send from Windows to the paired Android device.

These paths are contributor-validation scope in NearShare 1.0. The primary user workflow remains Windows-to-Android and Android-to-Windows.

## Private Connection Fallback

Private connection is a guided local route fallback:

1. User keeps both devices in NearShare.
2. One device creates a private connection and shows QR/manual details.
3. The other device connects from inside NearShare where the platform allows it.
4. NearShare uses the same 9-character code to submit the normal pairing request when the creating side exposes a pairing offer.
5. The creating device must approve the pairing request before a paired-device record is stored.
6. If a send had already failed because the paired device was unreachable, NearShare retries that saved send after the private connection route starts.
7. If there is no saved failed send, the user starts the paired-device send normally.

NearShare 1.0 supports Android-created private connection plus Windows joining by connection name/password/9-character alphanumeric security / pairing code. Android also shows a standard Wi-Fi QR for phone camera joining and a NearShare QR for app-aware joining. Android can join a private connection from a NearShare QR or manual details through the platform Wi-Fi join prompt.

Windows-created hotspot/private connection automation is outside NearShare 1.0 scope. Use an existing LAN, an Android-created private connection/hotspot, or Windows' own OS hotspot UI outside NearShare when Windows must provide the route.

Private connection does not bypass pairing. It bootstraps pairing where possible, and transfers still require a paired-device record plus authenticated request checks.

## Transfer Sounds

Transfer sounds are user-configurable.

- Default: on.
- Play once when a whole send/receive batch completes.
- Play once when a whole send batch fails or is canceled.
- Do not play per file or per progress update.
- Current receive-side completion infers batch completion from `completed && fileIndex == totalFiles`; future parallel/resumable receive work should replace that with an explicit batch terminal event.

## Android Receive Readiness

Android receive controls live in the main app shell:

- Dashboard: pairing/current trusted device summary.
- Transfer: manual receive controls and current transfer progress.
- Settings: receive folder, Always On receive toggle, and restart-after-phone-boot guidance.

Receive folder behavior:

- Default location is Android Downloads.
- A custom folder can be chosen through Android's folder picker / Storage Access Framework.
- Folder changes apply to new incoming files immediately; an already-writing file should finish at the location it started with.

Manual receive:

- User opens the app and starts receive mode.
- Receive mode remains active until user stops it, timeout expires, or app/system kills it.
- Foreground notification is used during active receive behavior so the user can see when Android is ready or receiving.

Always-on receive:

- User enables persistent toggle.
- App uses foreground service with visible notification.
- App tries to stay ready for paired devices on local networks.
- App must expose a quick action to disable always-on receiving.
- After phone restart, the app may receive `BOOT_COMPLETED` and should resume readiness only when Android allows it. On Android 15+ or restrictive OEM builds, show a clear notification asking the user to tap to resume instead of silently claiming the receiver is active.

## Transfer State Model

Plan for these states:

- queued;
- connecting;
- authenticating;
- transferring;
- verifying;
- completed;
- failed;
- canceled.

Do not treat "same Wi-Fi" as authorization. It is only network reachability.
