# Contributing

NearShare has a stable 1.0 release, and ongoing development continues on `main`. Contributions should follow the standards expected for a public Apache-2.0 project.

## Scope

NearShare is a local-link file sharing app for nearby Windows PCs and Android phones.

Do not add:

- cloud relay or account sync behavior;
- public internet receiver exposure;
- telemetry;
- unapproved runtime dependencies;
- unauthenticated receive paths;
- generated release binaries.

## Development Setup

Android app:

```powershell
cd apps/android
.\gradlew.bat :app:testDebugUnitTest
```

Windows app:

```powershell
cd apps/windows
dotnet test PCMobileLink.Windows.sln --no-restore
```

Run the focused tests for the area you changed. Do not commit build output, local state databases, screenshots, release artifacts, or private credentials.

## Documentation

Update docs when behavior changes:

- user-facing setup, pairing, sending, receiving, troubleshooting, or release behavior;
- protocol, discovery, private connection, security, or architecture behavior;
- app README files when platform implementation status changes.

Keep user docs separate from implementation docs.

## Security Rules

- Never accept unauthenticated file uploads.
- Treat same Wi-Fi as reachability, not trust.
- Private connection is route setup only; it must not bypass pairing or signed transfer authentication.
- Sanitize received file names and prevent path traversal.
- Keep always-on receive explicit and visible.

## Pull Requests

Before opening a pull request:

1. Explain the user-visible behavior change.
2. List affected workflows and dependent areas.
3. Run focused tests and include the commands/results.
4. Call out remaining risks or real-device validation gaps.

Do not include unrelated refactors in feature or bug-fix pull requests.
