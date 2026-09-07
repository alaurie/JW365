# JW365 - Windows 365 & AVD Linux Client

**JW365** is a modern, high-performance Linux client for **Windows 365 Cloud PCs** and **Azure Virtual Desktop (AVD)** built with **Java 25+** and **OpenJFX 25**. It provides a native desktop experience with automated Microsoft Entra ID authentication, background workspace synchronization, and FreeRDP session supervision.

---

## Highlights

- **Modern Java 25 Core**: Built with Project Loom **Virtual Threads**, **Records**, **Sealed Types**, **Pattern Matching**, and native **HTTP/2 Client**.
- **Automated In-App Authentication**: Embedded WebEngine completes Microsoft Entra ID OAuth 2.0 PKCE sign-in with **zero URL copying or terminal pasting**.
- **Persistent Session Cookies**: Backed by disk storage (`~/.local/share/jw365/webview-cookies.json`), keeping you signed in across application restarts.
- **Automated FreeRDP Session Authorization**: Seamlessly intercepts FreeRDP's terminal OAuth prompt, captures the redirect code via WebEngine, and writes it directly to FreeRDP's standard input in real time.
- **Native Wayland & X11 Support**: Runs cleanly under Wayland (`WAYLAND_DISPLAY`, `SDL_VIDEODRIVER=wayland,x11`) and X11/XWayland.
- **Dynamic Desktop Resizing & Clipboard**: Pre-configured with FreeRDP `+dynamic-resolution` (remotely adapts resolution when resizing the Linux window) and `+clipboard` (bidirectional copy/paste of text, files, and images).
- **Tuned Low-Memory Footprint**: Strict heap boundaries (`-Xms24m -Xmx192m`), Serial GC (`-XX:+UseSerialGC`), and aggressive memory return (`-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20`) keeping active heap around **~20–25 MB**.
- **Self-Contained Packages**: Bundles a stripped minimal Java 25 runtime via `jlink`. **Users do not need Java installed.**

---

## Prerequisites

JW365 delegates the RDP protocol handling to **FreeRDP 3.x**:

```bash
# Debian / Ubuntu (Trixie, Noble, or Backports)
sudo apt update && sudo apt install freerdp3-sdl freerdp3-x11
```

*(Optional)* `freerdp3-wayland` is also supported.

---

## Installation

### Option 1: Native Debian Package (`.deb`) - *Recommended*

Download or build `jw365_1.0.0_amd64.deb` and install with `apt`:

```bash
sudo apt install ./build/distributions/jw365_1.0.0_amd64.deb
```

- Installs to `/opt/jw365`.
- Integrates with GNOME/KDE application search and dock (`StartupWMClass=org.alaurie.jw365.gui.Jw365App`).
- Automatically resolves dependencies via `apt`.

### Option 2: Portable Standalone Tarball (`.tar.gz`)

Runs on any Linux distribution (Fedora, Arch, openSUSE, Debian) without `sudo` or package installation:

```bash
# Extract archive
tar -xzf build/distributions/jw365-1.0.0-linux-x64.tar.gz
cd jw365

# (Optional) Register desktop and dock icon in your user profile:
./install-desktop.sh

# Run directly:
./bin/jw365
```

---

## Building from Source

### Requirements
- **JDK 25** (e.g. Oracle GraalVM 25 or OpenJDK 25)
- **Gradle 9+** (or use `./gradlew`)

### Build Commands

```bash
# Run unit test suite (30 tests)
./gradlew test

# Run application locally in development mode
./jw365
# or
./gradlew run

# Build the native Debian package (.deb)
./gradlew deb
# Output: build/distributions/jw365_1.0.0_amd64.deb

# Build the native RPM package (.rpm) (requires rpmbuild: sudo apt install rpm / sudo dnf install rpm-build)
./gradlew rpm
# Output: build/distributions/jw365-1.0.0-1.x86_64.rpm

# Build the portable standalone archive (.tar.gz)
./gradlew portable
# Output: build/distributions/jw365-1.0.0-linux-x64.tar.gz
# Build all distribution packages
./gradlew packageAll
```


### Release Automation (GitHub Actions)

Pushing a version tag automatically triggers the GitHub Actions workflow (`.github/workflows/release.yml`), which tests and builds the `.deb`, `.rpm`, and `.tar.gz` packages and attaches them to a new GitHub Release with SHA-256 checksums:

```bash
# Create and push a version tag
git tag v1.0.0
git push origin v1.0.0
```
---

## Configuration & Storage (XDG Compliance)

JW365 adheres to the Linux XDG Base Directory specification:

| Path | Purpose |
| :--- | :--- |
| `~/.config/jw365/config.json` | Application preferences (tenant, FreeRDP binary, display scaling, audio). |
| `~/.local/share/jw365/token-cache.json` | Encrypted/restricted (`0600`) OAuth tokens and refresh tokens. |
| `~/.local/share/jw365/workspaces.json` | Cached workspace feed metadata for instantaneous startup display. |
| `~/.local/share/jw365/webview-cookies.json` | Microsoft Entra ID session cookies for Single Sign-On (SSO). |
| `~/.local/share/jw365/feed/` | Downloaded `.rdp` session configuration files. |
| `~/.local/share/jw365/logs/` | Session execution logs (automatically pruned to the 10 most recent). |

---

## Project Structure

```text
JW365/
├── src/
│   ├── main/
│   │   ├── java/org/alaurie/jw365/
│   │   │   ├── auth/           # Entra ID OAuth 2.0 PKCE, TokenStore, JWT parser, cookies
│   │   │   ├── feed/           # Workspace Feed discovery XML parser & HTTP/2 client
│   │   │   ├── rdp/            # FreeRDP locator, argument builder, PTY process supervisor
│   │   │   ├── config/         # XDG paths, configuration manager, workspace cache
│   │   │   └── gui/            # JavaFX 25 App, reactive AppState, views, tiles, dialogs
│   │   └── resources/          # Application icons and Azure/Fluent dark CSS
│   ├── package/resources/      # jpackage desktop templates and portable install helper
│   └── test/java/              # Comprehensive unit test suite (30 tests)
├── build.gradle.kts            # Consolidated build script, jlink, and jpackage pipeline
├── settings.gradle.kts         # Root project settings
├── jw365                       # Root executable development launcher
└── README.md                   # Documentation
```

---

## License

This project is licensed under the Apache License 2.0.
