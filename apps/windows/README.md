# Windows App

User-facing app name: **NearShare**.

Windows MVP stack: C# / .NET 8 / WPF.

Decision records:

- `../../docs/decisions/0002-windows-stack.md`
- `../../docs/decisions/0003-windows-product-name-ui-and-shell-integration.md`

UI design direction: `../../docs/windows-ui-design.md`.

## Project Layout

- `PCMobileLink.Windows.sln` - Windows solution file.
- `src/PCMobileLink.Windows.App/` - WPF app shell. Keep UI, tray integration, QR rendering, and app composition here.
- `src/PCMobileLink.Windows.Core/` - pure domain behavior such as transfer states and safe filename policy.
- `src/PCMobileLink.Windows.Infrastructure/` - adapters for filesystem, settings, networking, and Windows diagnostics.
- `tests/PCMobileLink.Windows.Core.Tests/` - tests for core behavior.
- `tests/PCMobileLink.Windows.Infrastructure.Tests/` - tests for infrastructure behavior.

## Prerequisites

- .NET SDK 8.x.

This workspace currently pins SDK `8.0.421` with `global.json` and allows roll-forward to the latest installed 8.0 feature band.

## App Dependencies

- `QRCoder` NuGet package renders Windows pairing QR images in the WPF app.
- `Microsoft.AspNetCore.App` framework reference is used by Infrastructure for the local HTTPS pairing listener.

## Commands

From `apps/windows/`:

```bash
export DOTNET_CLI_TELEMETRY_OPTOUT=1

dotnet restore PCMobileLink.Windows.sln

dotnet build PCMobileLink.Windows.sln --no-restore

dotnet test PCMobileLink.Windows.sln --no-build

dotnet run --project src/PCMobileLink.Windows.App/PCMobileLink.Windows.App.csproj
```

## Current Scope

The Windows app implements the WPF shell, pairing, authenticated receive, authenticated send, private connection join, transfer sounds, and manual GitHub release update checks.

Windows state currently includes:

- QR and 9-character pairing-code offer generation;
- local HTTPS pairing listener;
- persisted PC pairing certificate under `%APPDATA%\\NearShare\\pc-pairing-certificate.pfx`;
- pending pairing request display in Devices;
- approve/reject pairing actions;
- paired-device storage under `%APPDATA%\\NearShare\\paired-devices.json`;
- app settings storage under `%APPDATA%\\NearShare\\settings.json`;
- manual and always-on receive modes;
- dynamic HTTPS receive listener plus UDP endpoint discovery on port `53318`;
- authenticated reachability checks;
- resumable transfer-session create/chunk/cancel endpoints;
- safe filename handling, temp-session storage, checksum verification, and collision-safe writes;
- Windows send flow for one or more selected files;
- opt-in Explorer right-click menu registration for selected files through Settings;
- Android receive endpoint discovery and signed Windows-to-Android transfer;
- private connection manual join flow;
- transfer sound toggle;
- manual GitHub Releases update check with checksum-aware download.

The app supports an opt-in Windows Explorer action labeled **Send using NearShare**. Users install or remove it from NearShare Settings. The menu is registered under the current Windows user and is generated from the paired-device list on that PC.

Explorer flow:

1. Pair at least one Android device.
2. Open Settings and install Explorer integration.
3. Select one or more files in Windows Explorer.
4. Choose **Send using NearShare**.
5. Choose the paired device from the submenu.

The `send` command parses selected paths and opens NearShare with those paths shown in the send queue. Multiple Explorer invocations for selected files are batched into one send for the chosen device. Sending uses authenticated transfer sessions when the selected paired device is reachable and receiving.

The Explorer menu currently targets files, not folder context menus. Reinstall the menu from Settings after pairing or removing devices so the submenu refreshes.

Do not accept unauthenticated uploads. A file receiver/uploader must require paired-device identity and request authentication before writing files.

## Development Rules

- Follow test-first development for custom behavior.
- Keep `PCMobileLink.Windows.Core` free of WPF and Windows UI dependencies.
- Do not accept unauthenticated file uploads.
- Do not expose a receiver before pairing/authentication exists.
- Keep Android and Windows aligned through docs and shared protocol/test fixtures before introducing shared runtime code.
