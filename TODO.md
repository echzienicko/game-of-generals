# Game of the Generals — Project TODO

A real-time two-player "Game of the Generals" (Salpakan) web game.

- **Backend:** Spring Boot (Java 17+), WebSocket/STOMP for real-time play
- **Frontend:** React (Vite) + TypeScript
- **Goal:** Two players join a game, secretly place 21 pieces each on an 8x9 board,
  then alternate moves. Pieces are face-down; the server arbitrates every challenge.

---

## 0. Environment / Prerequisites — ALL DONE ✅

- [x] **Java JDK 17** — Temurin 17.0.20.1 in `~/.local/jdk` (no sudo). `JAVA_HOME` + PATH
      set at the top of `~/.bashrc` (above the non-interactive early-return), and all 28
      JDK binaries symlinked into `~/.local/bin`. Verify: `java -version`.
- [x] **Maven** — Apache Maven 3.9.16 in `~/.local/maven`, `MAVEN_HOME` + PATH in `~/.bashrc`,
      `mvn` symlinked into `~/.local/bin`. Verify: `mvn -version`.
- [x] **Node.js + npm** — already present (Node v22.22.1, npm 9.2.0).
- [x] **Backend scaffold built & verified** — `mvn clean package` succeeds, produces a 23 MB
      fat jar, and the app boots on port 8080 (~2.3 s startup).
- [x] **Frontend scaffold built & verified** — `npm run build` (tsc + vite) succeeds;
      dev server serves HTTP 200 on port 5173.
- [x] **Real-time client libs installed** — `sockjs-client` + `@stomp/stompjs`.
- [x] **Dev proxy configured** — `vite.config.ts` proxies `/api` and `/ws` (ws: true) to
      `localhost:8080`; `application.yml` allows CORS from `localhost:5173`.
- [x] *(Optional)* Docker run strategy — Docker binary not on PATH in this WSL distro.
      Native processes work fine; no blocker.

**Run commands**
```bash
# backend  (http://localhost:8080)
cd backend && mvn spring-boot:run

# frontend (http://localhost:5173)
cd frontend && npm run dev
```

> Note: open a new terminal (or `exec bash`) so the updated PATH is picked up.

---

## 1. Project Scaffolding

- [x] Create root layout:
  - `backend/` — Spring Boot app
  - `frontend/` — Vite React app
- [x] Initialize Spring Boot backend (Spring Initializr or manual `pom.xml`):
  - Dependencies: `spring-boot-starter-web`, `spring-boot-starter-websocket`,
    `spring-boot-starter-validation`, `spring-boot-starter-test`.
  - Java 17, Maven, group `com.generals`, artifact `generals`. Entry point:
    `backend/src/main/java/com/generals/GeneralsApplication.java`.
  - Config: `backend/src/main/resources/application.yml` (port 8080, CORS for :5173).
- [x] Initialize frontend: `npm create vite@latest frontend -- --template react-ts` (Vite 8).
- [x] Add `.gitignore` (root + per project) and a top-level `README.md`.
- [x] *(Optional)* Add `docker-compose.yml` for one-command startup. — Skipped, native runs work.

---

## 2. Backend — Domain Model — DONE ✅

All in `backend/src/main/java/com/generals/domain/`.

- [x] `Rank` enum with correct hierarchy:
  - Five-Star General, Four-Star General, Three-Star General, Two-Star General,
    One-Star General, Colonel, Lt. Colonel, Major, Captain, 1st Lieutenant,
    2nd Lieutenant, Sergeant, Private, Spy, Flag.
- [x] `Piece` class: id, `Rank`, owner (`PlayerColor`), current `Position`, `revealed` flag.
- [x] `Position` (row, col) value object with validation (8 rows x 9 cols) + `label()`,
      camp helpers, neighbours, `isOnBoard`.
- [x] `PlayerColor` enum (RED, BLUE) with `opponent()`.
- [x] `Board`: 2D grid, setup zones, `deploymentZone()`, `isAloneOnBoard()`,
      `possibleTargets()`, flag lookup.
- [x] Standard army composition per player (21 pieces) — `ArmyFactory`:
  - [x] 1x Five-Star, 1x Four-Star, 1x Three-Star, 1x Two-Star, 1x One-Star
  - [x] 1x Colonel, 1x Lt. Colonel, 1x Major, 1x Captain, 1x 1st Lt., 1x 2nd Lt., 1x Sergeant
  - [x] 6x Private, 2x Spy, 1x Flag

---

## 3. Backend — Game Rules Engine — DONE ✅

