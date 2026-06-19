# Windows Install Guide

Download the Windows installer from GitHub Releases. Do not download random build artifacts from the source tree.

The in-app update check uses GitHub Releases without embedded credentials. If no eligible release exists, the app reports that no update is available.

Downloaded updater packages are saved under `Downloads\NearShare Updates` before Windows asks whether to launch the installer.

Published installer type:

- installer `.exe`

The installer is built with Inno Setup.

## Setup Steps

1. Install the Windows app.
2. Allow the Windows firewall prompt if the app explains that local receiving is required.
3. Choose the folder for received files.
4. Pair with another device using QR or the 9-character pairing code.
5. Choose manual or always-on receive mode.

## Optional Explorer Integration

NearShare can add a Windows Explorer right-click action for selected files.

1. Open NearShare on Windows.
2. Open Settings.
3. Under Explorer integration, choose Install.
4. In Explorer, select one or more files, right-click, and choose `Send using NearShare`.
5. Choose the paired device from the submenu.

The Explorer menu is registered for the current Windows user. It is not installed silently by the Windows installer. If you pair or remove devices later, open NearShare Settings and install the Explorer menu again so the device submenu is refreshed.

## Firewall Note

The app is for local network sharing, not public internet exposure.

Windows firewall behavior can affect discovery and transfer. If transfers fail, check whether the app is allowed on the current Wi-Fi/hotspot network profile.
