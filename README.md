# JW365 - Windows 365 Linux Client

**JW365** is a native desktop client for Linux that connects to your **Windows 365 Cloud PC** with a single click.

Microsoft does not provide an official Windows 365 desktop app for Linux. JW365 fills that gap by giving you a clean, dedicated application: sign in with your corporate Microsoft account, see your assigned Cloud PCs, and launch into your Windows desktop with full audio, microphone, multi-monitor, and clipboard support.

---

## Why JW365?

- **True 1-Click Sign-In**: Sign in directly with your Microsoft work or school account inside the app. No terminal commands, no copying URLs from browser address bars, and no manual token pasting.
- **Stay Logged In**: Remembers your work session securely so you don't have to authenticate or approve MFA prompts every time you open the app.
- **Dynamic Window Resizing**: Resize your remote desktop window on Linux and your Windows resolution instantly adapts on the fly—no reconnecting or blurry scaling.
- **Full Multi-Monitor Support**: Spans across multiple displays, including mixed setups with vertical/portrait and landscape screens.
- **Seamless Copy & Paste**: Copy and paste text, screenshots, and files seamlessly between your Linux desktop and your Cloud PC.
- **Smooth, Low-Lag Display**: Hardware-accelerated graphics (H.264 video, smooth scrolling, and ClearType font smoothing) are enabled by default for video calls and daily work.
- **Audio & Microphone Ready**: Redirection configured out of the box for PipeWire and PulseAudio—ideal for Microsoft Teams, Slack, and browser calls.
- **Automatic Reconnection**: Recovers silently if your Wi-Fi drops or your VPN reconnects without kicking you out of your session.
- **Lean on Resources**: Keeps active memory usage tiny (~20–25 MB), freeing up your RAM for your local workflow.
- **Ready to Use (No Java Required)**: Available as self-contained **`.deb`** (Debian/Ubuntu), **`.rpm`** (Fedora/RHEL), and portable **`.tar.gz`** packages with an icon in your app launcher. You do not need to install Java.

---

## Prerequisites

JW365 uses the open-source **FreeRDP 3** engine to render the remote Windows session:

```bash
# Ubuntu / Debian
sudo apt update && sudo apt install freerdp3-sdl freerdp3-x11

# Fedora / RHEL
sudo dnf install freerdp
```

---

## Quick Install

Download the latest release from the **[Releases Page](https://github.com/alaurie/JW365/releases)**:

### Ubuntu / Debian (`.deb`)
```bash
sudo apt install ./jw365_*_amd64.deb
```
JW365 will appear in your application launcher menu.

### Fedora / RHEL (`.rpm`)
```bash
sudo dnf install ./jw365-*.x86_64.rpm
```

### Any Linux Distribution (`.tar.gz` portable)
```bash
tar -xzf jw365-*-linux-x64.tar.gz
cd jw365

# (Optional) Add JW365 to your desktop app launcher:
./install-desktop.sh

# Run directly:
./bin/jw365
```

---

## Using JW365

1. **Launch the App**: Open **JW365** from your application menu or terminal (`jw365`).
2. **Sign In**: Click **"Sign in with Microsoft"** and enter your work or school account credentials.
3. **Connect**: Your assigned Cloud PCs will appear on screen. Click **"Connect"** (or double-click the tile) to launch your session.
4. **Right-Click Context Menu**: Right-click any Cloud PC tile to:
   - Connect in **Fullscreen** or **Windowed** mode.
   - Connect in **Multi-Monitor** mode.
   - **Restart Session** with one click.
   - View recent connection logs for troubleshooting.

---

## Under the Hood (Technical Architecture)

For developers, sysadmins, and technical users:

- **Runtime**: Built with **Java 25+** and **OpenJFX 25**, bundled via `jlink` minimal runtime (~100 MB total package).
- **Concurrency**: Project Loom **Virtual Threads** handle all background network discovery, token renewal, and FreeRDP output streaming.
- **Authentication Handshake**: Embedded WebEngine completes Microsoft Entra ID OAuth 2.0 PKCE (`a85cf173-4192-42f8-81fa-777a763e6e2c`) with direct `nativeclient?code=` interception.
- **Session Auth Piping**: Real-time output watcher intercepts FreeRDP's `/sec:aad` token prompt and feeds authorization codes directly to FreeRDP's stdin over a native Linux pseudo-terminal (PTY).
- **Token Security at Rest**: Refresh tokens are encrypted with machine-bound **AES-256-GCM** (keyed to `/etc/machine-id` + user identity via 100,000 rounds of PBKDF2-HMAC-SHA256) with POSIX `0600` file permissions (`~/.local/share/jw365/token-cache.enc`).
- **Memory Tuning**: Uses Serial GC (`-XX:+UseSerialGC`), strict heap bounds (`-Xms24m -Xmx192m`), and aggressive memory uncommitting (`-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20`) to keep resident memory ~20–25 MB.
- **Wayland Window Management**: Sets `StartupWMClass=org.alaurie.jw365.gui.Jw365App` and uses native Wayland environment hints (`SDL_VIDEODRIVER=wayland,x11`).

---

## Building from Source

```bash
# Run the 30-test unit suite
./gradlew test

# Run in development mode
./jw365
# or
./gradlew run

# Build distribution packages (outputs to build/distributions/)
./gradlew deb       # Native .deb
./gradlew rpm       # Native .rpm (requires rpmbuild)
./gradlew portable  # Portable .tar.gz
./gradlew packageAll
```

---

## License

This project is licensed under the Apache License 2.0.
