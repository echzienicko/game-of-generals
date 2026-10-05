# AGENTS.md

Notes for anyone (human or agent) working on this repository.

## What this is

Two-player **Game of the Generals** (Salpakan): an 8x9 hidden-rank war game.
Spring Boot backend, React + TypeScript frontend, live play over STOMP/WebSocket.

Progress and scope live in [`TODO.md`](TODO.md); how to start, test, and play the thing lives in
[`RUNNING.md`](RUNNING.md).

## Commands

### Backend (`backend/`)

| Task | Command |
| --- | --- |
| Run | `mvn spring-boot:run` |
| Test (fast) | `mvn test` |
| **Full check — run this** | `mvn clean verify` |

`mvn clean verify` is the gate: 175 tests, must be green before any change is done. No network: use `-o`.

Non-interactive shell — call toolchain by full path:

```sh
export JAVA_HOME=$HOME/.local/jdk
$HOME/.local/maven/bin/mvn -o clean verify
```

### Frontend (`frontend/`)

| Task | Command |
| --- | --- |
| Dev server (port 5173, proxies `/api` and `/ws` to 8080) | `npm run dev` |
| **Full check — run this** | `npm run build && npm test && npm run lint` |

- `npm run build` runs `tsc -b` then `vite build` — type-checks **including test files**.
- `npm test` is `vitest run`; `npm run test:watch` for the loop.
- `npm run lint` is `oxlint`. Two expected warnings in `state/GameContext.tsx` (`only-export-components`).

## Running both halves

Start backend first (frontend proxies to `http://localhost:8080`).

## Playing over the LAN (other phones/laptops)

The SPA is packaged into the jar, and the client only ever asks for relative `/api` and
`/ws`, so **one origin on `:8080` is the whole game** — no CORS, no proxy. Rebuild it with
`npm run build:jar` (which rsyncs `dist/` into `backend/src/main/resources/static/`, so the
jar never ships a stale frontend) and run `java -jar backend/target/generals-0.0.1-SNAPSHOT.jar`.
`npm run dev` works from a phone too (`server.host: true` binds 0.0.0.0); its `/api` and `/ws`
proxies are resolved on this machine, so the browser still sees a single origin.

**The blocker is WSL2, not the app.** This is Windows 10 22H2 (build 19045) on WSL 3.0.1.
The VM sits behind NAT on `172.24.x.x`, an address no other device can route to; only
Windows reaches it (that is why `localhost:8080` works from Windows). Mirrored networking
would make the Linux side directly LAN-reachable but **requires Windows 11 22H2+** — on
Windows 10 WSL just prints "Mirrored networking mode is not supported, falling back to NAT".

So forward instead, from an **elevated** PowerShell on Windows:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\expose-lan.ps1
```

It reads the current WSL address itself, installs a `netsh interface portproxy` relay plus a
`LocalSubnet` firewall rule for 8080 (and 5173 for the dev server), and prints the URLs to
hand out — here `http://192.168.254.53:8080`. **Re-run it after every `wsl --shutdown`**, since
the relay points at a literal address and WSL changes it on every boot. `-Remove` undoes it.

