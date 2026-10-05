# Running this app — operational notes

Everything needed to start, test, and play Game of the Generals on this machine. For *why*
the code is shaped this way, see [`AGENTS.md`](AGENTS.md); for what's still to do, see
[`TODO.md`](TODO.md).

---

## 1. Toolchain (already installed, no sudo)

| Tool | Where | Version |
| --- | --- | --- |
| JDK | `~/.local/jdk` | Temurin 17.0.20.1 |
| Maven | `~/.local/maven` | 3.9.16 |
| Node / npm | system | 22.22.1 / 9.2.0 |

`JAVA_HOME`, `MAVEN_HOME` and both `bin` directories are exported at the top of `~/.bashrc`,
**above** its non-interactive early-return. That means a plain interactive shell finds `java`
and `mvn`, but a non-interactive one (an agent, a cron job, `bash -c`) does not. In those:

```sh
export JAVA_HOME=$HOME/.local/jdk
$HOME/.local/maven/bin/mvn -o clean verify      # note the -o: no network is available
```

Always pass `-o` to Maven. It otherwise stalls trying to reach a repository it cannot reach.

---

## 2. The two ways to run

### A. Development — hot reload

Two processes, two terminals, backend first.

```sh
cd backend  && mvn spring-boot:run          # http://localhost:8080
cd frontend && npm run dev                  # http://localhost:5173  (proxies /api and /ws)
```

The Vite dev server proxies `/api` and `/ws` to `localhost:8080`, so the browser only ever
sees one origin and CORS is never involved. Open **http://localhost:5173**.

### B. Packaged — one jar, one origin (use this for the LAN)

```sh
cd frontend && npm run build:jar            # tsc + vite, then rsync dist -> backend/src/main/resources/static
cd backend  && mvn -o clean package
java -jar target/generals-0.0.1-SNAPSHOT.jar
```

Now **http://localhost:8080 serves the entire game**: the React SPA, `/api`, the SockJS
endpoint, and the deep links `/lobby`, `/game/{id}`, `/vs-bot` (forwarded to `index.html` by
`SpaController`).

- `npm run build:jar` exists so the jar never ships a stale frontend. `npm run build` alone
  leaves `backend/src/main/resources/static/` untouched, and that directory is what actually
  gets packaged into the jar.
- `mvn spring-boot:run` also serves the packaged SPA, so for testing a UI change without
  rebuilding, `npm run build:jar` then `mvn spring-boot:run` is enough.

| Port | What |
| --- | --- |
| 8080 | backend + packaged SPA |
| 5173 | Vite dev server only |

---

## 3. Gates — run these before calling any change done

```sh
# backend: 166 tests, builds the jar
cd backend  && mvn -o clean verify

# frontend: 73 tests, type-checks (tests included), lints
cd frontend && npm run build && npm test && npm run lint
```

- `npm run build` runs `tsc -b` **then** `vite build`, so test files are type-checked too.
- `npm run lint` (`oxlint`) reports the expected `only-export-components` warning(s) in
  `src/state/GameContext.tsx`. That is expected, not a regression.
- Unit and integration tests are **not sufficient** for frontend work — jsdom never loads
  real deps and cannot race two connections. Two blank-page/stuck-screen bugs got through
  125 green tests. Before trusting a frontend change, run the real browser harness (68
  checks, two real browsers playing each other):

```sh
tools/install-browser.sh    # once: Chromium + libs into /tmp/opencode/browser, no sudo
tools/browser-run.sh        # boots jar + dev server, plays a full game in two windows, tears down
```

`browser-run.sh` deletes its own ledger first (a run must not inherit the last run's
scores) and kills both servers on the way out, reporting what is left. If a run is killed
mid-flight, clear the port yourself:

```sh
pkill -9 -f '[g]enerals-0.0.1-SNAPSHOT'; pkill -9 -f '[n]ode_modules/.bin/vite'
```

---

## 4. Playing on the LAN (phone, second laptop)

### The blocker is WSL2, not the app

This is Windows 10 22H2 (build 19045) with WSL 3.0.1. The VM is NAT'd at `172.24.223.0` —
an address on a private virtual segment that **no other device can route to**. Only Windows
itself reaches it, which is why `localhost:8080` works there. Mirrored networking would fix
this natively, but it requires Windows 11 22H2+; on Windows 10 WSL just prints
`Mirrored networking mode is not supported, falling back to NAT` and continues.

### The fix: forward Windows' own LAN address into WSL

