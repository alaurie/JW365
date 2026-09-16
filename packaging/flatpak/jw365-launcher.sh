#!/bin/sh
set -eu

# Force X11 backend for GDK to ensure reliable JavaFX/Glass GTK windowing and event dispatching under XWayland/GNOME
export GDK_BACKEND=x11

exec /app/runtime/bin/java \
  --enable-native-access=ALL-UNNAMED \
  -Xms24m -Xmx192m -XX:ReservedCodeCacheSize=64m -XX:CICompilerCount=2 \
  -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20 \
  -XX:-UsePerfData \
  -cp "/app/lib/*" \
  org.alaurie.jw365.gui.Jw365Main "$@"
