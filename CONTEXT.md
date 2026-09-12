# JW365 Domain Model

Linux desktop client for Windows 365 Cloud PCs and Azure Virtual Desktop workspaces.

## Language

**ClientConfig**:
Persisted user preferences controlling FreeRDP engine selection, peripheral redirection, display scaling, and background refresh intervals.
_Avoid_: UserSettings, AppPreferences, Options

**SessionTarget**:
The specific Cloud PC resource and display mode selected for an RDP session launch.
_Avoid_: RdpSessionConfig, LaunchTarget, ConnectionRequest

**DisplayMode**:
The screen presentation mode requested for a remote session (Default, Fullscreen, Windowed, Multi-Monitor).
_Avoid_: WindowMode, ScreenMode, ViewMode
