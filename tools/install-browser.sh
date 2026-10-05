#!/usr/bin/env bash
# Sets up a headless Chromium for browser-e2e.mjs on a machine with no sudo, which is
# what playwright's own `install --with-deps` needs. Idempotent; safe to re-run.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${WORK:-/tmp/opencode/browser}
LIBS="$WORK/libs"

mkdir -p "$WORK"
cd "$WORK"

if [ ! -d node_modules/playwright ]; then
  npm init -y >/dev/null
  npm i playwright@1.49.1
fi
npx playwright install chromium || true

# The browser binary ships; only a handful of system libraries are missing.
if ldd "$HOME"/.cache/ms-playwright/chromium_headless_shell-*/chrome-linux/headless_shell 2>/dev/null \
   | grep -q 'not found'; then
  echo "== fetching the missing shared libraries =="
  mkdir -p "$LIBS"
  for pkg in libasound2t64 libnspr4 libnss3; do
    apt-get download "$pkg" >/dev/null 2>&1 || echo "could not download $pkg"
  done
  for deb in ./*.deb; do dpkg-deb -x "$deb" "$LIBS"; done
  rm -f ./*.deb
fi

echo "== verifying =="
LD_LIBRARY_PATH="$LIBS/usr/lib/x86_64-linux-gnu:$LIBS/lib/x86_64-linux-gnu" node -e '
  const { chromium } = require("playwright");
  chromium.launch({ args: ["--no-sandbox"] }).then(async (b) => {
    const p = await b.newPage();
    await p.setContent("<h1>ok</h1>");
    console.log("chromium works:", await p.textContent("h1"));
    await b.close();
  });'
echo
echo "Run the harness with: $HERE/browser-run.sh"
