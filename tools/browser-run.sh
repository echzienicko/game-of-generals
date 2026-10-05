#!/usr/bin/env bash
# Boots the backend jar and the Vite dev server, runs a Node script against them,
# then tears both down. One invocation, no background jobs left behind.
set -u
ROOT=/home/nick/projects/game/generals
JAR="$ROOT/backend/target/generals-0.0.1-SNAPSHOT.jar"
JAVA="$HOME/.local/jdk/bin/java"
HERE=$(cd "$(dirname "$0")" && pwd)
# Chromium and the shared libraries it needs are not installed system-wide (no sudo on
# this box), so they live outside the repo. See install-browser.sh to recreate them.
BROWSER_LIBS=${BROWSER_LIBS:-/tmp/opencode/browser/libs}
OUT=${OUT:-/tmp/opencode/browser.out}
# The win table is a real file, so a run must not inherit the last run's scores: the
# harness asserts one win, and a leftover ledger would read as two.
LEDGER=/tmp/opencode/harness-leaderboard.json
SCRIPT=${SCRIPT:-$HERE/browser-e2e.mjs}

pkill -9 -f '[g]enerals-0.0.1-SNAPSHOT' >/dev/null 2>&1
# Match the real command line (`node .../node_modules/.bin/vite`): the port is never in it,
# and npm's child outlives a kill on npm, so the pattern has to be the dev server itself.
pkill -9 -f '[n]ode_modules/.bin/vite' >/dev/null 2>&1
rm -f "$LEDGER"
sleep 2

: > "$OUT"
{
  echo "=== boot ==="
  setsid "$JAVA" -jar "$JAR" \
    "--generals.leaderboard.path=$LEDGER" > /tmp/opencode/boot.log 2>&1 < /dev/null &
  BPID=$!
  setsid npm --prefix "$ROOT/frontend" run dev > /tmp/opencode/vite.log 2>&1 < /dev/null &
  VPID=$!

  backend=0
  vite=0
  for _ in $(seq 1 90); do
    curl -sf http://localhost:8080/api/meta >/dev/null 2>&1 && backend=1
    curl -sf http://localhost:5173/ >/dev/null 2>&1 && vite=1
    [ "$backend" = "1" ] && [ "$vite" = "1" ] && break
    sleep 1
  done
  echo "backend ready: $backend  vite ready: $vite"
  if [ "$backend" != "1" ]; then
    echo "--- boot.log ---"; tail -n 20 /tmp/opencode/boot.log
  fi
  if [ "$vite" != "1" ]; then
    echo "--- vite.log ---"; tail -n 20 /tmp/opencode/vite.log
  fi
  if [ "$backend" = "1" ] && [ "$vite" = "1" ]; then
    echo "=== check: $(basename "$SCRIPT") ==="
    cd "$HERE" || exit 1
    LD_LIBRARY_PATH="$BROWSER_LIBS/usr/lib/x86_64-linux-gnu:$BROWSER_LIBS/lib/x86_64-linux-gnu" \
      timeout 900 node "$SCRIPT" 2>&1
    echo "=== node exit: $? ==="
  fi

  kill -9 "$BPID" "$VPID" 2>/dev/null
  sleep 1
  pkill -9 -f '[g]enerals-0.0.1-SNAPSHOT' >/dev/null 2>&1
  pkill -9 -f '[n]ode_modules/.bin/vite' >/dev/null 2>&1
  sleep 1
  echo "=== leftover jvms: $(pgrep -f '[g]enerals-0.0.1-SNAPSHOT' | wc -l), leftover vite: $(pgrep -f '[n]ode_modules/.bin/vite' | wc -l) ==="
} >> "$OUT" 2>&1

cat "$OUT"
