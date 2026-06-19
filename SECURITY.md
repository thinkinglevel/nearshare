# Security Policy

NearShare is local-link first, but local networks are not trusted. Security issues should be handled carefully.

## Supported Versions

Security fixes currently target the latest stable release and the main development branch.

## Reporting A Vulnerability

Do not file public issues with exploit details.

Report security concerns through GitHub Security Advisories when available, or email contact@nearshare.thinkinglevel.com.

Include:

- affected platform: Android, Windows, or shared protocol;
- reproduction steps;
- whether pairing is required;
- whether an unpaired device can trigger the issue;
- impact on file write paths, authentication, receiver exposure, or private connection behavior.

## Security Expectations

- No unauthenticated uploads.
- No default public internet receiver.
- No private connection bypass of paired-device authentication.
- Pinned receiver TLS identity plus signed requests after pairing.
- Safe filename handling and path traversal prevention.
- Explicit user-visible always-on receive behavior.
