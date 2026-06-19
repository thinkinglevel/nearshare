# Release Process

Packaged release artifacts are published through GitHub Releases.

Release binaries must not be committed to the source repo. They belong in GitHub Releases or another explicit release distribution channel.

The in-app update check uses GitHub Releases for `thinkinglevel/nearshare` without embedded credentials. Do not embed a GitHub token in the Android or Windows app for update checks. If no eligible release exists, the app should show that no update is available. When a non-draft, non-prerelease release includes the expected platform package, the Settings screen can download it and then ask the user whether to install it.

The updater does not silently install releases. Android and Windows must still show their normal installer permission/confirmation flow.

Downloaded updater packages are saved to a visible `NearShare Updates` folder under the user's Downloads location before the app offers `Install` / `Not now`.

The updater selects assets by platform extension:

- Android: `.apk`
- Windows: installer `.exe`
- Checksum: an asset name containing `checksum`

## Expected Release Contents

Windows:

- installer `.exe`;
- checksum file;
- release notes.

Android:

- direct-install `.apk` for normal users;
- checksum file;
- release notes.

## Release Quality Bar

Before publishing a release:

1. Confirm source tag/version.
2. Build from clean source.
3. Run relevant tests.
4. Verify Android share/send workflow.
5. Verify PC to Android receive workflow.
6. Verify pairing and unpaired rejection.
7. Verify Windows firewall/setup behavior.
8. Generate checksums.
9. Publish artifacts through GitHub Releases.
10. Use a stable semver tag such as `v1.0` so in-app update checks can compare the installed version with the latest release.
11. Include a checksum text asset named with `checksum` so the in-app updater can verify downloaded packages before offering install.

Windows installer packages are built with Inno Setup from the published WPF output.

## Local Signing And Packaging Inputs

Android release signing uses ignored local files under `apps/android/`:

- `nearshare-release.jks`
- `key.properties`

Back up these files securely. Losing the Android release keystore prevents normal app updates from the existing package.

Windows installer packages use Inno Setup. Maintainers using Inno Setup for commercial activity should review JRSoftware's current license guidance before publishing.

## Packaging Commands

Android:

```powershell
cd apps/android
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease
```

Windows:

```powershell
cd apps/windows
dotnet test PCMobileLink.Windows.sln --no-restore
dotnet publish src\PCMobileLink.Windows.App\PCMobileLink.Windows.App.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=false -p:PublishReadyToRun=true -o publish\win-x64
& "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" installer\nearshare.iss
```
