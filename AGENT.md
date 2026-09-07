# AGENT.md — Autonomous Agent Operating Manual for JW365

This document is the authoritative engineering manual and architectural contract for AI coding agents and automated contributors working on the **JW365** repository.

---

## 1. System Identity & Mission

- **Project**: **JW365** (Java Windows 365 & AVD Client for Linux)
- **Target OS**: Linux (Wayland & X11 / XWayland) on modern distributions (Debian, Ubuntu, Fedora, Arch, RHEL, openSUSE).
- **Core Technology Stack**:
  - **Java**: Java 25+ (Toolchain `JavaLanguageVersion.of(25)`).
  - **GUI Toolkit**: OpenJFX 25 (`javafx.controls`, `javafx.graphics`, `javafx.web`).
  - **JSON & SerDe**: Jackson Databind 2.18+ with JSR310 date/time support.
  - **RDP Backend**: FreeRDP 3.x (`sdl-freerdp3`, `xfreerdp3`, `wlfreerdp3`).
  - **Build System**: Gradle 9+ (Kotlin DSL: `build.gradle.kts`, `settings.gradle.kts`).
  - **Package Namespace**: `org.alaurie.jw365.*`
  - **GitHub Repository**: `https://github.com/alaurie/JW365`

---

## 2. Inviolable Technical Invariants (Regression Traps)

Future agents **MUST NOT** violate these hard-won protocol, operating system, and security invariants:

### 2.1. FreeRDP Pseudo-Terminal (PTY) Requirement
- **Context**: When FreeRDP 3.x connects using `/sec:aad /gateway:type:arm`, its terminal input reader invokes `set_terminal_nonblock(fileno(stdin))`, which calls `tcgetattr()`.
- **The Trap**: If FreeRDP is spawned via standard `java.lang.ProcessBuilder` with raw pipes, `tcgetattr()` fails with `ENOTTY (Inappropriate ioctl for device)`, causing FreeRDP to abort or terminate abruptly.
- **The Rule**: FreeRDP **MUST** be launched via `script -q -c "<command>" /dev/null` on Linux to allocate a valid pseudo-terminal (PTY) for `stdin` and `stdout`.
- **Anchor**: `org.alaurie.jw365.rdp.RdpProcessSupervisor`

### 2.2. Real-Time OAuth Stdin Piping
- **Context**: Because Linux does not run Microsoft's closed-source Windows identity broker daemon (`com.microsoft.identity.broker1`), FreeRDP falls back to outputting:
  ```text
  Browse to: https://login.microsoftonline.com/...
  Paste redirect URL here:
  ```
  and blocks waiting on standard input.
