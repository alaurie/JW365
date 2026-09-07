# Changelog

All notable changes to the **JW365** project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [0.1.7] - 2026-09-07

### Added
- **Silent-First Session Authorization**: FreeRDP session authentication handshakes now resolve silently in the background off-screen using active session cookies, eliminating window popups during connection.
- **Graceful Fallback Prompt**: If user interaction (e.g. MFA, password change, consent) is required, an interactive dialog seamlessly appears after a 1.2s threshold.

---

## [0.1.6] - 2026-09-07

### Added
- **Hardware-Accelerated GDI (`/gdi:hw`)**: Enabled OpenGL 2D compositing offload by default.
- **32-Bit True Color (`/bpp:32`)**: Enabled 32-bit color rendering by default.
- **Optimized FreeRDP Performance Defaults**: Enabled `/network:auto`, `+async-update`, `+async-channels`, `+auto-reconnect`, `/auto-reconnect-max-retries:10`, `/gfx:progressive`, `+clipboard`, and `+dynamic-resolution` by default.

### Removed
- **Shared Folder Redirection**: Removed `/drive:Share...` option and configuration parameters.

---

## [0.1.5] - 2026-09-07

### Fixed
- **Clean Disconnect Status**: Terminating an active session via the "Disconnect" button or closing the FreeRDP window now marks the session as `Idle` / `Connect` instead of reporting `Failed` / `Retry`.
- **Exit Signal Handling**: Normalized Linux process exit signals (`143` SIGTERM, `130` SIGINT, `129` SIGHUP) as clean disconnects.

---

## [0.1.4] - 2026-09-07

### Fixed
- **Clean FreeRDP Version Display**: Parsed version strings with regex so FreeRDP reports cleanly as `(v3.30.0)` instead of repeating `(This is FreeRDP version 3.30.0 (3.30.0))`.
- **Automatic Feed Discovery on Sign-In**: Eliminated the race condition where `refreshWorkspacesAsync()` evaluated before `authenticated.set(true)` completed, removing the need to manually click "Refresh".

---

## [0.1.3] - 2026-09-07

### Added
- **Card Context Menu**: Right-click menu on Cloud PC tiles with options to Connect (Default, Fullscreen, Windowed, Multi-Monitor), Restart Session, View Session Log, and Open .RDP File.
- **Restart Session Action**: Cleanly disconnects and reconnects an active session with one click.
- **Startup Auto-Connect Option**: Preference to automatically connect to the primary Cloud PC on launch.

---

## [0.1.2] - 2026-09-07

### Fixed
- **FreeRDP SDL Hotkey Interception**: Automated generation of `~/.config/freerdp/sdl-freerdp.json` mapping `SDL_KeyModMask` to `KMOD_RCTRL` and `SDL_Disconnect` to `F12`, fixing the issue where pressing `Shift + D` in Windows closed the remote window.

---

## [0.1.1] - 2026-09-07

### Security
- **Machine-Bound AES-256-GCM Encryption**: Tokens cached at rest are now encrypted using AES-256-GCM keyed to `/etc/machine-id` and user login identity via 100,000 rounds of PBKDF2-HMAC-SHA256 (`0600` permissions).
- **Plaintext Shredding**: Legacy unencrypted `token-cache.json` files are automatically migrated and deleted.

---

## [0.1.0] - 2026-09-07

### Initial Release
- Initial release of JW365 for Linux (Wayland & X11).
- Microsoft Entra ID (Azure AD) OAuth 2.0 PKCE authentication with persistent session cookies.
- FreeRDP 3.x process supervisor with native PTY allocation and live logging.
- Multi-format packaging: Native Debian package (`.deb`), native RPM (`.rpm`), and portable tarball (`.tar.gz`).
