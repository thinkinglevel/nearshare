# Getting Started

NearShare shares files between nearby paired Windows PCs and Android phones.

Supported local connection shapes:

- Android and Windows PC are connected to the same Wi-Fi.
- Android hotspot is enabled and the Windows PC is connected to that hotspot.

These instructions describe the current NearShare 1.0 workflow.

## Basic Setup

1. Install the Windows app.
2. Choose where files received on the PC should be saved.
3. Install the Android app.
4. Pair devices using QR or the visible 9-character pairing code.
5. Choose receive behavior on devices that should receive files:
   - manual receive mode;
   - always-on receive mode.

## Pairing

Being on the same Wi-Fi is not trust. Pairing creates the device identity used by later signed transfer requests.

1. On the receiving device, start pairing.
2. On the other device, scan the QR code or enter the 9-character code.
3. Approve the request on the receiving device.
4. Confirm that the paired device appears in the device list.

## Sending From Android

NearShare 1.0 sends multiple files sequentially:

1. Keep receive mode active on the destination.
2. Select one or more files on Android.
3. Open the Android share sheet.
4. Choose a paired-device direct target, or choose NearShare.
5. Select the paired device from the top dropdown when using the generic NearShare target.
6. Watch the current-file and overall progress bars.
7. Use Cancel if you want to stop the transfer, or Retry if an interrupted transfer can be attempted again.

See `docs/user/sending-files.md` for current limits and safety behavior.

## Sending From Windows

1. Select one or more files in the Windows app, or right-click selected files in Explorer after enabling Explorer integration from NearShare Settings.
2. Select the paired destination in NearShare or from the `Send using NearShare` Explorer submenu.
3. Send when the destination is ready to receive.
4. Watch transfer progress on Windows and on the receiving device.

If always-on receive is disabled on the destination, open NearShare there and enable manual receive mode before sending.

## Private Connection Fallback

If paired devices cannot reach each other on the current network, create a private connection in NearShare and join it from the other device using QR or manual details. Private connection only creates a local route; it does not replace pairing or signed transfer authentication.

## Always-On Receive

Always-on receive is optional.

When enabled, Android shows a visible notification while it is ready to receive. This is intentional; hidden background receiving would be bad UX and unreliable on Android.
