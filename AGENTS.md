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

`mvn clean verify` is the gate: 203 tests, must be green before any change is done. No network: use `-o`.

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
- `npm run lint` is `oxlint`. One expected warning in `state/GameContext.tsx` (`only-export-components`).

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

14. **A game can finish on four paths, and all four must record the win.** `GameManager.move`
    mutates `Game` for a human turn; `BotOpponent.act` mutates the *same* `Game` directly
    for the computer's turn, because the bot plays off the domain object; `TurnClock.fire`
    plays the turn that ran out; and `GameManager.resign` hands the game over to whoever
    stayed. A win-detection check written only in `GameManager`
    therefore silently drops every game the computer won, and every game a player won by
    letting their clock run out. `ResultRecorder` exists so exactly one piece of logic
    answers "has this game's result been filed?" — `GameSession.claimResultFiling()` is a
    CAS that returns true once per game, and all four observers go through it. **Do not**
    call `leaderboard.recordResult` from anywhere else.

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

17. **The clock is on the server, and the move it plays is a *random* one.** A browser cannot
    be trusted to notice its turn ended, and a browser that is not open cannot notice
    anything, so a deadline in the client would freeze the game for both players the moment
    one of them closed the tab — `TurnClock` exists so that cannot happen, and it keeps
    running for a disconnected player on purpose. Two consequences that look like bugs:
    - the expiry move is `BotDifficulty.RANDOM`, *not* the heuristic the computer plays
      with. A player's own pieces playing better for them every time they think too long
      would turn a forgotten tab into an advantage; a random move is legal, so the game
      always continues, and the log says who let it go.
    - the clock is armed by whoever changed the game, not by a timer: `GameManager.move`,
      `GameManager.submitPlacement` and `BotOpponent.act` each call `clock.onChange`
      **before** building the view they return, or the view that goes out carries the
      previous turn's deadline. It clears itself for a finished game, in placement and on
      the computer's turn (`session.botFor(current).isEmpty()`), because a countdown to
      nothing is worse than none.

    The view carries `turnDeadlineMillis` as an **absolute instant** (plus `turnSeconds` for
    the bar) rather than a number of seconds: the client must never keep a countdown of its
    own, because a push delayed in transit would then lengthen the turn. `GameSession`
    holds the duration alongside the deadline (`armTurnClock(deadline, seconds)`) so
    `GameViewMapper` can draw a bar without knowing the configuration. What the client does
    trust is that its clock agrees with the server's — nothing in the payload says what time
    it was on the server, so a machine whose clock is minutes out shows a number that is
    minutes out. That is a known limit, not an oversight; `MoveClock` calls `Date.now()`
    during render and suppresses `react(purity)` for it deliberately, because the
    alternative is a stale reading for a tick, which puts "119s" on a 60-second turn.

    In the UI the countdown is `role="timer"` with `aria-live="off"` and a label inside it
    ("Time left on your move"). **Do not** move it into the `role="status"` banner: a live
    region that says a second is a live region nobody can listen to.

18. **A server already on `:8080` will happily answer for the build you just made.**
    `browser-run.sh` used to boot the jar, poll `/api/meta` and trust a `yes`. A leftover
    `mvn spring-boot:run` — a maven classpath on the command line, invisible to
    `pkill -f generals` — answers that poll perfectly well, so every check runs against
    yesterday's build and reports `ALL CHECKS PASSED`. It cost a full clock-harness run to
    notice: `turnDeadlineMillis` was `undefined` in a payload the jar demonstrably had. The
    script now takes both ports **by pid** (`ss -ltnpH "sport = :$port"`), kills whatever is
    there, refuses to continue unless the listener on 8080 is the pid it launched, and
    checks that whatever holds 5173 really is `node .../node_modules/.bin/vite`. Same
    lesson as trap 3 in the browser-bug list below: a check that cannot tell its own process
    from a stranger's is not a check. If you boot the backend by hand, look at
    `ss -ltnp 'sport = :8080'` first.

