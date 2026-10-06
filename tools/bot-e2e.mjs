// A browser harness for the one game the long harness cannot play: against the computer.
//
// Two browsers playing each other never touch POST /api/games/vs-bot, so the button that
// starts one, the difficulty picker it offers, and the placement screen that has to appear
// immediately (a vs-bot game that stopped in the waiting room would wait forever for an
// opponent that is already seated) are all code that no existing check runs. Then the point
// of the whole thing: the computer deploys itself, plays without being clicked, and gives the
// turn back — and none of that reveals a rank.

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
  const ctx = await browser.newContext({
    viewport: { width: 1400, height: 1000 },
    colorScheme: 'dark',
  })
  const p = await ctx.newPage()
  const consoleErrors = []
  p.on('pageerror', (e) => consoleErrors.push(`pageerror: ${e.message}`))
  p.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(`console: ${m.text()}`)
  })

  console.log('== the lobby offers the computer ==')
  await p.goto(FRONTEND)
  await p.getByLabel('Your name').fill('SoloRed')
  await p.getByRole('button', { name: 'Start playing' }).click()
  await p.locator('.lobby__cards').waitFor({ timeout: 15000 })

  const picker = p.getByLabel('Difficulty')
  check('there is a level to pick', (await picker.count()) === 1)
  const options = await picker.locator('option').allTextContents()
  check('all three levels are offered', JSON.stringify(options) === JSON.stringify(['Easy', 'Normal', 'Hard']), options.join(', '))
  check('the picker opens on the hardest level, which is the server\'s own default',
    (await picker.inputValue()) === 'LEARNING', await picker.inputValue())
  check('the level says what it does', /remembers the openings that have lost/i.test(await p.locator('.card__note').innerText()))

  // Four cards in the lobby grid, one of them carrying a select and a sentence: jsdom has no
  // layout engine, so only a real browser can say whether a phone now scrolls sideways.
  await p.setViewportSize({ width: 360, height: 720 })
  await p.waitForTimeout(200)
  const lobbyOverflow = await p.evaluate(() => {
    const shown = window.innerWidth
    // name the culprits: "372px in 360px" says nothing about which element to fix
    const wide = Array.from(document.querySelectorAll('body *'))
      .filter((el) => el.getBoundingClientRect().right > shown + 1)
      .slice(0, 8)
      .map(
        (el) =>
          `${el.tagName.toLowerCase()}.${(el.className || '').toString().split(' ')[0]}=${Math.round(el.getBoundingClientRect().width)}`,
      )
    return { scroll: document.documentElement.scrollWidth, shown, wide }
  })
  check(
    'four cards and a difficulty picker do not scroll sideways on a phone',
    lobbyOverflow.scroll <= lobbyOverflow.shown + 1,
    `${lobbyOverflow.scroll}px of content in ${lobbyOverflow.shown}px, over the edge: ${lobbyOverflow.wide.join(', ') || 'nothing'}`,
  )
  await p.screenshot({ path: '/tmp/opencode/browser-bot-lobby-phone.png', fullPage: true })
  await p.setViewportSize({ width: 1400, height: 1000 })

  await picker.selectOption('RANDOM')
  check('and the sentence follows the picker',
    /walk into anything/i.test(await p.locator('.card__note').innerText()),
    await p.locator('.card__note').innerText())
  await p.getByRole('button', { name: 'Start game' }).click()

  console.log('== the game it starts ==')
  // No waiting room: the opponent is already seated when the endpoint answers, so anything
  // that stopped in WAITING_FOR_OPPONENT would leave the player on a code screen forever.
  check('a game against the computer goes straight to deployment',
    await p.locator('.placement').waitFor({ timeout: 20000 }).then(() => true, () => false))
  check('it never asks for an opponent to join', (await p.locator('.waiting__code').count()) === 0)
  check('the opponent is named, and named by the level chosen',
    /against computer \(easy\)/i.test(await p.locator('.placement__hint').innerText()),
    (await p.locator('.placement__hint').innerText()).slice(0, 120))

  const started = await serverState(p)
  check('the level the player picked is the level the server is playing',
    started.seats[1].difficulty === 'RANDOM' && started.seats[1].bot === true,
    JSON.stringify(started.seats[1]))
  check('the computer has no name, because nothing files a result for it',
    started.seats[1].name === null)

  const blueDeployed = await waitFor(async () => {
    const state = await serverState(p)
    return state.board.filter((sq) => sq.owner === 'BLUE').length === 21
  }, 15000, 'the computer to deploy itself')
  check('the computer deploys itself, without being asked', blueDeployed)
  const afterDeploy = await serverState(p)
  check('and its deployment is as secret as yours',
    afterDeploy.board.every((sq) => sq.owner !== 'BLUE' || sq.rank === null))
  check('red has not deployed yet — that part is still yours to do',
    afterDeploy.board.every((sq) => sq.owner !== 'RED'))

  console.log('== playing it ==')
  await p.getByRole('button', { name: 'Randomise' }).click()
  await sleep(200)
  check('randomise fills exactly 21 of the 27 camp squares',
    (await p.locator('.progress__count').innerText()).trim() === '21 / 21',
    await p.locator('.progress__count').innerText())
  await p.getByRole('button', { name: 'Ready' }).first().click()

  await p.locator('[data-testid="board"]').waitFor({ timeout: 20000 })
  check('red is on the move once it has deployed', await waitFor(async () =>
    /your move/i.test(await p.locator('.banner').innerText().catch(() => '')), 20000, 'the board to open'))
  const live = await serverState(p)
  check('and red is put on a clock, exactly as against a person',
    live.currentPlayer === 'RED' && live.turnDeadlineMillis > Date.now(),
    `${live.currentPlayer}, deadline ${live.turnDeadlineMillis}`)

  // Move one piece the way a player would, then watch the computer answer without a click.
  const board = await p.evaluate(() =>
    Array.from(document.querySelectorAll('[data-testid="board"] .square')).map((el) => ({
      label: el.getAttribute('aria-label'),
      mine: el.classList.contains('square--mine'),
      occupied: !!el.querySelector('.piece'),
    })),
  )
  const piece = board.find((s) => s.mine && s.occupied)
  const coord = piece.label.split(',')[0]
  await square(p, coord).click()
  await sleep(150)
  const offered = await p.evaluate(() => !!document.querySelector('.square--target'))
  check('a piece can be picked up against the computer too', offered, coord)

  let moved = false
  if (offered) {
    const target = await p.evaluate(() =>
      document.querySelector('.square--target').getAttribute('aria-label').split(',')[0],
    )
    await square(p, target).click()
    moved = await waitFor(async () => {
      const state = await serverState(p)
      return state.turnNumber === 1 && state.currentPlayer === 'BLUE'
    }, 15000, 'the server to take red\'s move')
    check('the move went through and the turn passed to the computer', moved)
  }

  const answered = await waitFor(async () => {
    const state = await serverState(p)
    return state.turnNumber === 2 && state.currentPlayer === 'RED'
  }, 20000, 'the computer to answer')
  check('the computer answers by itself, with nothing clicked', answered)
  const after = await serverState(p)
  check('and hands the turn straight back',
    after.turnNumber === 2 && after.currentPlayer === 'RED',
    `turn ${after.turnNumber}, current ${after.currentPlayer}`)
  const lastLine = after.log[after.log.length - 1] || ''
  check("the log names the computer as the piece that moved", /^#\d+ BLUE /.test(lastLine), lastLine)
  // This view belongs to RED, so every RED square shows its own rank and every BLUE one
  // must show nothing: the computer's piece is the one that just moved.
  check("the computer's move revealed no rank of its own",
    after.board.every((sq) => sq.owner === 'RED' || sq.rank === null))
  check('the board on the screen agrees it is red\'s move again',
    /your move/i.test(await p.locator('.banner').innerText().catch(() => '')),
    await p.locator('.banner').innerText().catch(() => ''))

  check(
    'no console errors or uncaught exceptions',
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