# 0002 - Windows Stack

Status: Accepted
Date: 2026-06-09

## Context

NearShare needs a lightweight Windows application for a nearby local-link file sharing product. The Windows app is expected to provide:

- a local receiver/listener after pairing and protocol decisions are finalized;
- pairing UI with QR/manual fallback;
- receive-folder settings;
- PC-to-Android send UI;
- active transfer progress, cancel, retry, and resume controls;
- tray presence;
- firewall/setup diagnostics.

The product must avoid cloud accounts, telemetry, unnecessary background bloat, and heavy desktop frameworks unless explicitly approved.

## Options Considered

### C# / .NET / WPF

Pros:

- pragmatic Windows desktop development path;
- mature filesystem, networking, tray, settings, and diagnostics APIs;
- compatible with a lightweight local utility UX;
- mature testing and packaging ecosystem;
- lower complexity than C++/WinUI and less runtime weight than Electron.

Cons:

- less visually modern than WinUI 3 by default;
- requires .NET SDK for development and usually a .NET runtime or self-contained packaging for users;
- Windows-only UI implementation.

### WinUI 3

Pros:

- modern Windows UI stack;
- good long-term Microsoft direction.

Cons:

- more packaging/runtime complexity for this project stage;
- tray and utility-app behavior can be more cumbersome than WPF;
- adds friction before protocol and pairing are finalized.

### Rust / Tauri

Pros:

- lightweight Rust core potential;
- web UI flexibility;
- attractive if a shared Rust protocol core is chosen later.

Cons:

- extra cross-language complexity;
- less direct for native Windows tray/firewall/settings UX;
- not needed before protocol decisions are finalized.

### Electron

Pros:

- fast UI development for web developers;
- broad ecosystem.

Cons:

- heavy runtime footprint;
- poor fit for the lightweight local utility requirement.

### Native C++ / WinUI

Pros:

- maximal Windows-native control;
- high performance.

Cons:

- slower development;
- higher maintenance burden;
- unnecessary complexity for the initial release.

## Decision

Use C# / .NET / WPF for the Windows implementation.

Initial project architecture should separate:

- WPF app/UI and tray integration;
- core domain models and transfer state;
- infrastructure adapters for settings, filesystem, networking, and platform diagnostics;
- tests for core and infrastructure behavior.

The Windows runtime model for NearShare 1.0 is a tray-capable foreground desktop app, not a Windows Service.

## Consequences

- Windows app code should live under `apps/windows/`.
- The app uses separate app, core, infrastructure, and test projects.
- Protocol and pairing behavior remain behind explicit interfaces where practical.
- NearShare 1.0 uses an Inno Setup installer `.exe` for Windows distribution.
- MSI/MSIX can be reconsidered later if update, signing, or enterprise distribution needs justify the packaging change.
- Development requires the .NET SDK.

## Follow-up Work

- Completed for 1.0: Android stack is selected and implemented separately.
- Completed for 1.0: Windows solution, app, core, infrastructure, and focused test projects exist.
- Completed for 1.0: pairing/listener/file-receive behavior is implemented behind documented local HTTPS and signed-request security decisions.
- Post-1.0: add Windows Authenticode signing when certificate management is ready.
