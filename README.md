# NearShare

NearShare is an Apache-2.0 open-source nearby file sharing app for Windows PCs and Android phones.

It is designed for direct nearby-device sharing on a local connection:

- both devices on the same Wi-Fi, or
- Android phone hotspot enabled with the Windows PC connected to that hotspot, or
- a NearShare-created private connection where the platform allows it.

It is not a cloud sync service and does not require an internet relay.

## Status

NearShare 1.1.0 is the current stable release line.

Normal users should download NearShare from the project's GitHub Releases page, not from the source tree.

## Current User Workflows

Pairing:

1. Start pairing on one device.
2. Scan the QR code or enter the visible 9-character pairing code.
3. Approve the request on the receiving device.
4. After pairing, transfers use the paired-device identity, pinned local HTTPS, and signed requests.

Android share sheet to paired device:

1. Share files from Android using the system share sheet.
2. Select a paired-device direct target, or choose NearShare and use the paired-device dropdown.
3. Send files locally.
4. Track progress on Android and the receiving device.

Windows to paired device:

1. Select files in the Windows app, or enable Explorer integration in NearShare Settings and right-click selected files.
2. Select the paired destination in NearShare or from the `Send using NearShare` Explorer submenu.
3. Send files when the destination receive mode is active.
4. Track progress on Windows and the receiving device.
5. If the destination is unreachable, use the private-connection fallback or put both devices on the same local network.

Private connection fallback:

1. Create a private connection from NearShare when the normal LAN path fails.
2. Join it from the other device using QR or manual details where the platform allows it.
3. Approve the normal pairing request when prompted, so the device is saved for later transfers.
4. Retry the same paired-device transfer after the route is available.

## Downloads

Download NearShare from [GitHub Releases](https://github.com/thinkinglevel/nearshare/releases/latest).

Release artifacts are published through GitHub Releases:

- Android direct-install artifact: `.apk`
- Windows installer: `.exe`
- SHA-256 checksum text file

See [releases/README.md](releases/README.md) for how release artifacts are handled.

## Documentation

For normal users:

- [Getting started](docs/user/getting-started.md)
- [Windows install guide](docs/user/install-windows.md)
- [Android install guide](docs/user/install-android.md)
- [Pairing guide](docs/user/pairing.md)
- [Sending files](docs/user/sending-files.md)
- [Troubleshooting](docs/user/troubleshooting.md)

For contributors and maintainers:

- [Post-1.0 development plan](docs/implementation-plan.md)
- [Architecture](docs/architecture.md)
- [Product scope](docs/product-scope.md)
- [Transfer workflows](docs/transfer-workflows.md)
- [Pairing and discovery](docs/pairing-and-discovery.md)
- [Security model](docs/security-model.md)
- [Contributing](CONTRIBUTING.md)
- [Security policy](SECURITY.md)

## Security Note

Same Wi-Fi is not treated as trust. NearShare requires paired-device authentication before file transfer.

Do not expose receivers to the public internet unless the project explicitly adds a separate internet-transfer architecture.

## License

Licensed under the [Apache License 2.0](LICENSE).

Copyright 2026 ThinkingLevel. License and project contact: contact@nearshare.thinkinglevel.com.

## Acknowledgements

A special shoutout to [@OpenAI](https://github.com/openai) and [@Codex](https://github.com/openai/codex).