From an **elevated** PowerShell on Windows (right-click → *Run as administrator*):

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\expose-lan.ps1
```

It reads the current WSL address itself, installs a `netsh interface portproxy` relay for
8080 (and 5173 for the dev server), adds a `LocalSubnet`-scoped firewall rule, and prints the
URLs. On this machine that is **http://192.168.254.53:8080**.

Then, on each device: open the URL → *Create game* → copy the 6-character code → on the
second device open the same URL → paste the code → *Join*.

### Two things that will bite you

1. **Re-run the script after every `wsl --shutdown`** (and after a Windows reboot). The relay
   points at a literal WSL address and WSL hands out a new one on every boot. Symptom: the
   URL worked yesterday, gives "unable to connect" today. `-Remove` uninstalls the rules.
2. **Hand out `192.168.254.53`, never `172.24.x.x`.** The former is Windows' Ethernet
   address, the latter is inside the VM. The Windows address can also change if DHCP gives
   the PC a different one — re-check with
   `Get-NetIPAddress -AddressFamily IPv4 | Where-Object InterfaceAlias -eq Ethernet`.

Also note: joining a game needs only the 6-character code and **no token**, so anyone who
sees the code can take that seat. Fine at home; don't publish it beyond a trusted LAN.

---

## 5. Behaviour worth knowing at runtime

- **Games live in memory only** (`ConcurrentHashMap`). Restarting the backend deletes every
  game. The client notices the resulting 404 and drops back to the lobby.
- **The server is authoritative.** The client only ever *guesses* a move onto an empty square;
  battles wait for the push. Expect a brief pause after moving into contact.
- **Moves arrive over the socket, not over REST.** Every change is pushed per player. If the
  socket dies, moves fall back to REST and the UI shows a disconnected state.
- **Fog of war is server-side.** Enemy ranks serialise as `rank: null` until they fight. Don't
  "fix" the client to infer them — that's the whole game.
- The bot opponent exists as `POST /api/games/vs-bot[?difficulty=…]`, but **the UI has no
  button for it** — use the API directly.
- **You are asked your name once**, on the first visit, and it is kept in the browser under
  `generals.player`. There is no account: the name *is* the identity in the win table, so
  anyone on this server playing as `Nick` shares that row. To play as somebody else, clear
  that key in devtools.
- **The win table survives a restart; games do not.** It lives at `data/leaderboard.json`
  beside wherever you started the server (override with `--generals.leaderboard.path=`).
  Delete that file to clear the scores. A corrupt one is logged and replaced by an empty
  ledger rather than stopping the server.

---

## 6. REST + socket reference

| Call | Purpose |
| --- | --- |
| `GET /api/meta` | board size, army size, rank list — good health check |
| `POST /api/games` | create a game → **201** + `gameId` + player token; body `{"name":"Nick"}` |
| `POST /api/games/{id}/join` | take the second seat (no token needed); body `{"name":"Nick"}` |
| `POST /api/games/matchmake` | pair with a waiting player, else create; body `{"name":"Nick"}` |
| `POST /api/games/vs-bot` | create against the computer; body `{"name":"Nick"}` |
| `GET /api/leaderboard` | every player, best first: wins, losses, played, win rate, streak. `?limit=` (1–500) if you want a slice |
| `GET /api/games/{id}` | state, redacted for the caller (header `X-Player-Token`) |
| `POST /api/games/{id}/placement` | `{"pieces":[{"row","col","rank"}]}` — must be **wrapped** |
| `POST /api/games/{id}/move` | `{"from":{"row","col"},"to":{"row","col"}}` |
| `POST /api/games/{id}/chat` | say something to the other player: `{"text":"..."}` (≤200 chars). The author is the seat, not the body. |
| `ws /ws` | SockJS/STOMP; subscribe `/user/queue/game/{id}`, errors on `/user/queue/errors`; send to `/app/game/{id}/move`, `/app/game/{id}/placement`, `/app/game/{id}/chat` |

Status codes worth memorising: `201` on create, `400` illegal move **or a missing name**,
`403` bad token, `404` unknown game, `409` wrong phase. Note the leaderboard is
`/api/leaderboard`, not `/api/games/leaderboard` — it is not under `/api/games`.

---

## 7. Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| Blank white page in a browser | `define: { global: 'globalThis' }` missing from `vite.config.ts` — `sockjs-client` reads a bare `global` in Vite's ESM pre-bundle. Every test stays green while the page is blank. |
| "Cannot reach the server" in the UI | Backend not running, or (dev mode) it wasn't started before Vite. |
| `403 Invalid CORS request` | You're loading the SPA from one origin and hitting the API on another. Serve the SPA from the jar instead (§2B) — there is no CORS config in this app and none is needed. |
| LAN device can't connect | Re-run `tools/expose-lan.ps1` (§4), and check you are using the Windows address, not the WSL one. |
| Port 8080 already in use | A previous jar is still running: `pkill -9 -f '[g]enerals-0.0.1-SNAPSHOT'` — brackets, or `pkill -f` matches its own shell and kills your terminal. |
| Port 5173 already in use | Same for the dev server: `pkill -9 -f '[n]ode_modules/.bin/vite'`. Its command line holds no port number, so `pkill -f vite.*5173` matches nothing and quietly leaves it running. |
| Scores page looks short | The ledger is per-server: `/leaderboard` shows this server only. `data/leaderboard.json` holds everyone; delete it to start over. |
| Changes to React code don't show in the packaged jar | You ran `npm run build` instead of `npm run build:jar`. |
| Maven hangs on startup of a build | You forgot `-o`. |
| `game <id> not found` mid-game | The backend restarted; games are not persisted. Create a new one. |