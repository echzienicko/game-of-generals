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
# How long a player gets per move. Unset means the server's own default (60), which is what
# the long game harness wants. CLOCK_ONLY=1 asks for the short-clock harness instead, which
# only exists to watch a turn fall due, so it brings its own three seconds with it.
if [ "${CLOCK_ONLY:-0}" = "1" ]; then
  SCRIPT=${SCRIPT_OVERRIDE:-$HERE/clock-e2e.mjs}
  TURN_SECONDS=${TURN_SECONDS:-5}
fi
# The against-the-computer harness is a third thing rather than a branch of the first: the
# full game harness needs two people, and this needs one window and a button, so it shares
# nothing with either of the others but the boot and teardown below. No short clock here —
# the clock in a game against the computer is the same clock as in any other game, and
# clock-e2e.mjs is the harness that waits one out.
if [ "${BOT_ONLY:-0}" = "1" ]; then
  SCRIPT=${SCRIPT_OVERRIDE:-$HERE/bot-e2e.mjs}
fi
CLOCK_ARG=()
if [ -n "${TURN_SECONDS:-}" ]; then
  CLOCK_ARG=("--generals.turn-seconds=$TURN_SECONDS")
  echo "turn clock: ${TURN_SECONDS}s per move"
fi

# The pids listening on a port, or nothing.
port_pids() {
  ss -ltnpH "sport = :$1" 2>/dev/null | grep -o 'pid=[0-9]*' | cut -d= -f2 | sort -u
}

pkill -9 -f '[g]enerals-0.0.1-SNAPSHOT' >/dev/null 2>&1
# Match the real command line (`node .../node_modules/.bin/vite`): the port is never in it,
# and npm's child outlives a kill on npm, so the pattern has to be the dev server itself.
pkill -9 -f '[n]ode_modules/.bin/vite' >/dev/null 2>&1
# Those two patterns cannot see a server started any other way, and a server that is already
# up is the worst thing that can happen to this script: it answers /api/meta perfectly, so
# the readiness check passes and every check below is run against yesterday's build. A
# `mvn spring-boot:run` left over from an earlier session is exactly that, and its command
# line is a maven classpath rather than the jar's name. So take the ports.
for port in 8080 5173; do
  held=$(port_pids "$port" | tr '\n' ' ')
  if [ -n "${held// /}" ]; then
    echo "=== port $port was already held by: $held (killing) ==="
    # shellcheck disable=SC2086
    kill -9 $held >/dev/null 2>&1
  fi
done
rm -f "$LEDGER"
sleep 2

: > "$OUT"
{
  echo "=== boot ==="
  setsid "$JAVA" -jar "$JAR" \
    "--generals.leaderboard.path=$LEDGER" "${CLOCK_ARG[@]}" \
    > /tmp/opencode/boot.log 2>&1 < /dev/null &
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
  # Confirm the thing answering is the thing this run started. A readiness check that cannot
  # tell the difference between its own server and a stranger's is not a readiness check.
  on8080=$(port_pids 8080 | tr '\n' ' ')
  if [ "${on8080// /}" != "$BPID" ]; then
    echo "=== :8080 is answered by [$on8080], not by the jar this run started ($BPID) ==="
    backend=0
  fi
  for pid in $(port_pids 5173); do
    if ! tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -q '[n]ode_modules/.bin/vite'; then
      echo "=== :5173 is held by pid $pid, which is not a dev server ==="
      vite=0
    fi
  done
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
      TURN_SECONDS="${TURN_SECONDS:-}" timeout 900 node "$SCRIPT" 2>&1
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