- [x] `BattleResolver` (Arbiter): resolve attacker vs defender per official rules.
  - [x] Five-Star defeats all officers, Spy, Flag.
  - [x] Four-Star defeats Three-Star and below, Spy, Flag.
  - [x] (Continue descending for all officer ranks.)
  - [x] Spy defeats all officers (Generals through Sergeant) but loses to Private.
  - [x] Private defeats Spy; Private vs Private => both removed.
  - [x] Equal ranks => both removed.
  - [x] Flag: captured by any piece; only wins if it attacks a Flag.
  - [x] Two spies => both removed.
  - [x] A Flag attacking any soldier is destroyed.
- [x] `BattleResult` record describing attacker/defender survival.
- [x] `MoveValidator`: one orthogonal step to an empty square or enemy square, plus the
      "solo move" (a piece with no friendly neighbour may advance two squares).
- [x] Win conditions:
  - [x] Capture the enemy Flag.
  - [x] Move your Flag safely to the opponent's back row — the opponent gets **one final
        move** to capture it; if it survives, its owner wins.
- [x] `Game`: state machine (`WAITING_FOR_OPPONENT` -> `PLACEMENT` -> `IN_PROGRESS` ->
      `FINISHED`), current player, `MoveRecord` history, winner + reason.
- [x] Secret placement phase: validates 21 pieces, correct roster, inside own 3-row zone;
      pieces stored face-down.
- [x] A battle reveals nothing (fog of war). The winner stays face-down, the loser is
      gone, and the `BattleDto` ranks are nulled for whichever side the viewer does not own.
      Corrected from an earlier build that did reveal both pieces.
- [x] Unit tests for every battle combination and movement rule — **63 tests, all passing**
      (`mvn test`). Coverage includes the full 12x12 officer ladder from both sides.

---

## 4. Backend — API + Real-time Layer — DONE ✅

`backend/src/main/java/com/generals/{api,service,config}/`

- [x] REST endpoints:
  - [x] `POST /api/games` — create a game, returns `gameId` + player token.
  - [x] `GET  /api/games/{id}` — fetch game state (sanitized per player).
  - [x] `POST /api/games/{id}/join` — join as the second player.
  - [x] `POST /api/games/{id}/placement` — submit the secret 21-piece setup.
  - [x] `POST /api/games/{id}/move` — submit a move.
  - [x] `POST /api/games/matchmake` — pair with a waiting opponent, else create.
  - [x] `GET  /api/meta` — board size, army size, rank names, roster counts.
  - [x] **Player names.** Every seat-creating endpoint (`/games`, `/join`, `/matchmake`,
        `/vs-bot`) takes `{"name":"..."}` and answers 400 without one. The name is the key a
        win is recorded under, so an unnamed seat is a seat whose win could not be filed.
  - [x] `GET /api/leaderboard` — wins per name, best first. Its own controller at
        `/api/leaderboard`, not under `/api/games`.
  - [x] `POST /api/games/{id}/chat` and `/app/game/{id}/chat` — talk to the other player.
        The text is all the client sends; the author comes from the seat. 200 chars,
        100 lines kept, allowed in the waiting room too. The computer neither has a name
        nor answers.
  - [x] **Opponent's name on screen.** `GameStateDto.seats` carries
        `[{color,name,bot,you}]`, so the header, the strength bar, the waiting banner and
        the game-over line say *who* you are playing rather than only which colour you
        are. The name was chosen at seat time and cannot be rewritten mid-game, which is
        what makes it safe to put on the wire beside a board that hides every rank.
- [x] WebSocket (STOMP over SockJS) at `/ws`:
  - [x] `/user/queue/game/{id}` — **per-player** state pushes (not a shared topic).
  - [x] `/app/game/{id}/move` and `/app/game/{id}/placement` — inbound actions.
  - [x] `/user/queue/errors` — rejected actions, reported to the sender only.
  - [x] Token in the STOMP `CONNECT` header, lifted into the session `Principal`.
- [x] **Information hiding** — enforced in `GameViewMapper`:
  - [x] You always see your own army; an enemy piece is only named once it has fought.
  - [x] The move log is redacted too: an unseen move reads "a hidden piece", so the log
        cannot be used to reconstruct the deployment.
  - [x] Verified end-to-end: all 21 enemy pieces stay hidden, including the piece that
        won a fight. `BotBrainTest.finishesGamesWithTheFogNeverLifted` replays eight
        bot-vs-bot seed pairs to a decision with no rank ever revealed (slowest: 674 moves).
- [x] Session/player identity — a UUID token per seat; the server derives the side from it,
      so a client cannot impersonate the opponent.
- [x] Global exception handler returning structured JSON errors
      (400 illegal move / 403 bad token / 404 unknown game / 409 wrong phase).