- **The Rule**: `RdpProcessSupervisor` watches stdout in a virtual thread. When `line.contains("Browse to: ")` is detected, it emits `SessionEvent.AuthRequired`. The UI opens `SessionAuthDialog` (reusing the user's active WebEngine cookies), catches the `nativeclient?code=...` redirect in real time, and immediately writes `redirectUrl + "\n"` into FreeRDP's `stdin` via `session.writeInput()`.
- **Never**: Do not prompt the user to manually copy/paste redirect URLs from a terminal.

### 2.3. Microsoft AVD Client ID & Redirect URI
- **Constants**:
  - `CLIENT_ID`: `"a85cf173-4192-42f8-81fa-777a763e6e2c"` (Official Microsoft Remote Desktop / AVD public client ID).
  - `REDIRECT_URI`: `"https://login.microsoftonline.com/common/oauth2/nativeclient"`
- **The Trap**: Microsoft Entra ID strictly validates the redirect URI registered for this application. Attempting to use `http://localhost:<port>` with this client ID fails with error `AADSTS50011`.
- **The Rule**: Always use `https://login.microsoftonline.com/common/oauth2/nativeclient`. Do not attempt localhost loopback with this client ID.
- **Anchor**: `org.alaurie.jw365.auth.OAuthClient`

### 2.4. UTF-8 Byte Order Mark (BOM) in Microsoft XML Feeds
- **Context**: Microsoft IIS/ASP.NET AVD endpoints prepend a UTF-8 BOM (`\xef\xbb\xbf` / `\uFEFF`) to both discovery and workspace XML response bodies.
- **The Trap**: Java's standard `DocumentBuilder.parse()` fails with `SAXParseException: Content is not allowed in prolog.` if any characters precede the opening `<?xml` declaration.
- **The Rule**: `WorkspaceFeedParser.parseSecurely()` **MUST** strip leading BOM markers or any pre-prolog characters (`xml.indexOf('<')`).
- **Anchor**: `org.alaurie.jw365.feed.WorkspaceFeedParser`

### 2.5. Prohibition of Legacy AWT `SystemTray` on Wayland
- **Context**: Java's `java.awt.SystemTray` relies on the legacy X11 XEmbed protocol (`_NET_SYSTEM_TRAY`), which GNOME officially deprecated in GNOME 3.26 and Wayland does not support natively.
- **The Trap**: On Wayland, AWT tray icons suffer from severe scaling distortion, and AWT popup menu events are dropped (right-clicking "Quit" does nothing).
- **The Rule**: **Do not re-introduce `java.awt.SystemTray`**. Desktop state management belongs in the GNOME dock/dash, window manager minimization, and standard window controls.
- **Anchor**: `org.alaurie.jw365.gui.Jw365App`

### 2.6. Desktop & Window Manager Class Alignment
- **Context**: The Glass GTK engine in JavaFX assigns the window manager class to the fully-qualified class name of the application: `org.alaurie.jw365.gui.Jw365App`.
- **The Trap**: If a `.desktop` file sets `StartupWMClass=jw365`, GNOME Shell fails to match running windows to the launcher, causing duplicate dock icons and a fallback generic system gear/cog icon.
- **The Rule**: All desktop files (`template.desktop`, `jw365.desktop`, `install-desktop.sh`) **MUST** declare:
  ```ini
  StartupWMClass=org.alaurie.jw365.gui.Jw365App
  ```
- **The Rule on Runtime Files**: Do not write `.desktop` files dynamically at runtime; package managers (`.deb`, `.rpm`) and `./install-desktop.sh` handle static registration without duplicate race conditions.

### 2.7. Token Security at Rest
- **The Rule**: Tokens **MUST NEVER** be stored in plaintext.
- **Implementation**:
  1. `TokenStore` encrypts token payloads using **machine-bound AES-256-GCM** via `MachineBoundCrypto`.
  2. The 256-bit encryption key is derived from `/etc/machine-id` (or `/var/lib/dbus/machine-id`) + user login name using **100,000 rounds of PBKDF2 with HMAC-SHA256**.
  3. Every write uses a unique random 16-byte salt and 12-byte initialization vector (IV / nonce).
  4. The encrypted file (`~/.local/share/jw365/token-cache.enc`) is locked to POSIX `0600` (`rw-------`).
  5. Any existing legacy plaintext `token-cache.json` is automatically migrated and shredded on first load.
- **Anchor**: `org.alaurie.jw365.auth.MachineBoundCrypto`, `org.alaurie.jw365.auth.TokenStore`

### 2.8. Strict Desktop Memory Footprint
- **Context**: The JVM server default allocates up to 25% of system RAM (`-XX:MaxRAMPercentage=25.0`), which balloons to 8–16 GB on a 32–64 GB workstation.
- **The Rule**: The application launcher and native packages **MUST** enforce these JVM arguments:
  ```text
  -Xms24m
  -Xmx192m
  -XX:ReservedCodeCacheSize=64m
  -XX:CICompilerCount=2
  -XX:+UseSerialGC
  -XX:MinHeapFreeRatio=10
  -XX:MaxHeapFreeRatio=20
  ```
- **Active Heap**: Must remain around **~20–25 MB**. Unused memory must be aggressively uncommitted back to the Linux OS via `MADV_DONTNEED`.

### 2.9. UI Card Caching & Event Listener Cleanup
- **The Trap**: Rebuilding `ResourceCard` instances on every keystroke during search filtering creates orphaned `MapChangeListener` references on `AppState.getSessionStatuses()`, resulting in a memory leak and UI redraw tearing.
- **The Rule**: `MainView` must reuse cards via `cardCache` and invoke `card.cleanup()` when resources are removed from the workspace.
- **Anchor**: `org.alaurie.jw365.gui.view.MainView`, `org.alaurie.jw365.gui.view.ResourceCard`


### 2.10. FreeRDP SDL Client Hotkey Interception Trap
- **Context**: FreeRDP's SDL client (`sdl-freerdp3`) defaults to hardcoding `KMOD_RSHIFT` (Right Shift) as its hotkey modifier mask:
  - `Right Shift + D`: `SDL_Disconnect` (closes the remote window!)
  - `Right Shift + M`: `SDL_Minimize`
  - `Right Shift + R`: `SDL_Resizeable`
  - `Right Shift + G`: `SDL_Grab`
- **The Trap**: When typing uppercase letters in Windows (like a capital 'D' or 'M') with Right Shift, FreeRDP intercepts the keypress as a local hotkey command and immediately terminates or minimizes the remote session.
- **The Rule**: `RdpProcessSupervisor` **MUST** call `ensureSdlConfig()` before launching `sdl-freerdp3` to create `~/.config/freerdp/sdl-freerdp.json` mapping `SDL_KeyModMask` to `["KMOD_RCTRL"]` and remapping `SDL_Disconnect` to `["SDL_SCANCODE_F12"]`. Normal Shift typing must never be intercepted as a session-closing hotkey.
- **Anchor**: `org.alaurie.jw365.rdp.RdpProcessSupervisor.ensureSdlConfig`

### 2.11. Mandatory Release Changelog & Verification Checksums
- **Context**: Generic automated GitHub release notes often output empty bodies or a single uninformative link when commits are pushed directly without merged pull requests.
- **The Rule**: All releases **MUST** generate a detailed, structured Markdown changelog using `scripts/generate-changelog.py`:
  1. Categorizes commits between tags into **Features & Improvements**, **Bug Fixes & Stability**, **Documentation**, and **Other Changes**.
  2. Embeds the full SHA-256 verification checksums for all distribution packages (`.deb`, `.rpm`, `.tar.gz`).
  3. The project root **MUST** maintain `CHANGELOG.md` following [Keep a Changelog](https://keepachangelog.com/) standards.
- **Anchor**: `scripts/generate-changelog.py`, `.github/workflows/release.yml`, `CHANGELOG.md`

### 2.12. Audio, Media, and Peripheral Redirection Posture
- **Context**: Modern Linux distributions (Debian, Ubuntu, Fedora, Arch) default to PipeWire with `pipewire-pulse`.
- **The Rule**:
  - **Audio Output**: `/sound:sys:pulse,rate:48000,channel:2,quality:high` (matches PipeWire's native 48kHz clock, eliminating buffer resampling latency and distortion).
  - **Microphone**: `/microphone:sys:pulse,rate:48000` (low-latency audio input for Teams/calls).
  - **Environment**: FreeRDP process **MUST** receive `PULSE_SERVER=unix:${XDG_RUNTIME_DIR}/pulse/native` to connect directly to PipeWire without socket search delays.
  - **Peripherals**: `/usb:auto` (webcams and USB devices) and `/smartcard` (YubiKeys / FIDO2 security keys) are enabled by default.
  - **Shared Folder Prohibition**: Drive redirection (`/drive:...`) is explicitly removed and prohibited per user requirement.
- **Anchor**: `org.alaurie.jw365.rdp.RdpProcessSupervisor.buildCommandLine`
---

## 3. Project Structure & Code Map

```text
JW365/
├── src/
│   ├── main/
│   │   ├── java/org/alaurie/jw365/
│   │   │   ├── auth/                     # Authentication & Crypto Engine
│   │   │   │   ├── AuthResult.java       # Sealed result: Success, Failure, DeviceCodeRequired
│   │   │   │   ├── BrowserInfo.java      # Detected browser descriptor record
│   │   │   │   ├── BrowserLocator.java   # Discovers Edge, Chrome, Firefox, xdg-open
│   │   │   │   ├── DeviceCodeResponse.java
│   │   │   │   ├── JwtClaimsParser.java  # Native zero-dependency JWT ID-token claim extractor
│   │   │   │   ├── LoopbackAuthReceiver.java # Ephemeral HTTP server for OAuth callbacks
│   │   │   │   ├── MachineBoundCrypto.java   # AES-256-GCM + PBKDF2 machine-id crypto
│   │   │   │   ├── OAuthClient.java      # HTTP/2 client for Entra ID OAuth 2.0 PKCE
│   │   │   │   ├── PersistentCookieManager.java # Disk-backed cookies for JavaFX WebEngine SSO
│   │   │   │   ├── PkceChallenge.java    # RFC 7636 PKCE S256 code challenge record
│   │   │   │   ├── TokenResponse.java    # OAuth token record with expiry calculation
│   │   │   │   ├── TokenStore.java       # Encrypted token storage & keyring integration
│   │   │   │   └── UserClaims.java       # Extracted user profile claims record
│   │   │   ├── feed/                     # Workspace Feed & Discovery Engine
│   │   │   │   ├── ResourceType.java     # DESKTOP, REMOTE_APP, UNKNOWN enum
│   │   │   │   ├── TenantFeed.java       # Tenant feed URL descriptor record
│   │   │   │   ├── Workspace.java        # Workspace container record
│   │   │   │   ├── WorkspaceFeedClient.java # HTTP/2 client for AVD/W365 ARM discovery
│   │   │   │   ├── WorkspaceFeedParser.java # Secure XML parser with BOM stripping
│   │   │   │   └── WorkspaceResource.java   # Cloud PC / App item record
│   │   │   ├── rdp/                      # FreeRDP Process Management
│   │   │   │   ├── ActiveSession.java    # Process handle with stdin piping & lifecycle
│   │   │   │   ├── FreeRdpFlavor.java    # Binary flavor (SDL, X11, Wayland, Flatpak)
│   │   │   │   ├── FreeRdpInfo.java      # Detected executable descriptor record
│   │   │   │   ├── FreeRdpLocator.java   # Finds sdl-freerdp3, xfreerdp3, wlfreerdp3
│   │   │   │   ├── RdpProcessSupervisor.java # Spawns PTY, monitors logs, intercepts OAuth
│   │   │   │   ├── RdpSessionConfig.java # Launch configuration record
│   │   │   │   ├── SessionEvent.java     # Sealed hierarchy: Started, StatusChanged, OutputLine, Exited, AuthRequired
│   │   │   │   ├── SessionListener.java  # Event callback interface
│   │   │   │   └── SessionStatus.java    # IDLE, STARTING, CONNECTING, CONNECTED, DISCONNECTED, FAILED
│   │   │   ├── config/                   # Configuration & Caching
│   │   │   │   ├── ClientConfig.java     # Immutable user preferences record
│   │   │   │   ├── ConfigManager.java    # Atomic JSON config reader/writer
│   │   │   │   ├── WorkspaceCache.java   # Caches workspace metadata and PNG icons
│   │   │   │   └── XdgPaths.java         # Linux XDG Base Directory resolution & log pruning
│   │   │   └── gui/                      # Desktop User Interface
│   │   │       ├── Jw365App.java         # JavaFX Application entrypoint & shutdown hook
│   │   │       ├── Jw365Main.java        # Bootstrap launcher with native GTK properties
│   │   │       ├── state/
│   │   │       │   └── AppState.java     # Central reactive controller & virtual thread runner
│   │   │       └── view/
│   │   │           ├── AuthDialog.java   # Embedded WebEngine OAuth modal dialog
│   │   │           ├── MainView.java     # Header, search filter, workspace grid, status bar
│   │   │           ├── ResourceCard.java # Visual tile with live status badge & action button
│   │   │           ├── SessionAuthDialog.java # Real-time FreeRDP OAuth redirect capture modal
│   │   │           ├── SettingsDialog.java    # Preferences configuration dialog
│   │   │           └── SignInView.java        # Microsoft-branded sign-in landing screen
│   │   └── resources/
│   │       └── org/alaurie/jw365/gui/
│   │           ├── icon.png              # 256x256 master application icon
│   │           ├── icons/                # Multi-resolution hicolor icon set (16, 32, 48, 64, 128, 256)
│   │           └── styles.css            # Modern Fluent/Azure dark theme CSS
│   ├── package/resources/                # jpackage templates
│   │   ├── template.desktop              # Custom desktop template with StartupWMClass
│   │   ├── jw365.desktop                 # Package-named desktop template
│   │   └── install-desktop.sh            # One-click desktop registrar for portable tarball
│   └── test/java/org/alaurie/jw365/      # Unit Test Suite (30 tests)
│       ├── auth/                         # OAuthClientTest, OAuthFlowTest, BrowserLocatorTest,
│       │                                 # MachineBoundCryptoTest, PersistentCookieManagerTest
│       ├── feed/                         # WorkspaceFeedClientTest, WorkspaceFeedParserTest
│       ├── rdp/                          # FreeRdpLauncherTest, RdpProcessSupervisorLifecycleTest
│       ├── config/                       # XdgAndConfigTest, XdgLogPruningTest
│       └── gui/state/                    # AppStateTest
├── .github/workflows/
│   ├── ci.yml                            # Automated CI on push/PR to main (Node 24 actions)
│   └── release.yml                       # Automated release on tag push (v*) building .deb, .rpm, .tar.gz
├── build.gradle.kts                      # Root Gradle build script with dynamic versioning & jpackage
├── settings.gradle.kts                   # Project name specification
├── jw365                                 # Development execution script
├── README.md                             # User-facing documentation
└── AGENT.md                              # This file
```

---

## 4. Coding Conventions & Best Practices

1. **Records Everywhere**:
   - Use immutable Java `record`s for all data transfer objects, descriptors, configs, and payloads.
   - Never write boilerplate Java Bean getters/setters or mutable state containers.
2. **Sealed Types & Pattern Matching**:
   - Model operations with discrete outcomes using `sealed interface` and record subtypes.
   - Use pattern matching `switch` with exhaustiveness checking (avoid runtime `else` fallback traps).
3. **Project Loom Concurrency**:
   - Network calls, stream reading, log writing, and background tasks **MUST** run on Virtual Threads (`Thread.ofVirtual().start(...)` or `Executors.newVirtualThreadPerTaskExecutor()`).
   - Never block JavaFX Application Thread. Use `AppState.runOnFxThread()` to update observable properties.
4. **Zero-Copy Native Security**:
   - Use Java's native `javax.crypto` providers (`AES/GCM/NoPadding`, `PBKDF2WithHmacSHA256`). Do not introduce external cryptography libraries like BouncyCastle or JJWT.
5. **No Regressions on Wayland / FreeRDP**:
   - Always run the test suite before submitting changes: `./gradlew check`.
   - Never remove `+dynamic-resolution`, `+clipboard`, `script -q -c`, or `StartupWMClass`.

---

## 5. Verification & Packaging Runbook

### 5.1. Run Unit Tests
```bash
./gradlew test
```
All 30 unit tests should pass in ~2 seconds with zero failures.

### 5.2. Run Application Locally
```bash
./jw365
# or
./gradlew run
```

### 5.3. Build Packages Locally
```bash
# Build native Debian package (.deb)
./gradlew deb
# Output: build/distributions/jw365_1.0.0_amd64.deb

# Build native RPM package (.rpm) [requires rpmbuild installed]
./gradlew rpm
# Output: build/distributions/jw365-1.0.0-1.x86_64.rpm

# Build portable standalone tarball (.tar.gz)
./gradlew portable
# Output: build/distributions/jw365-1.0.0-linux-x64.tar.gz

# Build all distribution artifacts
./gradlew packageAll
```

### 5.4. Dynamic Versioning
To build packages with a specific release version (e.g. `1.2.0`), pass `-PappVersion`:
```bash
./gradlew deb -PappVersion=1.2.0
# Produces: build/distributions/jw365_1.2.0_amd64.deb
```

### 5.5. Automated GitHub Release
To publish an official multi-package release on GitHub:
```bash
git tag vX.Y.Z
git push origin vX.Y.Z
```
GitHub Actions will execute `.github/workflows/release.yml`, run tests, build the `.deb`, `.rpm`, and `.tar.gz`, generate SHA-256 checksums, and publish the GitHub Release automatically.
