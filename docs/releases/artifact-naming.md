# Artifact Naming

Use predictable names so normal users know what to download.

Suggested pattern:

```text
nearshare-android-vX.Y.Z.apk
nearshare-windows-vX.Y.Z-x64.exe
nearshare-vX.Y.Z-checksums.txt
```

Rules:

- Use `.apk` for Android direct installs.
- Use one clear Windows installer format per release unless there is a real reason to publish multiple.
- Include architecture when relevant, such as `x64` or `arm64`.
- Include version in every artifact name.
- Keep the platform file extension accurate. The in-app update check looks for `.apk` on Android and installer `.exe` on Windows.
- Publish one checksum text file with SHA-256 entries for each installable package. The updater matches the checksum line by package file name.
- Use checksum lines that include both the SHA-256 hex digest and exact file name, for example `0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef  nearshare-windows-v1.0-x64.exe`.
- Do not publish raw debug build outputs as user releases.
