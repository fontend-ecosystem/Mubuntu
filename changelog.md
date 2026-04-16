# Changelog

## 2026-04-15

### Added
- Bundled Ubuntu 24.04 ARM64 rootfs bootstrap flow.
- Bundled arm64 helper binaries for `hello`, `proot`, and extraction support.
- Release signing workflow in Gradle, with exported APK output in the downloads directory.
- Git repository initialization and baseline ignore rules for local build artifacts and large payloads.

### Changed
- Renamed the app from `Ubuntu4M` to `Mubuntu`.
- Replaced the launcher icon with the robot-hugging-penguin artwork.
- Refined the terminal UI and input behavior to better match a Termux-style experience.
- Auto-launches Ubuntu through `proot` from the terminal session.
- Configured Ubuntu apt sources for a domestic mirror and staged CA bundle support for HTTPS.

### Notes
- Large rootfs tarballs, local downloads, build outputs, and signing materials are intentionally excluded from Git.
