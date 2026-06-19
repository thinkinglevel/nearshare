# Windows UI Design

## Product Name

NearShare

## Design Goal

NearShare should feel like a small, polished Windows utility: fast to understand, calm under failure, and focused on local paired-device file sharing between Windows and Android.

The UI should not look like a developer dashboard, cloud sync product, or security tool. Normal users should understand what to do without learning terms like protocol, listener, authenticated session, or local network interface.

## Visual Direction

- Windows 11-inspired WPF UI.
- Light theme first; dark theme can come later.
- Clean white/off-white background.
- Rounded cards and buttons.
- Soft blue/indigo accent for primary actions.
- Green only for success/reachable states.
- Amber for waiting/manual action states.
- Red only for actionable failures.
- Subtle borders instead of heavy shadows.
- Segoe UI / system font by default.
- Icons should be simple and functional, not decorative clutter.

Suggested tokens:

```text
Background:        #F6F8FB
Surface:           #FFFFFF
Surface elevated:  #FFFFFF
Text primary:      #111827
Text secondary:    #6B7280
Border:            #E5E7EB
Primary:           #2563EB
Primary hover:     #1D4ED8
Success:           #16A34A
Waiting:           #D97706
Danger:            #DC2626
Radius small:      8px
Radius card:       16px
Spacing unit:      8px
```

## Tone And Copy

Use direct, user-facing language:

- "Send files"
- "Receive files"
- "Choose receive folder"
- "Waiting for your phone"
- "Open NearShare on Android to receive"
- "This device is paired"
- "Phone is not reachable on this network"

Avoid jargon in main UI:

- listener
- socket
- TLS
- mDNS
- protocol
- peer identity
- authenticated channel

Technical details can appear under diagnostics or developer logs only.

## Main Window Layout

Use a persistent vertical sidebar navigation shell.

Sidebar order:

1. Dashboard - default selected view.
2. Devices - paired devices, currently connected devices, and new pair connection setup.
3. Settings - bottom-most nav item.

The sidebar should stay visible during normal use so users always know where they are. It should use text labels, not icon-only navigation.

### Dashboard

Purpose: give the user the current save location, the two main actions, and transfer status at a glance.

Top content order:

1. Current save path card
   - label: "Current save path";
   - show the configured receive folder path;
   - provide "Open" and "Change" actions later;
   - if not configured, show a clear setup prompt.
2. Two primary action buttons below the save path
   - "Send" for Windows-to-paired-device sending;
   - "Receive" for receiving from paired devices.
3. Receive mode status near the Receive action
   - value comes from Settings;
   - supported options: Manual receive and Always on;
   - always-on must remain opt-in and visible.
4. Transfer progress section
   - shown only while a transfer is active, not while idle or merely queued;
   - count-based batch progress bar for Android-to-PC receive progress;
   - completed/total count UI from transfer metadata, e.g. "2 of 5 completed";
   - current file/device text with wrapping enabled so long filenames and device names are never clipped;
   - success/failure/cancel state summary.

Dashboard should remain simple. Advanced pairing, network, and context-menu details belong in Devices or Settings.

### Send Flow

Purpose: send one or more selected files from Windows to a paired device.

Current 1.0 entry points:

- Dashboard "Send" button;
- Explorer action: `Send using NearShare`.

Future entry points can include drag and drop into the dashboard/send area, but the UI and docs must not present that as current behavior until implemented.

Screens/states:

1. Select files
   - file picker button;
   - selected-file list with remove controls.
   - future drag/drop area only after the app implements it.
2. Choose device
   - reachable paired devices first;
   - offline paired devices shown with explanation.
3. Transfer progress
   - current file name, wrapped when needed;
   - total progress;
   - completed/total count;
   - per-file progress when available;
   - cancel button.
4. Private connection fallback
   - offer connection details entry when the paired device is unreachable;
   - retry a saved send after the local route starts.
5. Result
   - success summary;
   - actionable failure message.

### Receive Flow

