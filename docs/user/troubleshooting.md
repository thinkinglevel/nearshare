# Troubleshooting

These notes describe common troubleshooting areas for NearShare.

## Devices Cannot Find Each Other

Check:

- both devices are on the same Wi-Fi; or
- Android hotspot is enabled and the Windows PC is connected to that hotspot;
- Windows firewall is not blocking the app;
- guest Wi-Fi isolation is not enabled;
- QR, pairing-code, or private-connection fallback is available.

Discovery can fail on some networks even when transfer is possible.

## Pairing Code Cannot Be Found

The 9-character code is resolved through local discovery.

Check:

- both devices are on the same Wi-Fi or private connection;
- the code has not expired;
- the receiving device is still showing the pairing screen;
- firewall or guest Wi-Fi isolation is not blocking local UDP traffic.

If code lookup still fails, scan the QR code instead.

## PC Cannot Send To Android

Check:

- Android manual receive mode is active; or
- Android always-on receive is enabled;
- Android shows the receive notification if always-on mode is active;
- both devices are still on the same local network path.

If Windows says the device is unreachable, create a private connection from the Android device or put both devices on the same Wi-Fi, then retry.

## Android Cannot Send To PC

Check:

- Windows app is running;
- PC receive folder is configured;
- firewall allows local receiving;
- the PC and Android are paired.

If Android says the device is unreachable, keep NearShare open on both devices, create a private connection if needed, then retry.

## Large Files Fail

Possible causes:

- not enough storage;
- Wi-Fi/hotspot interruption;
- app killed by Android background restrictions;
- PC sleep or network change.

## Update Check Cannot Find A Release

NearShare checks GitHub Releases without embedded credentials. If the update check cannot read the latest release or the expected platform asset, the app may show that no update is available. You can still download the published package directly from the GitHub Releases page.

## Checksum Fails

Do not install that downloaded update from inside the app. Download the package directly from the GitHub Releases page and compare the published SHA-256 checksum.