Two cautions: joining a game needs only the 6-character code and no token, so anyone who can
see the code can take your seat (fine at home, don't expose it beyond the LAN); and the
Windows Ethernet adapter is a **Public** network profile, hence the explicit firewall rule.

## Rules of the game (do not "fix" — they are the spec)

- Board 8x9. RED camps rows 0-2, BLUE rows 5-7. RED moves first.
- Each army: 21 pieces (12 officers 5★→Sergeant, 6 Private, 2 Spy, 1 Flag).
- Move: one square orthogonal.
- **Solo move:** piece with no friendly orthogonal neighbour may move two squares straight onto empty square (any direction).
- Battle precedence: flag beats spy, spy beats officer, Private beats spy, officers by rank. Equal officers and two Privates both die. Two spies both die. Flag reaching enemy camp: opponent gets one more move; if flag survives, it loses.
- **A battle reveals nothing.** Not the loser, not the winner, not the flag. Ranks stay face-down to the opponent for the whole game; the only public facts are *who moved*, *who held the square*, and *who died*.
- Win by capturing enemy flag.

## Traps in this codebase (cost real debugging time)

1. **Broker must serve `/queue`, not just `/topic`.** Push to `/user/{token}/queue/game/{id}` rewrites to `/queue/game/{id}`. If `WebSocketConfig` registers only `/topic`, messages are silently discarded — HTTP tests pass, screens freeze. `GameSocketIntegrationTest` guards this.

2. **Never broadcast on a shared topic.** Each player receives their own fog-of-war view via `convertAndSendToUser`. A shared `/topic` leaks opponent's hidden ranks.

3. **Fog of war enforced server-side, and a battle reveals nothing.** `GameViewMapper` sends every enemy rank as `rank: null` — including the piece that just won a fight. Nothing in the engine calls `Piece.reveal()`; do not add such a call. Two separate leaks to watch for:
   - `BattleResult.description()` is the arbiter's full account and **names both ranks** ("5-Star General defeats Private"). It must never reach a client. `GameViewMapper.battleDescription` rebuilds it per viewer; `Game.move` also stores it on the `MoveRecord`, which is fine because games are in-memory and never exported — but do not add a field that ships it.
   - `BattleDto`'s four rank fields are now nullable. `battleDto` nulls whichever side the viewer does not own. `BattleOverlay` renders those as "Unknown".

   Move log is redacted too (`MoveRecord.moverRevealed`, and contested moves go through the same `battleDescription`).


4. **Camp has 27 squares, army has 21 pieces.** Code deploying/randomising from zone must slice to `pool.length`. Forgetting sends 27 entries (HTTP 400) or writes `undefined` ranks.

5. **Placement request bodies are wrapped:** `{"pieces":[{"row","col","rank"}]}`. Bare array = 400 with no useful message.

6. **`POST /api/games` returns 201**, not 200.

7. **No `WebSocketStompClient` for socket integration tests.** Tomcat's JSR-356 endpoint dispatches on a thread pool, corrupting STOMP partial-frame state and losing pushes. Spring 6 removed `JdkWebSocketClient`; `StandardWebSocketClient` can't pin executor. `GameSocketIntegrationTest.RawStompClient` is hand-rolled on `java.net.http.WebSocket` (sequential delivery). STOMP frames are NUL-terminated (`\0`), not space-terminated.

8. **Games are in-memory only** (`ConcurrentHashMap`). Restart loses all games; frontend detects 404 and returns to lobby. Mutations synchronised on `Game` instance.

9. **`pkill -f` matches its own shell.** Use bracket pattern: `pkill -9 -f '[g]enerals-0.0.1-SNAPSHOT'`.

10. **There is no CORS filter on the backend, and `spring.cors.*` is not a Spring Boot 3 property.** Boot 3.5's autoconfigure jar ships only `spring.graphql.cors.*`; `spring.cors.allowed-origins` binds to nothing. An earlier version of `application.yml` set it, which looked like protection and was not. It "worked" solely because the client uses relative `/api` + `/ws`, so the SPA and API are same-origin. Serving the frontend from a different origin needs a real `WebMvcConfigurer` CORS bean — a property will not do it.

11. **Vite's dev server binds `127.0.0.1` unless `server.host` is set.** Invisible from `localhost` and from any test; it only shows up when someone tries to open the game from a phone.

12. **A face-down piece must carry no emblem.** The piece emblem varies by rank (officer
    pips), so `PieceView`'s `hidden` branch renders no emblem at all — see the comment
    there. This is the fog of war: an emblem would hand the rank straight over. `App.test.tsx`
    renders every rank face-down and asserts the markup is byte-identical.

13. **The bot plays with no rank knowledge, and that is correct.** Because nothing is revealed, every `isRevealed()` branch in `BotBrain` (lines 182, 224, 262, 317) is inert: the flag shortcut, the resolved-outright odds, the `outstandingEnemyRanks` subtraction and the beater-adjacency penalty all never fire. The bot scores every defender against the full 21-rank roster, which is the honest play — do **not** give the bot privileged knowledge to make it stronger; the invariant is in its own Javadoc. Consequence: bot-vs-bot games run long. The slowest of the eight seed pairs in `BotBrainTest.finishesGamesWithTheFogNeverLifted` needs 674 moves, so keep `MOVE_CAP` (1200) generous — a 400 cap reports slow games as stalls.

14. **A game can finish on either path, and both must record the win.** `GameManager.move`
    mutates `Game` for a human turn; `BotOpponent.act` mutates the *same* `Game` directly
    for the computer's turn, because the bot plays off the domain object. A win-detection
    check written only in `GameManager` therefore silently drops every game the computer
    won. `ResultRecorder` exists so exactly one piece of logic answers "has this game's
    result been filed?" — `GameSession.claimResultFiling()` is a CAS that returns true once
    per game, and both observers go through it. **Do not** call `leaderboard.recordResult`
    from anywhere else.

15. **Every seat is named, so all four seat-creating endpoints require a body.** `POST
    /api/games`, `/{id}/join`, `/matchmake` and `/vs-bot` take `{"name":"..."}` and answer
    400 without one (`Leaderboard.requireValid`, already mapped to 400 by
    `ApiExceptionHandler`). This is not decoration: the name is the ledger's key, so a game
    seated without one is a game whose win cannot be recorded. Note `LeaderboardController`
    is mapped at `/api/leaderboard`, **not** under `/api/games` — a `@GetMapping` added to
    `GameController` lands on `/api/games/leaderboard` and 404s.

16. **The ledger is a real file, so tests and harnesses must not inherit each other's
    scores.** `generals.leaderboard.path` defaults to `data/leaderboard.json` (gitignored).
    `GameApiTest` points it at `target/` and states every expectation relative to what is
    already on the table; `tools/browser-run.sh` deletes its ledger and passes a scratch path
    to the jar. `Leaderboard.load()` is lenient on purpose — an unreadable file is logged and
    replaced by an empty ledger, and unknown fields are ignored, because refusing to read a
    ledger from a later version would throw away every score in it.

## Testing philosophy

Server is authoritative. Client's `applyQuietMove` only guesses moves onto **empty** squares; battles wait for push — client never keeps second copy of precedence rules. One move in flight at a time; refusal on `/user/queue/errors` rolls board back to saved pre-move state.

Add tests at the level that owns the rule: `rules.ts`/components for client logic, `GameSocketIntegrationTest` for real broker, `GameApiTest` for REST.

## Verified so far

- 175 backend tests, 86 frontend tests, type-check, lint, production build.
- LAN path verified from Windows over the Windows LAN address (192.168.254.53), through the
  portproxy relay: `/api/meta` 200, `/` 200, `/lobby` 200, `/ws/info` 200 (SockJS
  negotiation), `POST /api/games` 201 with a live gameId, dev server `:5173` 200, and a
  proxied `POST /api/games` with `Origin: http://172.24.223.0:5173` 201 — that last one is
  the check that would have caught a CORS wall on the dev-server path.
- Wire-level harness (`/tmp/opencode/e2e.mjs`, 38 checks; both seats named, and it asserts a nameless `POST /api/games` is 400) plays complete 44-move game over two real STOMP sessions; checks per-user pushes, fog of war across 11 battles, illegal move rejection. `/tmp/opencode/errs.mjs` (15 checks) covers `/user/queue/errors`.
- Browser harness (`tools/browser-e2e.mjs`, 86 checks) plays complete game in two real Chromium windows via UI. Run with `tools/browser-run.sh` (boots jar + dev server). First run `tools/install-browser.sh` (no sudo — Chromium + libs installed to `/tmp/opencode/browser`).
- Chat and names re-verified at every layer: `GameApiTest` (8 tests — shared between
  players, author taken from the seat, waiting room, validation, token required, the
  100-line cap, seats carrying names, bot seat nameless) and `GameSocketIntegrationTest`
  (a line sent over STOMP reaches the other player, whitespace collapsed; a refused line
  comes back on `/user/queue/errors` and is never broadcast). Browser harness: two real
  windows exchange lines both ways during placement, the box empties on send, `.chat__line--mine`
  is marked *You* while the opponent's line is marked with their name, and the conversation
  is still on screen when the game goes live.
- Names shown where a colour used to be: browser harness asserts both names in
  `.game__identity` and on the `.strength` bar, the waiting banner names the player you are
  waiting for, and the game-over line reads *against Rival*.
- Fog of war re-verified after the battle-reveal fix, at every layer: wire harness sees
  `peak 0` enemy ranks across 11 battles and per-viewer `BattleDto` redaction; browser
  harness sees the overlay read `5-Star General vs Unknown` with the caption `your piece
  holds the square and an unidentified enemy piece is destroyed`, and `21 still hidden, 0
  revealed` for the losing player at the end.

## Traps the tests did not catch (browser-only bugs)

These broke the app in a browser while all unit/integration tests stayed green (jsdom never loads real deps or races two connections). Use browser harness before trusting frontend changes.

1. **`sockjs-client` reads bare `global`.** Published CommonJS bundle wraps modules in `(function(global){...})`, but Vite pre-bundles ESM entry where wrapper is gone → `global.crypto` throws, app renders nothing. `define: { global: 'globalThis' }` in `vite.config.ts` fixes it. **Do not remove.**

2. **A table wide enough for eight columns pushes a phone sideways.** The scores page
   measured 493px of content in a 360px viewport once `Played` and `Win rate` were added.
   jsdom has no layout engine, so every frontend test stayed green while a phone would
   scroll the page out from under the reader. The table now sits in its own
   `overflow-x: auto` region (`role="region"`, labelled, `tabIndex={0}` so it can be
   scrolled from the keyboard) and `browser-e2e.mjs` resizes to 360px and asserts
   `documentElement.scrollWidth <= innerWidth`. Any new column needs that check re-run —
   the board already scrolls internally for the same reason.

   The same trap sits in the **game screen**, and it bit for a second reason: adding a
   name to each seat chip in the header made `.game__header` 372px wide in a 360px
   viewport, so the page scrolled even though every element inside it was legal. The fix
   is `flex-wrap: wrap` on `.game__header` and `.game__identity` (the controls drop under
   the names). The harness check reports *which elements* crossed the edge, not just the
   total — "372px in 360px" alone does not say what to fix, and the first entry it named
   (`.board__grid`, 460px) was a red herring: that one is inside the board's own
   `overflow-x: auto` region and is supposed to be wider than the screen.

3. **`pkill -f` on a pattern the process does not contain leaves it running, quietly.**
   `browser-run.sh` used to end with `pkill -f '[v]ite.*5173'` and report
   `leftover vite: 0` — but the dev server's command line is
   `node .../node_modules/.bin/vite`, with no port in it, and npm's child outlives a kill
   on npm. Every run left a server holding `:5173` and every report claimed otherwise.
   Match the real command line (`[n]ode_modules/.bin/vite`) and count with the same
   pattern you kill with; a leftover check that counts nothing is worse than none.

4. **REST response can arrive after socket push that supersedes it.** Different connections → push for change you requested can overtake response to that request. Applying stale response rewinds board — player stuck on deployment while game is live. `applyUnlessPushed` in `GameContext.tsx` drops any response a push overtook; server broadcasts after every change, so newest push is never older than a response still in flight.

5. **A name is not a rank, but chat is still a channel.** Chat and the seat names ride the
   same per-player state push, so `GameViewMapper.toDto` takes `(Game, GameSession,
   PlayerColor)` — the mapper reads session state it cannot inject without a cycle. Two
   rules keep the fog intact and they are worth not undoing:
   - the **author of a chat line comes from the seat, never from the request body.** There
     is no `author` field to spoof; `Requests.ChatRequest` carries `text` alone. If one is
     ever added, the fog is gone, because the only thing that makes a name safe is that it
     was chosen at seat time and can never be rewritten mid-game.
   - **the bot seat has no name and refuses to chat.** `seatsFor` reports `bot: true`,
     `name: null` and `say` answers "the computer does not chat". A bot that appears to
     answer back would be the one actor on the board whose intent the opponent could read.

   Chat is capped at `MAX_CHAT_MESSAGES = 100` (the deque drops the oldest) and
   `GameSession.MAX_CHAT_LENGTH = 200`, validated in `requireMessage`: stripped, internal
   whitespace collapsed, ISO control characters refused outright. It is allowed in the
   waiting room as well as during play, because the waiting room is where two strangers
   agree on the match; a guard that says "you cannot chat until the game starts" is a
   guard nobody asked for. The client keeps its own `sendingChat`/`chatError` state rather
   than routing through `run()`, because a failed chat must not roll the board back to a
   saved pre-move snapshot — no move was made.

- Win table re-verified after the leaderboard work, at every layer: `LeaderboardTest` (22
  tests over naming, tallying, streaks, ordering and the file), `GameManagerLeaderboardTest`
  (12 tests that play whole games — both seats named, a win filed from `GameManager.move`,
  a loss filed from the bot's own turn, and the exactly-once guarantee), `GameApiTest` over
  HTTP, the wire harness (38 checks, `WireBlue 2 wins` accumulating over two games), and
  the browser harness: red reaches the gate first, is stored as `generals.player`, keeps the
  game across a refresh without being asked again, is told `Recorded as a win for Red.`, and
  the table at `/leaderboard` reads `Red 1/0`, `Blue 0/1` with the reader's own row marked,
  each row's `Played`/`Win rate` agreeing with `GET /api/leaderboard` (`gamesPlayed`,
  `winRatePercent`), and the whole table served with no `?limit=` —
  matching `GET /api/leaderboard` exactly, with no row for the computer.
- **Persistence proven the only way that counts**: with the ledger pointed at
  `/tmp/opencode/wire-leaderboard.json`, the process was killed and restarted, and the boot
  log reads `leaderboard: loaded 2 players` with `WireBlue 2/0` and `WireRed 0/2` served
  again. Every unit test can be green while the file is never written; this could not.