19. **The difficulty is a choice the player makes, so it is in the view — and it is the only
    thing about the opponent that is.** `SeatDto` carries `difficulty`, null on a human's
    seat, and the frontend names the seat from it (`Computer (Hard)`). That is not a fog-of-war
    leak: the level came in on `POST /api/games/vs-bot?difficulty=` and the fog is about
    `Square.rank`, not about who is sitting opposite you. What *is* load-bearing is that the
    picker opens on `LEARNING`, because that is what `BotDifficulty.parse(null)` returns — a
    client defaulting to something else would quietly play a different game from the one a
    bare `curl` serves. `src/bot.ts` holds the three levels and their wording, kept out of
    the component file for the same reason `seats.ts` is (`only-export-components`).

    A vs-bot game is **not** a waiting-room game: `createAgainstBot` seats both sides and
    calls `beginPlacement()` before it returns, and the client calls `seat(result)` — the
    same path `create`, `join` and `matchmake` use — so there is nothing to add when a new
    way into a game appears. Any check that stops in `WAITING_FOR_OPPONENT` here is a check
    that would wait forever: `bot-e2e.mjs` asserts the placement screen appears and that no
    game code is ever shown.

20. **A deployment cannot be taken back, so the client asks the server whether it happened.**
    `GameStateDto.youPlaced` is per-viewer (`game.hasPlaced(viewer)`) and null nowhere — it is
    a `boolean`, so the settled state of the deploy button is driven by it and not by
    remembering that a click succeeded. Two things follow, and both were bugs first:
    - **the tick is green because a deployment cannot be undone.** `btn--done` gets its own
      `--done-bg`/`--done-hover` pair per theme rather than reusing `--ok`, because white on
      `--ok` is 3.06:1 on the dark theme and 4.30:1 on the light one. The dark hover goes
      *darker* (`#1a6b38`, 6.55:1) where the red primary's goes lighter: a plate carrying
      white text cannot afford to lighten.
    - **a refresh loses the local camp map**, so `Placement` derives the camp it draws rather
      than writing it into state in an effect: when the server holds the army and nothing is
      arranged locally, the pieces are read out of the view (your own pieces always carry
      their rank) instead of an empty camp appearing beside a settled button. It is also why
      `complete` is `total > 0 && placed === total` — a roster that had not arrived would
      otherwise count as a complete army of nothing.

    `Randomise`, `Clear` and the camp squares are all inert once the army is in. Leaving them
    live would let a player rearrange an army the server already holds and press a button the
    server refuses with `RED has already deployed` (400). In a game against the computer the
    green state is never seen — the game starts the instant the army lands — which is why
    `browser-e2e.mjs` stages red first and reads the button while blue is still deploying.