- [x] All mutations synchronized per game so turns cannot interleave.
- [x] **Persistent win table** (`Leaderboard`) — the one thing that outlives a restart,
      because a score that empties on every redeploy is not worth keeping:
  - [x] A name is the whole of a player's identity: keys are case-insensitive with
        whitespace collapsed, so `Nick`, `nick` and `Nick ` are one row.
  - [x] Wins, losses, games played and win rate (a whole percentage of games played, so
        1-of-1 is honestly distinguishable from 10-of-10), current streak and best streak;
        the winner's time is stamped.
  - [x] The table shows **everyone** by default. An endpoint that silently returned the
        first 50 rows would read as a shorter history, not as a truncation, so `?limit=`
        is opt-in and capped, and the response reports `totalPlayers` so a caller can say
        what it left out.
  - [x] Written with a temp file and `ATOMIC_MOVE`, so the ledger is never half a file.
  - [x] Lenient on load: a corrupt file is logged and replaced by an empty ledger, and
        unknown fields are ignored, because refusing to read a newer ledger would throw
        away every score in it.
  - [x] The computer's seat has no name, so it has no row — a human's win against the bot
        counts and the bot's wins do not pollute the table.
  - [x] Filed exactly once per game. A game can end on either path (`GameManager.move`
        mutates `Game` for a human, `BotOpponent` mutates the same object for the bot), so
        `ResultRecorder` and a CAS in `GameSession.claimResultFiling()` make exactly-once a
        property of the session rather than something two observers must agree on.
- [x] Tests: **166 total passing** (`mvn clean verify`) — 63 domain + 21 HTTP + 5 socket +
      37 leaderboard/service. The HTTP win-rate test reads its own row first and asserts a
        change, because the ledger under `target/` is a real file and survives the run
        before it.

**Verified against a running server:** create → join → both deploy → alternating moves →
battle resolves correctly → fog of war holds → illegal moves rejected with clear messages.

---

## 5. Frontend — Core UI

- [x] Lobby screen: create game, join by id, waiting-for-opponent state.
- [x] Placement screen: drag-and-drop 21 pieces into your 3-row zone, confirm.
- [x] Game board component (8 rows x 9 cols) rendering:
  - [x] Own pieces face-up.
  - [x] Enemy pieces face-down for the whole game.
  - [x] Empty squares, last move highlight, valid-move hints.
- [x] Piece emblem: officers carry one pip per rank step (Sergeant 1 -> 5-Star General
      12, four to a row); the flag, spy and private get a glyph instead of pips, since a
      one-pip private would be indistinguishable from a Sergeant. Stamped above the text
      abbreviation, which stays. A face-down piece gets **no** emblem at all — the pips
      vary by rank, so one would hand the rank straight over.
- [x] Turn indicator, captured-piece trays, move log.
- [x] Battle result modal/animation showing the outcome; the opponent's piece reads
      "Unknown" and the caption names no ranks.
- [x] Victory/defeat screen with rematch option.
- [x] Responsive layout + basic theming.
- [x] Name gate: asked once, on the first visit, then remembered in the browser
      (`generals.player`). No account, no password — two people playing under one name on
      one server share a row, which is the point.
- [x] Scores page at `/leaderboard`: every player who has finished a game, ranked, with
      wins, losses, games played, win rate (with a meter behind the number), current and
      best run, and the reader's own row marked. Reachable
      from the lobby, the name gate and the end screen, and the one page that does not
      require a name — you can look before you commit.
- [x] The end screen says who the result was recorded for (`Recorded as a win for Nick.`).

---

## 6. Frontend — Networking

- [x] API client (fetch/axios) for REST calls.
- [x] STOMP/SockJS WebSocket client wired to backend topics.
- [x] Central game state store (React Context or Zustand/Redux).
- [x] Reconnect handling and optimistic-vs-server reconciliation.
  - Reconnect: `reconnectDelay: 3000`, the subscription is re-established in
    `onConnect`, `onWebSocketClose` flips the UI to disconnected, and `movePiece`
    falls back to REST so play is never blocked by a dead socket.
  - Reconciliation: the server push is always authoritative and supersedes the
    local guess. `applyQuietMove` only moves a piece onto an *empty* square
    (battles wait for the push, so the client never keeps a second copy of the
    precedence rules), and a rejection on `/user/queue/errors` rolls the board
    back to the saved pre-move state. One move is in flight at a time.

---

## 7. Testing & Quality

