// A browser harness for the one thing the long game harness cannot check: what happens when
// nobody moves. Both windows are left alone and the server's clock is set short
// (tools/browser-run.sh passes --generals.turn-seconds=3 for a CLOCK_ONLY run), so a turn
// falls due while this script is watching. Everything a browser could get wrong about that is
// here: the number reaching zero, the tone changing, the board moving without a click, the
// turn passing to the other player, and both windows being told the move was the clock's.
//
// This is deliberately a separate script from browser-e2e.mjs rather than a branch inside
// it. A full game takes three and a half minutes and forty-odd moves; waiting out a clock is
// the whole point, and a run that has to play a whole game first cannot afford a short clock.

import { createRequire } from 'node:module'

function loadPlaywright() {
  const require = createRequire(import.meta.url)
  for (const candidate of ['playwright', '/tmp/opencode/browser/node_modules/playwright']) {
    try {
      return require(candidate)
    } catch (e) {
      /* try the next one */
    }
  }
  throw new Error('playwright not found; run tools/install-browser.sh')
}

const FRONTEND = process.env.FRONTEND_URL || 'http://localhost:5173'
// must match the --generals.turn-seconds the jar was started with, or the waits here are wrong
const TURN_SECONDS = Number.parseInt(process.env.TURN_SECONDS || '3', 10)