21. **Leaving is an explicit act, and it hands the win to whoever stayed.**
    `POST /api/games/{id}/resign` (token header, no body) is the only way a game ends early.
    A closed tab, a dropped socket or a restart does **not** forfeit: `TurnClock` keeps
    playing for whoever is still there, and the waiting room's `Cancel` stays client-only —
    there is nothing to concede to yet, so `Game.resign` refuses `WAITING_FOR_OPPONENT`
    (and an already-`FINISHED` game) with `IllegalStateException` → 409. Four things there
    are decisions, not implementation detail:
    - **the reason is built from the seat, never from the request.**
      `Game.resign(color)` calls `declareWinner(color.opponent(), color + " left the game")`,
      and that string is what both end screens and the log show.
    - **it is watcher four.** `GameManager.resign` locks the same `session.game()` monitor
      as every other mutation, takes `wasOver` *before* the game changes so
      `results.fileIfFinished` still decides, then calls `clock.onChange` (a finished game
      must not get a random expiry move) and `broadcaster.broadcast`. See trap 14.
    - **the client asks first.** `LeaveButton` is two steps — `Leave` swaps the plate for
      "Leave and lose the game?" with `Yes, leave` / `Stay` — and it sits in two places: the
      board header during play and beside the Ready button in `Placement`, because a game can
      be resigned while the army is still being arranged. The response is an ordinary
      `GameStateDto` with `status: FINISHED`, so `App.tsx` routes it to `GameScreen` and
      `GameOver` draws over the board; the session is kept on purpose and leaving is the
      overlay's *Back to lobby*, not the button that conceded.
    - **the second press must be harmless.** A `LeaveButton` still on screen after the game
      has already ended (by flag, by clock or by the opponent's own resignation) gets 409 and
      must leave the result alone — the `applyUnlessPushed` path in `GameContext.resign`
      keeps the refusal in the error slot rather than rolling anything back.

## Testing philosophy

Server is authoritative. Client's `applyQuietMove` only guesses moves onto **empty** squares; battles wait for push — client never keeps second copy of precedence rules. One move in flight at a time; refusal on `/user/queue/errors` rolls board back to saved pre-move state.

Add tests at the level that owns the rule: `rules.ts`/components for client logic, `GameSocketIntegrationTest` for real broker, `GameApiTest` for REST.

## Verified so far

- 203 backend tests, 134 frontend tests, type-check, lint, production build.
- LAN path verified from Windows over the Windows LAN address (192.168.254.53), through the
  portproxy relay: `/api/meta` 200, `/` 200, `/lobby` 200, `/ws/info` 200 (SockJS
  negotiation), `POST /api/games` 201 with a live gameId, dev server `:5173` 200, and a
  proxied `POST /api/games` with `Origin: http://172.24.223.0:5173` 201 — that last one is
  the check that would have caught a CORS wall on the dev-server path.
- Wire-level harness (`/tmp/opencode/e2e.mjs`, 38 checks; both seats named, and it asserts a nameless `POST /api/games` is 400) plays complete 44-move game over two real STOMP sessions; checks per-user pushes, fog of war across 11 battles, illegal move rejection. `/tmp/opencode/errs.mjs` (15 checks) covers `/user/queue/errors`.
- Browser harness (`tools/browser-e2e.mjs`, 138 checks) plays complete game in two real Chromium windows via UI. Run with `tools/browser-run.sh` (boots jar + dev server). First run `tools/install-browser.sh` (no sudo — Chromium + libs installed to `/tmp/opencode/browser`).
- Playing the computer re-verified at every layer: `GameApiTest` (4 tests — the seat says how
  hard it was asked to play, `LEARNING` is what a game with no level asked for gets, an
  unknown level is a 400 that lists the three, and the computer still has no name), 8
  `App.test.tsx` tests over the lobby card, and `tools/bot-e2e.mjs` (26 checks, run as
  `BOT_ONLY=1 tools/browser-run.sh`): all three levels offered, the picker on Hard, the hint
  following the picker, a straight line to deployment with no game code, the opponent shown
  as *Computer (Easy)*, the level the server is playing matching the one chosen, the computer
  deploying itself with its ranks as hidden as yours, red on a clock, the computer answering a
  move with nothing clicked and handing the turn back, the log naming BLUE as the mover, its
  move still revealing nothing, and four cards with a `<select>` not scrolling sideways at
  360px.
- The settled deploy button verified at every layer: `GameApiTest` (`youPlaced` is about the
  viewer — true for the player who deployed, false for the one who has not), 5
  `App.test.tsx` tests (green with a tick from the server's answer rather than the click,
  surviving a refresh with the camp rebuilt, no second deployment offered), and the browser
  harness: red deploys first, so the button is read while blue is still arranging — resolved
  `rgb(31, 122, 63)` under white text with a `.btn__tick` inside, 5.37:1 on the green in dark
  and 6.51:1 in light (each theme needs its own `--done-bg`; white on `--ok` would not do),
  Randomise and Clear inert, the hint saying the army cannot be changed, blue's button
  untouched, and all 21 pieces still on show. Reading that colour the instant the button
  appears caught a mid-transition blend of the red primary and the green — a value in neither
  palette — so the harness waits for two readings that agree.
- The clock re-verified at every layer, including a real browser: `TurnClockTest` (10 tests
  that wait on genuine expiry with a one-second clock — a legal random move is played, the
  turn hands on, a move made in time is never replaced, a stale expiry is turned away, the
  computer is never timed, and a timed-out move that takes the flag is filed exactly once),
  `GameApiTest` and `GameSocketIntegrationTest` on the deadline in the payload, and
  `tools/clock-e2e.mjs` (27 checks, run as `CLOCK_ONLY=1 tools/browser-run.sh`, which boots
  the jar with `--generals.turn-seconds=5` and leaves one window alone): the countdown reaches
  the number the server's own deadline implies, no clock is drawn in the waiting room or
  during placement, the board moves with nothing clicked, both logs say *ran out of time*,
  the clock says *your move* in one window and *their move* in the other at the same moment,
  and **closing a tab does not stall the game** — the clock plays for whoever left.
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
- Both themes re-verified in a real browser, because jsdom loads no stylesheet and has no
  `matchMedia`: 19 checks that read what Chromium resolved. `colorScheme: 'dark'` on both
  windows gives 15.5:1 body text; the switch gives 14.4:1 on `#eef1f4`; the primary button is
  white-on-red in both (4.56:1 / 5.43:1); a refresh comes back light without a dark frame; a
  fresh context that prefers light comes up light **with nothing written to
  `localStorage`** — the OS is being followed, not a default being stored. On the board: the
  empty square repaints, your own half stays distinct, the face-down hatch goes from a dark
  slab to a light one, and a face-up piece keeps a byte-identical gradient in both themes
  (sampling one is a trap — a hidden enemy piece also carries `piece--blue`, and its hatch is
  *supposed* to change). One player switching does not move the other's page. Screenshots:
  `/tmp/opencode/browser-lobby-light.png`, `/tmp/opencode/browser-board-light.png`.
- Fog of war re-verified after the battle-reveal fix, at every layer: wire harness sees
  `peak 0` enemy ranks across 11 battles and per-viewer `BattleDto` redaction; browser
  harness sees the overlay read `5-Star General vs Unknown` with the caption `your piece
   holds the square and an unidentified enemy piece is destroyed`, and `21 still hidden, 0
   revealed` for the losing player at the end.
- Leaving re-verified at every layer: `GameTest` (`leaving the game`, 4 — the opponent
  wins, a waiting room has nobody to hand a win to, resigning after the end is refused, a
  pending flag escape is cancelled with it), `GameApiTest` (4 — the win reaches both views,
  the clock stops, a waiting room and a foreign token are 409),
  `GameManagerLeaderboardTest` (4 — the resignation is filed under the right name, filed
  exactly once, refused in a waiting room, and losing to the computer counts as a loss),
  4 `App.test.tsx` tests (the two-step prompt, the refusal reported without rolling
  anything back, and the same way out from the deployment screen), and
  `tools/browser-e2e.mjs`: the confirm plate appears in a fresh game with nothing ended
  behind it, reads 4.56:1 on `rgb(208, 69, 62)` on its own, `Stay` backs out with
  deployment still up, the deployment screen still does not scroll sideways at 360px
  (`360px of content in 360px`), and a whole second game played to a resignation ends
  `Victory` for the player who stayed and `Defeat` for the one who left, with *BLUE left the
  game* on both screens, *Recorded as a win for Red.* on the ledger line, and both rows on
  `/api/leaderboard` reading `gamesPlayed: 2`.

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

6. **A theme is one attribute, and any colour written outside the palette block is a bug in
   waiting.** `styles.css` declares every colour as a custom property in `:root`, and
   `:root[data-theme='light']` replaces the set; `state/theme.ts` sets the attribute and
   `ThemeToggle` flips it. Four things there are decisions, not implementation detail:
   - **no colour is written in a rule further down.** The light theme started life with a
     `.field input` still painted `#10151a`, which is how a light page ends up with one dark
     box on it. If you need a colour, add a token.
   - **the team colours are deliberately not tokens.** A red piece is red because it is red on
     a board, not because of the time of day; recolouring them would change what the board
     means. `browser-e2e.mjs` checks a face-up piece keeps the identical gradient in both
     themes.
   - **the `--*-soft` tokens are text, and they flip direction.** In the dark theme they are
     pale tints; on white they must be dark, so light's `--red-soft` is `#a3271f`, not a pale
     pink. A `rgba()` wash under them is composited over `--surface` for the same reason.
   - **the primary button sets `color: #fff` itself.** It used to inherit `var(--text)`,
     which is 3.0:1 ink-on-red the moment the theme flips.

   `installTheme()` runs in `main.tsx` **before** the first render and the built CSS is a
   `<link>` in `<head>`, so the first paint is already correct — no inline script in
   `index.html` and no flash. Do not move that call into a component: it is a module-level
   side effect by design. `ThemeToggle` reads the theme back off `<html>` rather than keeping
   its own copy, so a test that sets `dataset.theme` before rendering sees a toggle that
   agrees with the page.

   **Chromium prefers light.** A browser harness that does not pin `colorScheme` is testing a
   light-mode app and will call it dark; both contexts in `browser-e2e.mjs` pass
   `colorScheme: 'dark'` and a third context with `'light'` and empty storage is what proves
   the OS is followed at all. Squares transition over 120ms, so a reading taken right after a
   switch catches a board mid-transition and proves nothing — `settled()` polls until the
   computed colour stops changing.

   The one deliberate contrast compromise: `.square__coord` (the row/column letters at 0.6rem)
   sits near 2.5–2.8:1 in both themes. It is incidental text that duplicates each square's
   `aria-label`, and 4.5:1 would mean bright lettering on all 72 squares. `--faint`
   (".log__empty" / ".chat__empty" — real sentences) was raised to clear 4.5:1 instead.

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
