#!/bin/sh
set -eu
exec /app/runtime/bin/java \
  --enable-native-access=ALL-UNNAMED \
  -Xms24m -Xmx192m -XX:ReservedCodeCacheSize=64m -XX:CICompilerCount=2 \
  -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20 \
  -cp "/app/lib/*" \
  org.alaurie.jw365.gui.Jw365Main "$@"