Purpose: receive Android-to-PC files according to the receive mode configured in Settings.

Dashboard Receive behavior:

- Manual receive mode: user explicitly starts a temporary receive-ready state.
- Always-on mode: app shows that always-on receiving is enabled and visible; closing the main window hides NearShare to the system tray and keeps listening until the user chooses Exit NearShare from the tray menu.
- Start with Windows is an opt-in setting. Enabling it shows an in-app confirmation dialog and automatically enables Always-on receiving. On Windows login, NearShare starts with `--background`, hides to the tray, and starts the receiver/discovery without requiring the main window to stay open.

### Custom Dialogs

To maintain a consistent modern aesthetic without pulling in heavy external UI frameworks, the Windows app uses `ThemedConfirmationDialog` to replace the default OS `MessageBox`. This custom dialog features:
- DWM-rounded corners on the OS window (or simulated rounding via clipping on older systems);
- explicit primary, secondary, and danger button styles;
- drop-shadow and transparent background geometry.

Use `ThemedConfirmationDialog.Confirm(...)` for destructive actions (like removing a paired device) and major state changes (like enabling Start with Windows) instead of default message boxes.
- The receive listener binds to a dynamic available HTTPS port at startup; paired phones refresh the current endpoint through UDP discovery on port `53318`, then signed reachability validation, instead of relying on a fixed saved HTTPS port.
- If the user changes the receive folder while a manual or always-on listener is active, the Windows app restarts the local receiver/discovery immediately so the next incoming file uses the new folder instead of the path captured by the old listener.

Content:

- current save path;
- local readiness status;
- pairing status;
- network diagnostic link.

Receiving must not imply unauthenticated uploads are allowed. Receive UI must stay tied to paired-device readiness, and incoming files must be accepted only after paired identity and signed request validation pass.

### Devices

Purpose: show device trust and reachability separately.

Content:

- establish a new pair connection through a clear "Pair new device" action;
- enter a pairing code from another device;
- already paired devices;
- currently connected/reachable devices;
- offline paired devices;
- pairing action;
- last seen status;
- device type/name;
- clear labels such as "Paired", "Connected", "Offline", and "Needs manual receive mode".

Same network or current connection must never be presented as authorization. Paired identity is the trust source.

### Settings

Purpose: configure behavior that should not clutter the dashboard.

Settings is the bottom-most sidebar item.

Content:

- receive mode setting:
  - Manual receive;
  - Always on;
- current save path setting;
- PC display name;
- Explorer integration controls:
  - Install `Send using NearShare`;
  - Uninstall `Send using NearShare`;
  - show current registration status when detectable;
- start with Windows toggle, default off;
- network diagnostics;
- transfer sounds toggle;
- update check action;
- about NearShare.

Do not hide always-on/background behavior. Any future auto-start or background receive behavior must be explicit.

## Explorer Integration UX

The desired action label is:

```text
Send using NearShare
```

Expected behavior:

1. User enables Explorer integration from NearShare Settings.
2. User selects one or more files in Windows Explorer.
3. User chooses `Send using NearShare`.
4. User chooses a paired Android device from the submenu.
5. NearShare opens to the Send flow with those files batched for that device.
6. If the Android device is not ready, NearShare explains how to open manual receive mode.

The context-menu action must not hide transfer state. The user chooses the device from the submenu, then NearShare must show destination and transfer status while files are sent.

The current Explorer registration targets files, not folder context menus. If folder sending is added later, the registry integration and documentation must explicitly cover directory targets.

## Accessibility And Usability

- All primary actions should have visible text labels.
- Keyboard navigation should work for primary flows.
- Error messages should explain the next action.
- Do not rely on color alone for status.
- Long paths should truncate in the middle and show full path on hover/copy.
- Many selected files should be summarized without freezing the UI.

## Non-Goals For Initial UI

- No cloud account UI.
- No telemetry prompt.
- No public internet transfer controls.
- No advanced protocol controls in normal screens.
- No privileged Android overlay concepts.
- No hidden background receiving.