- [x] Backend: JUnit tests for Arbiter, MoveValidator, win conditions.
- [x] Backend: integration tests for REST + WebSocket flows.
  - `GameApiTest` (12) over HTTP, `GameSocketIntegrationTest` (5) over real STOMP
    on a JDK WebSocket. The socket tests are hand-rolled because Tomcat's
    JSR-356 endpoint delivers frames on a thread pool, which corrupts STOMP's
    partial-frame state and silently loses pushes (Spring 6 removed
    `JdkWebSocketClient`, and `StandardWebSocketClient` exposes no way to pin the
    endpoint executor). They pin the `/queue` broker registration, per-user fog
    of war in the pushed payload, opponent pushes, and rejections landing on
    `/user/queue/errors` with the same message REST gives, and — added after the browser
    run — a join that pushes the creator out of the waiting room.
- [x] Frontend: component tests (Vitest + React Testing Library).
  - `npm test` runs 73 tests in 3s: `rules.test.ts` (22) for the movement and
    camp rules including the new `applyQuietMove`, `GameContext.test.tsx` (12) for
    the optimistic move, its rollback on a refusal, the in-flight guard, the REST
    fallback and the overtaken-response race, and `App.test.tsx` (39) for status
    routing, the fog of war reaching the DOM, the name gate and the scores page. jsdom environment via
    `vite.config.ts`; `tsc -b` type-checks the tests too, so a broken assertion helper
    cannot slip through.
- [x] Manual end-to-end test: two browser windows play a full game.
  - Done in real Chromium: two independent browser contexts play a complete game
    through the UI (`tools/browser-e2e.mjs`, 74 checks, driven by
    `tools/browser-run.sh`, which boots the jar plus the Vite dev server; see
    `tools/install-browser.sh` for the headless Chromium setup).
    It covers rendering, CSS, SockJS in a browser, the clipboard, and console errors.
  - The armies are staged rather than randomised so the ending is deterministic: red's
    five-star general waits alone on the centre column, blue's flag sits two rows in
    front of it, and three moves decide the game.
  - **This found two bugs that 125 unit and integration tests had all missed**, both of
    which made the app unusable in a browser while every other check stayed green:
    1. `global is not defined` — Vite pre-bundles sockjs-client's ESM entry, which reads
       a bare `global.crypto` that the published CommonJS bundle supplies via a wrapper.
       The app rendered a blank page. Fixed with `define: { global: 'globalThis' }`.
    2. A player could be stranded on the deployment screen forever: the REST response to
       their own submission can arrive *after* the socket push announcing the game is
       live, and applying the stale response rewound the view. Fixed by dropping any REST
       response that a push overtook (`applyUnlessPushed`).
  - The first bug also means every earlier "verified" claim about the frontend was only
    ever true of jsdom. The join push was fixed earlier the same way — by finding the
    creator stuck on the waiting room while the joiner went straight to placement.
- [x] Add lint/format scripts (`mvn` verify, `npm run lint`) and document them in `AGENTS.md`.

---

## 8. Stretch Goals (post-MVP)

- [x] AI opponent (random legal moves, heuristic scoring, counting opening memory for learning, and REST endpoint `POST /api/games/vs-bot`).
- [x] Deployment (frontend built and packaged as static resources inside the Spring Boot jar with SPA routing fallback).
- [x] Playable over the LAN from a phone or second laptop.
  - The SPA ships inside the jar and the client only requests relative `/api` + `/ws`, so
    the whole game is one origin on `:8080` — no CORS, no proxy, nothing to configure on
    the phone beyond the URL. `npm run build:jar` keeps the packaged frontend from going
    stale; `npm run dev` also works from another device (`server.host: true`).
  - WSL2 is the obstacle, not the app: the VM is NAT'd on an unroutable `172.24.x.x`, and
    mirrored networking — the native fix — needs Windows 11 22H2+ while this host is
    Windows 10 22H2 (19045), where WSL silently falls back to NAT. So `tools/expose-lan.ps1`
    (run from an elevated PowerShell) installs a portproxy relay plus a `LocalSubnet`
    firewall rule and prints `http://192.168.254.53:8080`. Re-run it after every
    `wsl --shutdown`, because the relay points at a literal WSL address.
- [x] Persistent win table by player name (see §4) — done without accounts, by making the
      name the key and accepting that a name is not a login.
- [ ] Persistent game history (database + JPA).
- [ ] User accounts / authentication.
- [ ] Spectator mode.
- [ ] Sound effects and richer animations.

---

## Suggested Build Order

1. Environment (Java/Maven) → 2. Backend domain + rules (+ tests) →
3. REST/WebSocket layer → 4. Frontend board + placement →
5. Wire real-time play → 6. Battle animations & polish → 7. Stretch goals.