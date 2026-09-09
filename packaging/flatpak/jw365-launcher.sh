#!/bin/sh
set -eu
exec /app/runtime/bin/java \
  -cp "/app/lib/*" \
  org.alaurie.jw365.gui.Jw365Main "$@"
