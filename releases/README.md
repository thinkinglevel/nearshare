# Releases

This folder documents release handling only.

Installable release binaries are not committed to this source repository.

Normal users should download installable files from the project's GitHub Releases page.

Release artifacts:

- Android `.apk` for direct installation.
- Windows installer `.exe`.
- SHA-256 checksum file for verification.

Use `nearshare-*` artifact names. The in-app update check looks at public GitHub Releases and selects platform packages by file extension.

Do not place generated release binaries in this folder unless the project explicitly changes release policy.
