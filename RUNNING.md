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
# backend: 203 tests, builds the jar
cd backend  && mvn -o clean verify

# frontend: 134 tests, type-checks (tests included), lints
cd frontend && npm run build && npm test && npm run lint
```

- `npm run build` runs `tsc -b` **then** `vite build`, so test files are type-checked too.
- `npm run lint` (`oxlint`) reports the expected `only-export-components` warning in
  `src/state/GameContext.tsx`. That is expected, not a regression.
- Unit and integration tests are **not sufficient** for frontend work — jsdom never loads
  real deps and cannot race two connections. Two blank-page/stuck-screen bugs got through
  125 green tests. Before trusting a frontend change, run the real browser harness (138
  checks, two real browsers playing each other):

```sh
tools/install-browser.sh    # once: Chromium + libs into /tmp/opencode/browser, no sudo
tools/browser-run.sh        # boots jar + dev server, plays a full game in two windows, tears down
```

There is a second harness for the turn clock, which cannot be part of a full game: waiting
out a 5-second clock is most of what it does, and it deliberately leaves one window alone to
watch the server move for it (27 checks, about a minute).

```sh
CLOCK_ONLY=1 tools/browser-run.sh   # boots the jar with --generals.turn-seconds=5
```

And a third for a game against the computer, which the other two never start: one window,
the lobby card, and a bot that has to deploy itself and answer a move (26 checks, about a
minute).

```sh
BOT_ONLY=1 tools/browser-run.sh
```

`browser-run.sh` deletes its own ledger first (a run must not inherit the last run's
scores), **takes the ports by pid before booting** — a server already on `:8080` answers
`/api/meta` perfectly, so without that the whole run would quietly test an older build —
and kills both servers on the way out, reporting what is left. If a run is killed
mid-flight, clear the port yourself:

```sh
pkill -9 -f '[g]enerals-0.0.1-SNAPSHOT'; pkill -9 -f '[n]ode_modules/.bin/vite'
ss -ltnp 'sport = :8080'   # anything still there was started some other way
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
- **Playing the computer is a card in the lobby.** Pick a level — Easy moves at random, Normal
  scores every move, Hard also remembers the openings that have lost it games — and press *Start
  game*. The computer deploys itself and plays at its own pace; the board names it *Computer
  (Hard)* so a refresh leaves you knowing what you are up against. The picker opens on Hard
  because that is what the server serves when no level is asked for, so the button and a bare
  `curl` start the same game.
- **You are asked your name once**, on the first visit, and it is kept in the browser under
  `generals.player`. There is no account: the name *is* the identity in the win table, so
  anyone on this server playing as `Nick` shares that row. To play as somebody else, clear
  that key in devtools.
- **The page follows your machine until you say otherwise.** Light or dark is decided by
  `prefers-color-scheme` until the switch in a screen's header is used, after which the
  choice is kept in the browser under `generals.theme` and wins over the OS. Clear that key
  to go back to following the system. Each browser has its own — one player switching does
  not move the other's page.
- **Moves are timed, by the server.** Each turn gets 60 seconds (`generals.turn-seconds`),
  shown as a countdown in the board's header. When it runs out the server plays a **random
  legal move** for whoever let it expire and says so in the move log — the clock keeps
  running while a tab is closed, which is the whole reason it is on the server. The computer
  is never on a clock. Start an untimed game with `--generals.turn-seconds=0`: no countdown
  is sent and nothing moves itself.
- **The win table survives a restart; games do not.** It lives at `data/leaderboard.json`
  beside wherever you started the server (override with `--generals.leaderboard.path=`).
  Delete that file to clear the scores. A corrupt one is logged and replaced by an empty
  ledger rather than stopping the server.
- **Leaving is a deliberate press, and it loses.** *Leave* in the board header (or beside
  the Ready button while arranging) asks once — "Leave and lose the game?" — and confirming
  ends the game for both sides: you see *Defeat*, the opponent sees *Victory* with the
  reason *RED left the game*, and the result goes on the ledger under both names. Closing a
  tab does **not** forfeit anything: the clock keeps playing for whoever is still there, so
  an abandoned game carries on rather than handing somebody a win.

---

## 6. REST + socket reference

| Call | Purpose |
| --- | --- |
| `GET /api/meta` | board size, army size, rank list — good health check |
| `POST /api/games` | create a game → **201** + `gameId` + player token; body `{"name":"Nick"}` |
| `POST /api/games/{id}/join` | take the second seat (no token needed); body `{"name":"Nick"}` |
| `POST /api/games/matchmake` | pair with a waiting player, else create; body `{"name":"Nick"}` |
| `POST /api/games/vs-bot` | create against the computer; body `{"name":"Nick"}`, `?difficulty=RANDOM\|HEURISTIC\|LEARNING` (default `LEARNING`, unknown value 400). Both seats are seated before it answers, so the caller lands straight on deployment. |
| `GET /api/leaderboard` | every player, best first: wins, losses, played, win rate, streak. `?limit=` (1–500) if you want a slice |
| `GET /api/games/{id}` | state, redacted for the caller (header `X-Player-Token`) |
| `POST /api/games/{id}/placement` | `{"pieces":[{"row","col","rank"}]}` — must be **wrapped** |

Once an army is in, the view's `youPlaced` is `true` **for that player only** — it is what
turns the deploy button into the green, ticked, inert "Army placed" state. Both the view and
the WebSocket push carry it, so a refresh shows the settled screen rather than an empty camp.
| `POST /api/games/{id}/move` | `{"from":{"row","col"},"to":{"row","col"}}` |
| `POST /api/games/{id}/chat` | say something to the other player: `{"text":"..."}` (≤200 chars). The author is the seat, not the body. |
| `POST /api/games/{id}/resign` | leave the game: header `X-Player-Token`, no body → the opponent wins (`200` + your final state, pushed to both). `409` in the waiting room or after the game has already ended. |
| `ws /ws` | SockJS/STOMP; subscribe `/user/queue/game/{id}`, errors on `/user/queue/errors`; send to `/app/game/{id}/move`, `/app/game/{id}/placement`, `/app/game/{id}/chat` |

Status codes worth memorising: `201` on create, `400` illegal move **or a missing name**,
`403` bad token, `404` unknown game, `409` wrong phase **or a resignation that has nothing
to hand over** (waiting room, already finished). Note the leaderboard is
`/api/leaderboard`, not `/api/games/leaderboard` — it is not under `/api/games`.

Every state payload (REST and push alike) carries the clock as `turnDeadlineMillis` — an
**absolute epoch instant**, not a duration — alongside `turnSeconds`. Both are `null` when
no clock is running: before deployment, on the computer's turn, after the game, or with
`--generals.turn-seconds=0`. Count down to the deadline; never start a timer of your own,
or a push that took a second in transit becomes a second off your move. Anything reading
these has to agree with the server's clock, and a client with its own time minutes out will
show a number that is minutes out.

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
| Port 8080 answers but the change I just built is not there | Something else owns the port: a leftover `mvn spring-boot:run`, whose command line is a maven classpath and so is invisible to `pkill -f generals`. `tools/browser-run.sh` now takes the port by pid and refuses to run if the listener is not its own jar — if you boot the backend by hand, check `ss -ltnp 'sport = :8080'` first. |
| A piece moved and it was not me | That was the clock. `--generals.turn-seconds` governs it, and the move log says `… ran out of time`. |
| `game <id> not found` mid-game | The backend restarted; games are not persisted. Create a new one. |