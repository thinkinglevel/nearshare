# Sending Files

## Android To Paired Device

NearShare supports sending one or more shared Android files to a paired receiver on the same Wi-Fi network or private connection. The main NearShare 1.0 user path is Android to Windows.

Normal flow:

1. Make sure both devices are on the same local network path.
2. Keep receiving enabled on the destination.
   - Manual receive mode may require opening NearShare on the destination and starting receive mode.
   - Always On receive mode keeps the receive listener active while the app/service is allowed to run.
3. On Android, choose one or more files in another app.
4. Open the Android share sheet.
5. Choose a paired-device direct target, or choose NearShare to open the paired-device dropdown.
   - The generic NearShare target shows a device dropdown at the top of the send screen.
   - The last selected paired device is selected again the next time Android opens this send screen.
   - If that remembered device has been deleted from the paired-device list, the dropdown falls back to `Select device` instead of choosing another device automatically.
   - Reachability is checked only when sending starts; a paired device can remain selected even if it is currently offline.
6. Watch the current-file and overall progress.
7. Use Cancel to stop a transfer, or Retry to attempt the interrupted transfer again.

If multiple paired devices exist, Android can show paired-device targets in the system share sheet where supported, and the generic NearShare target still shows the top paired-device dropdown.

Files are saved in the receiver's configured receive folder.

Safety behavior:

- Receivers accept uploads only from paired devices with valid HMAC authentication.
- Android signs session creation and each chunk upload separately.
- Android sends multiple files sequentially, not concurrently.
- Android hashes each shared file while copying it to app-owned cache, then uploads it through a temporary resumable session.
- Receivers store incomplete sessions in a temp area, not in the receive folder.
- Receivers verify final size and SHA-256 before moving/copying a completed file into the receive folder.
- Receivers sanitize incoming file names.
- Receivers avoid overwriting existing files by adding a suffix such as ` (1)`.

Progress, cancel, retry, and resume behavior:

- Android shows separate progress bars for the whole batch and the current file.
- Transfers run through a foreground data-sync service with a visible progress notification, so uploads can continue after the send screen is no longer visible.
- Android persists only active/incomplete transfer state, not completed-transfer history.
- Cancel stops the active upload and asks Windows to remove the incomplete session.
- Retry reuses prepared Android cache files while the active transfer state exists.
- If the interrupted receiver session still exists, Retry resumes from the last accepted chunk offset.
- If the receiver session was cancelled or cleaned up, Retry starts that file again from the beginning.
- The receiver shows the sending device, true batch count such as `3 of 14 completed`, file name, byte progress, and completion state in its transfer progress UI.
- NearShare does not show a completed-transfer history screen.

## Windows To Paired Device

Windows can send one or more selected files to a paired Android device.

Normal flow:

1. Open NearShare on Windows.
2. Select files in the Send flow, launch NearShare through `NearShare.exe send <path> [<path> ...]`, or use the Explorer right-click menu after enabling it in Settings.
3. Select the paired destination in NearShare, or choose the paired device from the `Send using NearShare` Explorer submenu.
4. Keep receive mode active on the destination.
5. Start the transfer and watch progress on both sides.

If the paired device is not reachable, NearShare asks you to open receive mode or create/connect a private connection.

Explorer right-click flow:

1. Pair the Android device first.
2. On Windows, open NearShare Settings and install Explorer integration.
3. Select one or more files in Windows Explorer.
4. Right-click and choose `Send using NearShare`.
5. Choose the paired device from the submenu.
6. NearShare opens and batches the selected files for that device.

The Explorer menu is opt-in and per Windows user. It targets selected files, not folder context menus. Reinstall it from NearShare Settings after pairing or removing devices so the submenu reflects the current paired-device list.

## Private Connection Fallback

Private connection is a local route fallback, not a trust model.

1. Create a private connection from NearShare when the paired device is unreachable.
2. Join it from the other device using QR or manual details where supported.
3. Retry the same paired-device send after the route starts.

In NearShare 1.0, private connections are created from Android. Android shows both a NearShare QR and a standard Wi-Fi QR, plus manual network details and a 9-character security code. Windows can join using the manual details. Android can join from a NearShare QR or manual details through the Android system Wi-Fi prompt.

Windows-hosted private connection creation is outside the NearShare 1.0 user workflow. Use the same Wi-Fi, an Android-created private connection/hotspot, or Windows' own hotspot UI outside NearShare when Windows must provide the route.

## Transfer Sounds

Transfer sounds are configurable. When enabled, NearShare plays a sound once for whole-batch success and once for whole-batch failure or cancellation. It does not play per file or per progress update.

Reliability notes:

- Low storage, Wi-Fi interruption, hotspot changes, PC sleep, or Android background restrictions can interrupt a transfer.
- Use Retry when NearShare offers it. If devices cannot reach each other after a network change, reconnect them to the same local route or use the private-connection fallback.
- Endpoint refresh uses stored paired endpoints, UDP discovery, and signed reachability checks before sending.