let failures = 0
function check(name, ok, detail = '') {
  if (ok) {
    console.log(`  ok   ${name}`)
  } else {
    failures += 1
    console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`)
  }
}
const note = (m) => console.log(`      ${m}`)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const square = (page, coord) => page.locator(`.board__grid .square[aria-label^="${coord},"]`)

/** The countdown, as the page has drawn it. */
async function readClock(page) {
  const timer = page.locator('[role="timer"]')
  if ((await timer.count()) === 0) return null
  return {
    text: (await timer.locator('.clock__time').innerText()).trim(),
    tone: (await timer.getAttribute('class')) || '',
    label: (await timer.locator('.sr-only').innerText()).trim(),
  }
}

/** Waits for the turn banner to say whose move it is. */
async function waitBanner(page, wanted, timeoutMs = 20000) {
  return waitFor(async () => {
    const text = await page.locator('.banner').innerText().catch(() => '')
    return new RegExp(wanted, 'i').test(text)
  }, timeoutMs, `the banner to say ${wanted}`)
}

/** The server's own view of the game, read past the socket. */
async function serverState(page) {
  return page.evaluate(async () => {
    const session = JSON.parse(localStorage.getItem('generals.session'))
    const response = await fetch('/api/games/' + session.gameId, {
      headers: { 'X-Player-Token': session.token },
    })
    return response.json()
  })
}

async function waitFor(predicate, timeoutMs, what) {
  const giveUpAt = Date.now() + timeoutMs
  while (Date.now() < giveUpAt) {
    if (await predicate()) return true
    await sleep(100)
  }
  note(`gave up waiting for ${what}`)
  return false
}

async function main() {
  const { chromium } = loadPlaywright()
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] })
  const options = { viewport: { width: 1400, height: 1000 }, colorScheme: 'dark' }
  const ctx1 = await browser.newContext(options)
  const ctx2 = await browser.newContext(options)
  const p1 = await ctx1.newPage()
  const p2 = await ctx2.newPage()
  const consoleErrors = []
  for (const [who, p] of [['red', p1], ['blue', p2]]) {
    p.on('pageerror', (e) => consoleErrors.push(`${who} pageerror: ${e.message}`))
    p.on('console', (m) => {
      if (m.type() === 'error') consoleErrors.push(`${who} console: ${m.text()}`)
    })
  }

  console.log(`== clock only (turn-seconds=${TURN_SECONDS}) ==`)
  await p1.goto(FRONTEND)
  await p1.getByLabel('Your name').fill('ClockRed')
  await p1.getByRole('button', { name: 'Start playing' }).click()
  await p1.locator('.lobby__cards').waitFor({ timeout: 15000 })
  await p1.getByRole('button', { name: 'Create game' }).click()
  await p1.locator('.waiting__code code').waitFor({ timeout: 10000 })
  const gameId = (await p1.locator('.waiting__code code').innerText()).trim()

  // the clock is not there yet, and saying so is part of it: nothing is timed before the game
  check('no clock in the waiting room', (await readClock(p1)) === null)

  await p2.goto(FRONTEND)
  await p2.getByLabel('Your name').fill('ClockBlue')
  await p2.getByRole('button', { name: 'Start playing' }).click()
  await p2.locator('.lobby__cards').waitFor({ timeout: 15000 })
  await p2.getByLabel('Game code').fill(gameId)
  await p2.getByRole('button', { name: 'Join' }).click()

  console.log('== placement ==')
  await p1.locator('.placement').waitFor({ timeout: 20000 })
  check('no clock while armies are being placed', (await readClock(p1)) === null)
  await p1.getByRole('button', { name: 'Randomise' }).click()
  await sleep(200)
  check(
    'randomise fills exactly 21 of the 27 camp squares',
    (await p1.locator('.progress__count').innerText()).trim() === '21 / 21',
    await p1.locator('.progress__count').innerText(),
  )
  await p1.getByRole('button', { name: 'Ready' }).first().click()
  await p2.locator('.placement').waitFor({ timeout: 20000 })
  check('no clock while blue is still placing', (await readClock(p1)) === null)
  await p2.getByRole('button', { name: 'Randomise' }).click()
  await sleep(200)
  await p2.getByRole('button', { name: 'Ready' }).first().click()

  console.log('== the turn running out ==')
  await p1.locator('[data-testid="board"]').waitFor({ timeout: 20000 })
  await p2.locator('[data-testid="board"]').waitFor({ timeout: 20000 })
  // The board appears while armies are still being placed, so the turn banner is what says
  // the game is live — and with a five-second clock, waiting for the right thing matters.
  check('red is on the move once both sides have deployed', await waitBanner(p1, 'your move'))
  const started = await readClock(p1)
  const startedLeft = started === null ? -1 : Number.parseInt(started.text, 10)
  check('red is put on a clock',
    startedLeft > 0 && startedLeft <= TURN_SECONDS,
    started && started.text)
  check('red is told it is their move', /your move/i.test(started.label), started.label)
  check('blue sees the same clock', (await readClock(p2)).text === started.text)
  // The warning threshold is ten seconds, so a turn shorter than that is urgent from the
  // first tick. That is the point of the threshold being a duration and not a fraction.
  check('a turn shorter than the warning threshold reads as urgent from the start',
    started.tone.includes('clock--warn'), started.tone)

  const state0 = await serverState(p1)
  check('the server armed a deadline and said how long a turn is',
    state0.turnDeadlineMillis > Date.now() && state0.turnSeconds === TURN_SECONDS,
    `deadline ${state0.turnDeadlineMillis}, seconds ${state0.turnSeconds}`)

  // Watch it run down. This is the part a unit test fakes: the number on screen has to reach
  // zero without anything being pushed, because that is what a player watches.
  const warned = await waitFor(async () => {
    const c = await readClock(p1)
    return c !== null && Number.parseInt(c.text, 10) <= 2 && c.tone.includes('clock--warn')
  }, (TURN_SECONDS + 3) * 1000, 'the clock to warn')
  check('the clock warns before the turn is out', warned)

  // Zero is a race with the move that follows it: the server fires within milliseconds of the
  // deadline, so a poll every 100ms usually misses it. It is reported rather than asserted,
  // because "the expired tone is nearly never seen" is a fact about this app worth having in
  // the log, and the tone itself is covered by the component's own tests.
  const expired = await waitFor(async () => {
    const c = await readClock(p1)
    return c !== null && Number.parseInt(c.text, 10) === 0 && c.tone.includes('clock--expired')
  }, 1500, 'the clock to reach zero')
  note(expired ? 'the number reached zero in the urgent tone' : 'the move came before the screen could show zero')

  // Nobody clicked anything. The board moves anyway, and the turn passes.
  const moved = await waitFor(async () => {
    const state = await serverState(p1)
    return state.turnNumber > 0
  }, 10000, 'the server to move for red')
  check('the server moved a piece nobody moved', moved)
  const state1 = await serverState(p1)
  check("the move was red's, and it was one move",
    state1.turnNumber === 1 && state1.currentPlayer === 'BLUE',
    `turn ${state1.turnNumber}, current ${state1.currentPlayer}`)
  check("the log tells red the move was the clock's",
    state1.log.some((line) => /ran out of time/i.test(line)),
    state1.log.join(' | '))
  const blueView = await serverState(p2)
  check('the log tells blue the same thing',
    blueView.log.some((line) => /ran out of time/i.test(line)),
    blueView.log.join(' | '))
  check('the move leaked no rank either way',
    state1.board.every((sq) => sq.owner === 'RED' || sq.rank === null),
  )

  // Both windows, read in the same moment: the turn has passed to blue, so blue's clock says
  // the time is theirs and red's says it is not. Two windows reading differently is the
  // check; either one on its own would pass with the label hard-coded.
  check('blue is told it is their move', await waitBanner(p2, 'your move', 8000))
  const blues = await readClock(p2)
  const reds = await readClock(p1)
  check('the clock moves to the other player, and says the time is theirs',
    blues !== null && /your move/i.test(blues.label), blues && blues.label)
  check('and the waiting player is told the time is not theirs',
    reds !== null && /their move/i.test(reds.label), reds && reds.label)
  check('and it is a whole new turn, not what was left of the old one',
    Number.parseInt(blues.text, 10) >= TURN_SECONDS - 1,
    `${blues.text} left of ${TURN_SECONDS}s`)
  check('the board on the server is a different board from the one before the clock ran out',
    JSON.stringify(state1.board) !== JSON.stringify(state0.board),
  )

  console.log('== a move in time is kept ==')
  // Blue's clock is the one running now, so wait it out as well: this section is about a
  // player moving inside their own clock, which means waiting for the turn to come back.
  check('red is put on the clock again after blue let theirs run out',
    await waitBanner(p1, 'your move', (TURN_SECONDS + 8) * 1000))
  const before = await serverState(p1)
  const board = await p1.evaluate(() =>
    Array.from(document.querySelectorAll('[data-testid="board"] .square')).map((el) => ({
      label: el.getAttribute('aria-label'),
      mine: el.classList.contains('square--mine'),
      target: el.classList.contains('square--target'),
      occupied: !!el.querySelector('.piece'),
    })),
  )
  // find red's front rank from the DOM: a red square that is occupied
  const piece = board.find((s) => s.mine && s.occupied)
  const coord = piece.label.split(',')[0]
  await square(p1, coord).click()
  await sleep(150)
  const offered = await p1.evaluate(() => !!document.querySelector('.square--target'))
  check('red can still select and move inside the clock', offered, coord)
  if (offered) {
    const targetCoord = await p1.evaluate(
      () => document.querySelector('.square--target').getAttribute('aria-label').split(',')[0],
    )
    await square(p1, targetCoord).click()
    await sleep(400)
    const after = await serverState(p1)
    check('the move went through and was not the clock',
      after.turnNumber === before.turnNumber + 1,
      `turn ${after.turnNumber}, was ${before.turnNumber}`)
    check('and the newest log line is not marked as a clock move',
      !/ran out of time/i.test(after.log[after.log.length - 1] || ''),
      after.log[after.log.length - 1])
  }

  console.log('== leaving ==')
  // The tab closed, which is the reason the clock is on the server at all: the game carries
  // on without the browser that was going to play it.
  await p1.close()
  const carried = await waitFor(async () => {
    const state = await serverState(p2)
    return state.turnNumber >= 2 && state.currentPlayer === 'RED'
  }, (TURN_SECONDS + 8) * 1000, "blue's clock to move for red")
  check('a closed tab does not stall the game: the clock plays for whoever left', carried)
  const finalState = await serverState(p2)
  check('the game is still running after the browser went away',
    finalState.status === 'IN_PROGRESS', finalState.status)

  check(
    'no console errors or uncaught exceptions in either page',
    consoleErrors.length === 0,
    consoleErrors.slice(0, 4).join(' | '),
  )

  await browser.close()
  console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : `${failures} CHECK(S) FAILED`}`)
  process.exit(failures === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error('script error:', e.stack || e.message)
  process.exit(1)
})