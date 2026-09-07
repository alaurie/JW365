#!/bin/sh
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
APPS="$HOME/.local/share/applications"
ICONS="$HOME/.local/share/icons/hicolor/256x256/apps"

mkdir -p "$APPS" "$ICONS"
if [ -f "$DIR/jw365.png" ]; then
    cp -f "$DIR/jw365.png" "$ICONS/jw365.png"
fi

cat << EOF > "$APPS/jw365.desktop"
[Desktop Entry]
Version=1.0
Type=Application
Name=JW365
Comment=Modern Linux Client for Windows 365 and Azure Virtual Desktop
Exec=$DIR/bin/jw365
Icon=$ICONS/jw365.png
Terminal=false
Categories=Network;RemoteAccess;
StartupWMClass=org.alaurie.jw365.gui.Jw365App
EOF

if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$APPS" 2>/dev/null || true
fi

echo "JW365 desktop launcher successfully installed to $APPS/jw365.desktop"
