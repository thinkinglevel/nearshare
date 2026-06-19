# 0003 - Windows Product Name, UI Direction, And Shell Integration

Status: Accepted
Date: 2026-06-09

## Context

The Windows app needs a public-facing identity, an understandable modern UI direction, and a Windows Explorer integration path for user-selected files.

The user confirmed:

- app name: NearShare;
- Explorer action wording: Send using NearShare;
- UI should be modern, slick, and understandable;
- repository should be ready for public GitHub distribution.

## Decision

Use **NearShare** as the Windows app display name and working product name.

Use **Send using NearShare** as the Windows Explorer context-menu label.

Use a modern, clean WPF UI direction:

- Windows 11-inspired layout;
- light theme first;
- rounded cards;
- generous spacing;
- clear primary actions;
- user-friendly wording instead of protocol jargon;
- visible status and progress;
- no cloud account, telemetry, or unrelated feature surfaces.

## Windows Explorer Integration Direction

NearShare 1.0 direction:

1. The Windows app accepts selected file paths through a command-line send entry point.
2. Explorer context-menu registration invokes that command-line entry point.
3. Registration is opt-in from NearShare Settings for 1.0.
4. The app supports multiple selected files where Windows shell invocation provides them as separate launches.

The preferred label is:

```text
Send using NearShare
```

The app command shape should be:

```text
NearShare.exe send <path> [<path> ...]
```

Classic context-menu registration is acceptable for NearShare 1.0. A modern Windows 11 top-level context menu extension can be revisited later if it becomes worth the packaging complexity.

## UI Information Architecture

Initial Windows app sections:

- Home
  - Send files to phone
  - Receive from phone
  - paired-device cards
  - recent activity summary
- Send
  - file picker
  - selected-file list
  - destination device picker
  - future drag/drop area after implementation
- Receive
  - configured receive folder
  - open folder button
  - pairing/readiness status
- Pairing
  - QR code/manual code pairing flow
- Settings
  - PC display name
  - receive folder
  - Explorer integration status
  - network diagnostics
  - about NearShare

## Consequences

- Windows project output produces a user-facing `NearShare.exe`.
- Source namespaces currently remain `PCMobileLink.Windows.*` to preserve repository/product lineage, but assembly/app display names should use NearShare.
- The shell integration must not bypass pairing or security controls. It should only select local files and start the send workflow.
- Shell registration should not be silently enabled without clear installer/app settings behavior.
- The 1.0 Explorer registration targets files, not folder context menus.
- The public repo should document that installable artifacts belong in GitHub Releases, not committed binaries.

## Follow-up Work

- Completed for 1.0: WPF app display name/title and assembly metadata use NearShare.
- Completed for 1.0: Windows UI design is documented.
- Completed for 1.0: command-line `send` behavior exists for selected paths.
- Completed for 1.0: opt-in Explorer integration can be installed or removed from NearShare Settings.
- Future: decide whether installer-managed automatic registration is worth adding.
