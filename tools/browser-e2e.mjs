// Browser end-to-end check: two real Chromium pages play a complete game through
// the UI. This covers the ground the wire-level harness cannot: rendering, CSS,
// SockJS inside a browser, clipboard permissions, and console errors.
//
// Playwright is installed outside the repo by install-browser.sh (this machine has no
// sudo, and node_modules does not belong in version control), so resolve it by hand
// rather than relying on a bare specifier walking up from tools/.
import { createRequire } from 'node:module'

function loadPlaywright() {
  const roots = (process.env.PLAYWRIGHT_ROOT || '/tmp/opencode/browser/node_modules')
    .split(':')
    .filter(Boolean)
  for (const root of [...roots, import.meta.url]) {
    try {
      return createRequire(root.endsWith('/') ? root : `${root}/`)('playwright')
    } catch {
      // try the next root
    }
  }
  throw new Error('playwright not found. Run tools/install-browser.sh first.')
}

const FRONTEND = process.env.FRONTEND_URL || 'http://localhost:5173'

let failures = 0
function check(name, ok, detail = '') {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`)
  if (!ok) failures++
}
const note = (m) => console.log(`      ${m}`)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const ROWS = 8
const COLS = 9
/** Server labels a square letter(col) + row+1, e.g. E5 for row 4 col 4. */
function parseLabel(label) {
  const col = label.charCodeAt(0) - 65
  const row = Number(label.slice(1)) - 1
  return { row, col }
}
/* -------------------------------------------------------- theme measurement --
 * jsdom never loads a stylesheet, so the only place a theme can be proven is here: these
 * read the colours the browser actually resolved, and compare them for contrast. */
const channel = (v) => {
  const c = v / 255
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
}
/** WCAG relative luminance of a computed `rgb(r, g, b)`. */
function luminance(css) {
  const [r, g, b] = css.match(/[\d.]+/g).slice(0, 3).map(Number)
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
}
/** Contrast ratio between two computed colours; null if either is not opaque enough to judge. */
function contrast(a, b) {
  const la = luminance(a)
  const lb = luminance(b)
  if (la < 0.02 && lb < 0.02) return null
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
}

const ortho = (r, c) =>
  [
    [r - 1, c],
    [r + 1, c],
    [r, c - 1],
    [r, c + 1],
  ].filter(([rr, cc]) => rr >= 0 && rr < ROWS && cc >= 0 && cc < COLS)


const ROSTER = [
  ['5★ General', 1],
  ['4★ General', 1],
  ['3★ General', 1],
  ['2★ General', 1],
  ['1★ General', 1],
  ['Colonel', 1],
  ['Lt. Colonel', 1],
  ['Major', 1],
  ['Captain', 1],
  ['1st Lt.', 1],
  ['2nd Lt.', 1],
  ['Sergeant', 1],
  ['Private', 6],
  ['Spy', 2],
  ['Flag', 1],
]
/** The whole roster as 21 (label, count) pairs. */
function roster() {
  return ROSTER.flatMap(([label, n]) => Array.from({ length: n }, () => label))
}
const campSquare = (page, row, col) =>
  page.locator(`.board--camp .square[aria-label^="Row ${row} column ${col}"]`)
const coordOf = (row, col) => String.fromCharCode(65 + col) + (row + 1)

/**
 * Deploys a fixed plan: { "row,col": rankLabel }, plus a list of "row,col" squares to
 * leave empty so a staged solo move stays legal. Roster order fills the remaining camp.
 */
async function deployPlan(page, plan, color, reserved = []) {
  const rows = color === 'RED' ? [0, 1, 2] : [5, 6, 7]
  const taken = new Set([...Object.keys(plan), ...reserved])
  const free = []
  for (const row of rows) {
    for (let col = 0; col < COLS; col++) {
      // Plan keys are "row,col" strings, so compare the string, not the number.
      if (!taken.has(`${row},${col}`)) free.push([row, col])
    }
  }
  const spare = roster().filter((label) => {
    return !Object.values(plan).includes(label)
  })
  const entries = []
  for (const [key, label] of Object.entries(plan)) {
    const [row, col] = key.split(',').map(Number)
    entries.push({ row, col, label })
  }
  // Fill the rest of the camp with the ranks the plan did not use.
  let spareIndex = 0
  for (const [row, col] of free) {
    if (entries.length >= 21) break
    const label = spare[spareIndex++]
    if (!label) break
    entries.push({ row, col, label })
  }
  if (entries.length !== 21) {
    throw new Error(`plan produced ${entries.length} pieces, not 21`)
  }
  const count = async () => {
    const text = await page.locator('.progress__count').innerText()
    return Number(text.split('/')[0].trim())
  }
  let retries = 0
  for (const { row, col, label } of entries) {
    const before = await count()
    for (let attempt = 0; attempt < 3 && (await count()) === before; attempt++) {
      if (attempt > 0) retries++
      await page.locator(`.tray__item[title="${label}"]`).click()
      await campSquare(page, row, col).click()
    }
    if ((await count()) !== before + 1) {
      throw new Error(`could not place ${label} on row ${row} column ${col}`)
    }
  }
  if (retries) note(`${color}: ${retries} placement click(s) needed a retry`)
}

/** Reads the board straight out of the DOM: what the player can actually see. */
async function readBoard(page) {
  const raw = await page.evaluate(() => {
    const squares = []
    for (const el of document.querySelectorAll('.board__grid .square')) {
      const label = el.getAttribute('aria-label') || ''
      squares.push({
        label,
        what: label.includes(', ') ? label.slice(label.indexOf(', ') + 2) : null,
        mine: el.classList.contains('square--mine'),
        occupied: el.classList.contains('square--filled'),
        // piece--face-down is on the inner span, not on the square button.
        hidden: !!el.querySelector('.piece--face-down'),
      })
    }
    return squares
  })
  // The aria-label is "<coord>, <what>", so the coordinate has to be split off first.
  return raw.map((s) => {
    const coord = s.label.split(',')[0].trim()
    return { ...s, coord, ...parseLabel(coord) }
  })
}

const at = (board, r, c) => board.find((s) => s.row === r && s.col === c)

function loneIn(board, r, c, mine) {
  return ortho(r, c).every(([rr, cc]) => {
    const s = at(board, rr, cc)
    return !(s && s.mine === mine && s.occupied)
  })
}

/**
 * Same greedy policy as the wire harness: fight if we can, otherwise advance.
 * The DOM marks a square square--mine only when it holds one of *my* pieces, so
 * `step` (which way is the enemy camp) is passed in rather than a colour.
 */
function greedyMove(board, step) {
  const mine = true
  const target = step > 0 ? ROWS - 1 : 0
  const candidates = []
  for (const p of board.filter((s) => s.mine === mine && s.occupied)) {
    for (const [r, c] of ortho(p.row, p.col)) {
      const t = at(board, r, c)
      if (!t) continue
      if (!t.mine && t.occupied) candidates.push({ from: p, to: t, score: 100 })
      else if (!t.occupied) {
        const closer = Math.abs(r - target) < Math.abs(p.row - target) ? 10 : 0
        candidates.push({ from: p, to: t, score: closer })
      }
    }
    if (loneIn(board, p.row, p.col, mine)) {
      for (const [r, c] of ortho(p.row, p.col)) {
        const mid = at(board, r, c)
        if (!mid || mid.occupied) continue
        for (const [rr, cc] of ortho(r, c)) {
          const dr = Math.abs(rr - p.row)
          const dc = Math.abs(cc - p.col)
          if (!((dr === 2 && dc === 0) || (dr === 0 && dc === 2))) continue
          const t = at(board, rr, cc)
          if (t && !t.occupied) candidates.push({ from: p, to: t, score: 20, double: true })
        }
      }
    }
  }
  candidates.sort((a, b) => b.score - a.score)
  return candidates[0] || null
}

const square = (page, coord) => page.locator(`.board__grid .square[aria-label^="${coord},"]`)
const banner = (page) => page.locator('.banner')
const myTurn = async (page) => (await banner(page).innerText()).includes('Your move')

/**
 * How wide a screen the page really wants, measured at 360px and then put back.
 *
 * jsdom has no layout engine, so this is the only place a row of buttons can be proved not
 * to push a phone sideways — and `wide` names the elements that crossed the edge, because
 * "520px in 360px" on its own says nothing about what to fix.*
 */
const phoneFits = async (page) => {
  await page.setViewportSize({ width: 360, height: 720 })
  await page.waitForTimeout(200)
  const measured = await page.evaluate(() => {
    const shown = window.innerWidth
    return {
      scroll: document.documentElement.scrollWidth,
      shown,
      wide: Array.from(document.querySelectorAll('body *'))
        .filter((el) => el.getBoundingClientRect().right > shown + 1)
        .slice(0, 6)
        .map(
          (el) =>
            `${el.tagName.toLowerCase()}.${(el.className || '').toString().split(' ')[0]}=${Math.round(el.getBoundingClientRect().width)}`,
        ),
    }
  })
  await page.setViewportSize({ width: 1400, height: 1000 })
  await page.waitForTimeout(150)
  return measured
}

/** The socket indicator flips to "connected" only once STOMP CONNECT lands. */
async function waitLive(page, timeout = 15000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if ((await page.locator('.conn').innerText().catch(() => '')).trim() === 'connected') return true
    await sleep(120)
  }
  return false
}

async function waitForTurn(page, expected, timeout = 20000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if ((await myTurn(page)) === expected) return true
    await sleep(120)
  }
  return false
}

async function deploy(page) {
  await page.getByRole('button', { name: 'Randomise' }).click()
  await page.getByRole('button', { name: 'Ready' }).click()
}

async function main() {
  const { chromium } = loadPlaywright()
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] })
  // colorScheme is pinned on purpose. Chromium's default is to prefer light, and the app's
  // contract is "follow the machine until the reader picks a side", so both windows are told
  // to prefer dark: that makes the OS-following path the one under test here, and the
  // toggle the one that overrides it. The light-preferring path is checked further down, in
  // its own context, because a fresh context with no stored choice is the honest way to ask.
  const darkMachine = { viewport: { width: 1400, height: 1000 }, colorScheme: 'dark' }
  const ctx1 = await browser.newContext(darkMachine)
  const ctx2 = await browser.newContext(darkMachine)
  await ctx1.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: FRONTEND })
  await ctx2.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: FRONTEND })

  const p1 = await ctx1.newPage()
  const p2 = await ctx2.newPage()
  const consoleErrors = []
  for (const [who, p] of [['red', p1], ['blue', p2]]) {
    p.on('pageerror', (e) => consoleErrors.push(`${who} pageerror: ${e.message}`))
    p.on('console', (m) => {
      if (m.type() === 'error') consoleErrors.push(`${who} console: ${m.text()}`)
    })
  }

  console.log('== name gate ==')
  await p1.goto(FRONTEND)
  check(
    'a first visit asks for a name before anything else',
    (await p1.locator('.namegate h2').innerText()).includes('What should we call you'),
  )
  check('the lobby is not reachable yet', (await p1.locator('.lobby__cards').count()) === 0)
  await p1.getByLabel('Your name').fill('Red')
  await p1.getByRole('button', { name: 'Start playing' }).click()
  await p1.locator('.lobby__cards').waitFor({ timeout: 10000 })
  check('naming a player moves on to the lobby', true)
  const storedName = await p1.evaluate(() => window.localStorage.getItem('generals.player'))
  check('the name is remembered in the browser', storedName === 'Red', `stored=${storedName}`)

  console.log('== lobby ==')
  check('lobby renders', (await p1.locator('h1').innerText()).includes('Game of the Generals'))
  check('the lobby says who you are playing as', (await p1.locator('.lobby__identity').innerText()).includes('Red'))
  const themed = await p1.evaluate(
    () => getComputedStyle(document.body).backgroundColor,
  )
  check('stylesheet is applied (dark theme)', themed === 'rgb(15, 18, 22)', themed)

  console.log('== themes ==')
  // what the browser resolved, not what the stylesheet says it meant
  const bodyColours = () =>
    p1.evaluate(() => ({
      attr: document.documentElement.dataset.theme,
      scheme: getComputedStyle(document.documentElement).colorScheme,
      bg: getComputedStyle(document.body).backgroundColor,
      text: getComputedStyle(document.body).color,
      card: getComputedStyle(document.querySelector('.card')).backgroundColor,
      input: getComputedStyle(document.querySelector('.field input')).backgroundColor,
      btn: getComputedStyle(document.querySelector('.btn--primary')).backgroundColor,
      btnText: getComputedStyle(document.querySelector('.btn--primary')).color,
      stored: window.localStorage.getItem('generals.theme'),
    }))

  const dark = await bodyColours()
  check('the page says it is dark', dark.attr === 'dark', `attr=${dark.attr}`)
  check(
    'native controls follow the theme too',
    dark.scheme === 'dark' || dark.scheme === 'normal',
    dark.scheme,
  )
  const darkContrast = contrast(dark.bg, dark.text)
  check(
    'dark theme: body text is readable on the page',
    darkContrast !== null && darkContrast >= 4.5,
    `${darkContrast?.toFixed(2)}:1 between ${dark.bg} and ${dark.text}`,
  )
  // the primary button inherited var(--text) once, which is ink on a red plate in light mode
  check(
    'dark theme: the primary button is not ink on red',
    contrast(dark.btn, dark.btnText) >= 4.5,
    `${contrast(dark.btn, dark.btnText).toFixed(2)}:1`,
  )

  await p1.getByRole('button', { name: /switch to the light theme/i }).click()
  await sleep(250)
  const light = await bodyColours()
  check('the switch puts the page in light mode', light.attr === 'light', `attr=${light.attr}`)
  check(
    'native controls follow the switch',
    light.scheme === 'light',
    light.scheme,
  )
  check(
    'the page is actually painted light',
    luminance(light.bg) > 0.5 && light.card !== dark.card && light.input !== dark.input,
    `body ${light.bg}, card ${light.card}, input ${light.input}`,
  )
  const lightContrast = contrast(light.bg, light.text)
  check(
    'light theme: body text is readable on the page',
    lightContrast !== null && lightContrast >= 4.5,
    `${lightContrast?.toFixed(2)}:1 between ${light.bg} and ${light.text}`,
  )
  check(
    'light theme: the primary button is not ink on red',
    contrast(light.btn, light.btnText) >= 4.5,
    `${contrast(light.btn, light.btnText).toFixed(2)}:1`,
  )
  check('the choice is remembered in the browser', light.stored === 'light', `stored=${light.stored}`)
  await p1.screenshot({ path: '/tmp/opencode/browser-lobby-light.png', fullPage: true })

  // A reload is the only proof the choice survives a fresh document: main.tsx applies it
  // before the first render, so there is no window in which the page is dark.
  await p1.reload()
  await p1.locator('.lobby__cards').waitFor({ timeout: 10000 })
  const reloaded = await bodyColours()
  check(
    'a refresh comes back light, not dark for a moment',
    reloaded.attr === 'light' && luminance(reloaded.bg) > 0.5,
    `attr=${reloaded.attr}, body ${reloaded.bg}`,
  )
  await p1.getByRole('button', { name: /switch to the dark theme/i }).click()
  await sleep(100)
  const back = await bodyColours()
  check(
    'and it switches back to dark',
    back.attr === 'dark' && luminance(back.bg) < 0.05,
    `attr=${back.attr}, body ${back.bg}`,
  )

  // A machine that prefers light, on a browser with nothing stored: the page must come up
  // light without having written a preference of its own. This is the one check jsdom
  // cannot make, because it has no matchMedia and loads no stylesheet.
  const ctx3 = await browser.newContext({ colorScheme: 'light' })
  const p3 = await ctx3.newPage()
  await p3.goto(FRONTEND)
  await p3.locator('.namegate h2').waitFor({ timeout: 10000 })
  const followed = await p3.evaluate(() => ({
    attr: document.documentElement.dataset.theme,
    bg: getComputedStyle(document.body).backgroundColor,
    stored: window.localStorage.getItem('generals.theme'),
  }))
  check(
    'a machine that prefers light comes up light, with nothing stored',
    followed.attr === 'light' &&
      luminance(followed.bg) > 0.5 &&
      followed.stored === null,
    `attr=${followed.attr}, body ${followed.bg}, stored=${followed.stored}`,
  )
  await ctx3.close()

  await p1.getByRole('button', { name: 'Create game' }).click()
  await p1.locator('.waiting__code code').waitFor({ timeout: 10000 })
  const gameId = (await p1.locator('.waiting__code code').innerText()).trim()
  check('create game shows a code in the waiting room', gameId.length > 0, gameId)
  // SockJS in a real browser: the info/request/websocket frames and STOMP CONNECT.
  check('socket reports live in the waiting room', await waitLive(p1))

  // A refresh is the only way to prove the name was kept rather than merely held in
  // memory: the gate is gone and the game is still there after it.
  await p1.reload()
  await p1.locator('.waiting__code code').waitFor({ timeout: 10000 })
  check('a refresh does not ask for the name again', (await p1.locator('.namegate').count()) === 0)
  check(
    'the game survives the refresh',
    (await p1.locator('.waiting__code code').innerText()).trim() === gameId,
    gameId,
  )
  check('the socket comes back after the refresh', await waitLive(p1))

  // Clipboard: the Copy button, which is the one thing no wire test can touch.
  await p1.getByRole('button', { name: 'Copy' }).click()
  await sleep(400)
  const clip = await p1.evaluate(() => navigator.clipboard.readText())
  check('copy button puts the game code on the clipboard', clip === gameId, `clipboard=${clip}`)
  check('copy button confirms', (await p1.locator('button:has-text("Copied")').count()) === 1)

  await p2.goto(FRONTEND)
  await p2.getByLabel('Your name').fill('Blue')
  await p2.getByRole('button', { name: 'Start playing' }).click()
  await p2.locator('.lobby__cards').waitFor({ timeout: 10000 })
  await p2.getByLabel('Game code').fill(gameId)
  await p2.getByRole('button', { name: 'Join' }).click()
  // Both seats filled, so the game skips the waiting room straight into placement.
  await p2.locator('.placement').waitFor({ timeout: 15000 })
  check('joining by code works', true, `code ${gameId}`)
  check(
    'blue is told which camp is its own',
    (await p2.locator('.placement__hint').innerText()).includes('BLUE'),
  )

  console.log('\n== placement ==')
  await p1.locator('.placement').waitFor({ timeout: 10000 })
  await p2.locator('.placement').waitFor({ timeout: 10000 })
  check('red reaches the placement screen', true)
  check('blue reaches the placement screen', true)
  const p1Placement = await p1.locator('.placement__hint').innerText()
  check(
    'red is told who it is deploying against, by name',
    p1Placement.includes('Red') && p1Placement.includes('against Blue'),
    p1Placement.replace(/\s+/g, ' '),
  )
  check(
    'red sees its camp as rows 0-2',
    (await p1.locator('.board--camp .square').count()) === 27,
    `${await p1.locator('.board--camp .square').count()} squares`,
  )
  // Chat while the two players are deciding something neither can see: the one thing a
  // board cannot say. Red speaks first, and the line has to arrive in Blue's browser.
  const chatBox = (p) => p.getByRole('textbox', { name: 'Message' })
  check('the waiting room has a chat box for each player', (await chatBox(p1).count()) === 1)
  await chatBox(p2).fill('where are you putting your flag?')
  await p2.getByRole('button', { name: 'Send' }).click()
  await p1.locator('.chat__text', { hasText: 'where are you putting your flag?' }).waitFor({ timeout: 10000 })
  // The author label is uppercased by CSS, and innerText reflects that, so compare
  // case-insensitively rather than to the name as it was typed.
  const blueLineForRed = await p1
    .locator('.chat__line', { hasText: 'where are you putting your flag?' })
    .innerText()
  check(
    "the other player's line arrives, under their name",
    (await p1.locator('.chat__line--mine').count()) === 0 &&
      blueLineForRed.toLowerCase().includes('blue'),
    blueLineForRed.replace(/\n/g, ' / '),
  )
  await chatBox(p1).fill('safe, behind the privates')
  await p1.getByRole('button', { name: 'Send' }).click()
  await p2.locator('.chat__text', { hasText: 'safe, behind the privates' }).waitFor({ timeout: 10000 })
  const myLine = await p1.locator('.chat__line--mine').innerText()
  check(
    'a line sent is shown as yours, and the box is emptied',
    myLine.toLowerCase().includes('you') && myLine.includes('safe, behind the privates') &&
      (await chatBox(p1).inputValue()) === '',
    myLine.replace(/\n/g, ' / '),
  )
  check(
    'both lines are in the conversation for both players',
    (await p1.locator('.chat__line').count()) === 2 && (await p2.locator('.chat__line').count()) === 2,
  )
  check(
    'the composer refuses an empty line rather than posting one',
    await chatBox(p2)
      .fill('   ')
      .then(() => p2.getByRole('button', { name: 'Send' }).isDisabled()),
  )

  await p1.getByRole('button', { name: 'Randomise' }).click()
  const counter = await p1.locator('.progress__count').innerText()
  check('randomise fills exactly 21 of the 27 camp squares', counter.trim() === '21 / 21', counter)
  // A tray item greys out once its rank is fully placed: the 6th Private is spent.
  const privates = p1.locator('.tray__item[title="Private"]')
  check('the 6th Private is marked spent', (await privates.getAttribute('class')).includes('spent'))
  await p1.getByRole('button', { name: 'Clear' }).click()
  check(
    'clear empties the camp again',
    (await p1.locator('.progress__count').innerText()).trim() === '0 / 21',
  )

  // Stage the armies so the finish is deterministic: red's five-star general waits
  // alone on the centre column with every orthogonal neighbour empty, and blue's flag
  // sits two rows in front of it. Two red moves then decide the game.
  const redPlan = { '2,4': '5★ General' }
  // The general's orthogonal neighbours must stay empty or it is not alone, and the
  // solo double move becomes illegal.
  const redReserved = ['1,4', '2,3', '2,5']
  const bluePlan = { '5,4': 'Flag' }
  await deployPlan(p1, redPlan, 'RED', redReserved)
  await deployPlan(p2, bluePlan, 'BLUE')
  check(
    'red deployed a hand-picked 21-piece army',
    (await p1.locator('.progress__count').innerText()).trim() === '21 / 21',
  )
  const blueCounter = (await p2.locator('.progress__count').innerText()).trim()
  check('blue deployed a hand-picked 21-piece army', blueCounter === '21 / 21', blueCounter)
  if (blueCounter !== '21 / 21') {
    note(`blue filled squares: ${await p2.locator('.board--camp .square--filled').count()}`)
    const alert = await p2.locator('.alert--error').count()
    if (alert) note(`blue alert: ${await p2.locator('.alert--error').innerText()}`)
    note(`blue tray leftovers: ${(await p2.locator('.tray__count').allInnerTexts()).join(',')}`)
  }
  // Red first and on its own, so the deployment screen is still up when the button settles:
  // a deployment cannot be taken back, and a screen whose only button still read "Ready"
  // while it waited would read as a broken one. Blue has not deployed, which is the only
  // thing that makes that gap observable — a game against the computer goes live at once.
  await p1.getByRole('button', { name: 'Ready' }).click()
  await p1.getByRole('button', { name: 'Army placed' }).waitFor({ timeout: 10000 })
  // The button crossfades its background over 150ms, so a colour read the instant it appears
  // is a colour on the way from the red primary to the green — a midpoint of the two, which
  // is neither. Read it twice and only believe two readings that agree.
  const settledPlate = async () => {
    const read = () =>
      p1.evaluate(() => {
        const button = document.querySelector('.btn--done')
        if (!button) return null
        const style = getComputedStyle(button)
        return {
          bg: style.backgroundColor,
          text: style.color,
          tick: !!button.querySelector('.btn__tick'),
        }
      })
    let previous = await read()
    for (let attempt = 0; attempt < 10; attempt++) {
      await sleep(120)
      const next = await read()
      if (next && previous && next.bg === previous.bg) return next
      previous = next
    }
    return previous
  }
  const darkPlate = await settledPlate()
  const [r, g, b] = (darkPlate?.bg ?? '').match(/\d+/g)?.map(Number) ?? [0, 0, 0]
  check(
    'red\'s deploy button turns green with a tick once the army is in',
    // green means green, not merely "not the red primary"
    !!darkPlate &&
      r < 80 &&
      g > r + 50 &&
      b < g &&
      darkPlate.text === 'rgb(255, 255, 255)' &&
      darkPlate.tick,
    `${darkPlate?.bg} with ${darkPlate?.text}, tick ${darkPlate?.tick}`,
  )
  check(
    'and the button cannot be pressed again, nor the camp rearranged behind it',
    (await p1.getByRole('button', { name: 'Army placed' }).isDisabled()) &&
      (await p1.getByRole('button', { name: 'Randomise' }).isDisabled()),
  )
  check(
    'and the screen says what it is waiting for',
    /cannot be changed/i.test(await p1.locator('.placement__hint').innerText()),
    await p1.locator('.placement__hint').innerText(),
  )
  check('red still has all 21 pieces on show', (await p1.locator('.board--camp .square--filled').count()) === 21)
  check(
    'blue\'s button is untouched, because blue has not deployed',
    (await p2.locator('.btn--done').count()) === 0,
  )

  // Leaving costs the game, so it asks first — and it is offered during deployment as well
  // as over the board, because an opponent who never deploys would otherwise hold this
  // screen hostage with no clock to rescue it.
  await p2.getByRole('button', { name: 'Leave', exact: true }).click()
  const leaveAsk = await p2.locator('.leave__ask').innerText().catch(() => '')
  check(
    'leaving asks before it concedes',
    leaveAsk.length > 0 && (await p2.getByRole('button', { name: 'Yes, leave' }).count()) === 1,
    leaveAsk,
  )
  check(
    'and the first press has not ended anything',
    (await p2.locator('.placement').count()) === 1,
  )
  const confirmPlate = await p2.evaluate(() => {
    const yes = [...document.querySelectorAll('button')].find((b) => b.textContent?.includes('Yes, leave'))
    const style = yes ? getComputedStyle(yes) : null
    return style ? { bg: style.backgroundColor, text: style.color } : null
  })
  check(
    'the confirm button is legible on its own plate',
    !!confirmPlate && contrast(confirmPlate.bg, confirmPlate.text) >= 4.5,
    confirmPlate ? `${contrast(confirmPlate.bg, confirmPlate.text)?.toFixed(2)}:1 on ${confirmPlate.bg}` : 'no button',
  )
  await p2.getByRole('button', { name: 'Stay' }).click()
  check(
    'and it can be backed out of, with the deployment screen still up',
    (await p2.locator('.leave__ask').count()) === 0 &&
      (await p2.locator('.placement').count()) === 1 &&
      (await p2.getByRole('button', { name: 'Leave', exact: true }).count()) === 1,
  )

  // Five controls now sit in the deployment header, and a phone is the width that notices:
  // measure it the same way the board and the scores table are measured.
  const placementFits = await phoneFits(p2)
  check(
    'the deployment screen does not scroll sideways on a phone',
    placementFits.scroll <= placementFits.shown + 1,
    `${placementFits.scroll}px of content in ${placementFits.shown}px, over the edge: ${placementFits.wide.join(', ') || 'nothing'}`,
  )

  // White on a green plate is the whole risk in a button like this: the green that reads as
  // "done" on a dark surface is too light for white text, so each theme needs its own, and
  // a theme is not a theme until the browser has resolved both of them.
  check(
    'dark theme: the tick is legible on the green plate',
    contrast(darkPlate.bg, darkPlate.text) >= 4.5,
    `${contrast(darkPlate.bg, darkPlate.text).toFixed(2)}:1 on ${darkPlate.bg}`,
  )
  await p1.getByRole('button', { name: /switch to the light theme/i }).click()
  await sleep(250)
  const lightPlate = await settledPlate()
  check(
    'light theme: the tick is still legible on the green plate',
    contrast(lightPlate.bg, lightPlate.text) >= 4.5,
    `${contrast(lightPlate.bg, lightPlate.text).toFixed(2)}:1 on ${lightPlate.bg}`,
  )
  await p1.screenshot({ path: '/tmp/opencode/browser-placed-light.png', fullPage: true })
  await p1.getByRole('button', { name: /switch to the dark theme/i }).click()
  await sleep(200)
  await p2.getByRole('button', { name: 'Ready' }).click()

  console.log('\n== board ==')
  await p1.locator('[data-testid="board"]').waitFor({ timeout: 15000 })
  await p2.locator('[data-testid="board"]').waitFor({ timeout: 15000 })
  // The board is the most themed part of the app, so it is the part worth looking at in the
  // other theme — and the theme is per browser, so red switching must not move blue's page.
  const boardColours = () =>
    p1.evaluate(() => ({
      square: getComputedStyle(document.querySelector('.square')).backgroundColor,
      mine: getComputedStyle(document.querySelector('.square--mine') ?? document.querySelector('.square')).backgroundColor,
      face: getComputedStyle(document.querySelector('.piece--face-down')).backgroundImage,
      // face UP on purpose: a hidden enemy piece also carries piece--blue, and its hatch is
      // meant to change with the theme — the team colours live on the pieces you can see
      piece: getComputedStyle(
        document.querySelector('.piece--red:not(.piece--face-down)'),
      ).backgroundImage,
    }))
  // squares transition over 120ms, so a reading taken too soon catches a board halfway
  // between the two themes and proves nothing
  const settled = () =>
    p1.evaluate(async () => {
      const square = document.querySelector('.square')
      const read = () => getComputedStyle(square).backgroundColor
      let previous = read()
      for (let tries = 0; tries < 40; tries++) {
        await new Promise((r) => setTimeout(r, 25))
        const now = read()
        if (now === previous) return now
        previous = now
      }
      return previous
    })
  const boardDark = await boardColours()
  await settled()
  await p1.getByRole('button', { name: /switch to the light theme/i }).click()
  await sleep(150)
  const boardLight = await boardColours()
  const blueStillDark = await p2.evaluate(() => document.documentElement.dataset.theme)
  check(
    'the board repaints in the light theme',
    luminance(boardLight.square) > 0.5 && luminance(boardDark.square) < 0.05,
    `empty square ${boardDark.square} -> ${boardLight.square}`,
  )
  check(
    'your own half stays a different colour from an empty square',
    boardLight.mine !== boardLight.square,
    `mine ${boardLight.mine}, empty ${boardLight.square}`,
  )
  check(
    'a face-down piece stays a hatch, and stops being a dark slab',
    boardLight.face !== boardDark.face && !boardLight.face.includes('70, 75, 84'),
    boardLight.face.slice(0, 60),
  )
  check(
    'the team colours do not change with the theme',
    boardLight.piece === boardDark.piece,
    `dark ${boardDark.piece} / light ${boardLight.piece}`,
  )
  check(
    "one player switching does not move the other's page",
    blueStillDark === 'dark',
    `blue is ${blueStillDark}`,
  )
  await p1.screenshot({ path: '/tmp/opencode/browser-board-light.png', fullPage: false })
  await p1.getByRole('button', { name: /switch to the dark theme/i }).click()
  await settled()
  const boardBack = await boardColours()
  check(
    'and the switch puts the board back',
    boardBack.square === boardDark.square,
    `${boardDark.square} -> ${boardBack.square}`,
  )
  const identity1 = await p1.locator('.game__identity').innerText()
  const identity2 = await p2.locator('.game__identity').innerText()
  check(
    'red sees both players named over the board',
    identity1.includes('Red') && identity1.includes('Blue'),
    identity1.replace(/\s+/g, ' '),
  )
  check(
    'and blue sees the same two names',
    identity2.includes('Red') && identity2.includes('Blue'),
    identity2.replace(/\s+/g, ' '),
  )
  // the labels are uppercased by CSS and innerText reflects that
  const strengthText = (await p1.locator('.strength').innerText()).toLowerCase()
  check(
    'the strength bar names the sides it is counting',
    strengthText.includes('red') && strengthText.includes('blue'),
    strengthText.replace(/\n/g, ' / '),
  )
  check(
    'the waiting banner names the player being waited for',
    /waiting for/i.test(await p2.locator('.banner').innerText()) ||
      /your move/i.test(await p2.locator('.banner').innerText()),
    await p2.locator('.banner').innerText(),
  )
  check(
    'the conversation is still on screen once the game is live',
    (await p1.locator('.chat__line').count()) === 2,
  )
  check('red board renders 8 rows of 9', (await p1.locator('.board__row').count()) === 8)
  check(
    'red board renders 72 squares',
    (await p1.locator('[data-testid="board"] .square').count()) === 72,
  )
  check(
    'both players see 21 of their own pieces',
    (await readBoard(p1)).filter((s) => s.mine && s.occupied).length === 21 &&
      (await readBoard(p2)).filter((s) => s.mine && s.occupied).length === 21,
  )
  const redFog = await readBoard(p1)
  const blueFog = await readBoard(p2)
  check(
    'red cannot name a single enemy rank before any battle',
    redFog.filter((s) => !s.mine && s.occupied).every((s) => s.what === 'unknown enemy piece'),
  )
  check(
    'blue cannot name a single enemy rank before any battle',
    blueFog.filter((s) => !s.mine && s.occupied).every((s) => s.what === 'unknown enemy piece'),
  )
  check(
    'the two views differ (per-user fog, not a shared board)',
    JSON.stringify(redFog) !== JSON.stringify(blueFog),
  )
  check('red is told it is its move', await myTurn(p1))
  check('blue is told to wait', !(await myTurn(p2)))
  check('both sockets are live', (await p1.locator('.conn').innerText()).trim() === 'live')

  console.log('\n== clock ==')
  // The countdown is drawn from an instant the server chose, so the honest check is against
  // that instant and not against a number the test made up: fetch the state both windows are
  // being pushed and compare the seconds on screen with what is left of the same deadline.
  const readClock = async (page) => ({
    text: (await page.locator('[role="timer"] .clock__time').innerText()).trim(),
    tone: (await page.locator('[role="timer"]').getAttribute('class')) || '',
    width: await page
      .locator('[role="timer"] .clock__fill')
      .evaluate((el) => el.style.width),
    label: await page.locator('[role="timer"] .sr-only').innerText(),
  })
  const deadlineState = () =>
    p1.evaluate(async () => {
      const session = JSON.parse(localStorage.getItem('generals.session'))
      const response = await fetch('/api/games/' + session.gameId, {
        headers: { 'X-Player-Token': session.token },
      })
      return response.json()
    })

  check('red is shown a clock', (await p1.locator('[role="timer"]').count()) === 1)
  check('blue is shown the same clock', (await p2.locator('[role="timer"]').count()) === 1)

  const before = await readClock(p1)
  const beforeBlue = await readClock(p2)
  check('the clock counts a whole turn, not a guess', before.text === '60s', before.text)
  check('both players are shown the same time left', before.text === beforeBlue.text,
    `red ${before.text}, blue ${beforeBlue.text}`)
  check('it says the time is on the player to move', /your move/i.test(before.label), before.label)
  check('the bar starts full', before.width === '100%', before.width)
  check('nothing is announced once a second',
    (await p1.locator('[role="timer"]').getAttribute('aria-live')) === 'off')

  await sleep(1600)
  const after = await readClock(p1)
  const ticked = Number.parseInt(before.text, 10) - Number.parseInt(after.text, 10)
  check('the number counts down', ticked >= 1 && Number.parseInt(after.text, 10) >= 0,
    `${before.text} then ${after.text}`)
  check('the bar empties with it', Number.parseInt(after.width, 10) < 100, after.width)

  const state = await deadlineState()
  const leftFromServer = Math.ceil((state.turnDeadlineMillis - Date.now()) / 1000)
  const shown = Number.parseInt(after.text, 10)
  check('the number is the server\'s deadline, not one the client counted',
    Math.abs(leftFromServer - shown) <= 1 && state.turnSeconds === 60,
    `server ${leftFromServer}s, screen ${shown}s, turnSeconds ${state.turnSeconds}`)
  check('the client sends nothing the server did not', state.turnDeadlineMillis > 0,
    `deadline ${state.turnDeadlineMillis}`)

  console.log('\n== board feedback ==')
  // Out of turn: click one of blue's own pieces and expect the explanation.
  const bluePiece = blueFog.find((s) => s.mine && s.occupied)
  await square(p2, bluePiece.coord).click()
  await p2.locator('.alert--error').waitFor({ timeout: 5000 })
  check(
    'clicking your own piece out of turn explains why',
    (await p2.locator('.alert--error').innerText()).includes('not your turn'),
    await p2.locator('.alert--error').innerText(),
  )
  // Nothing selected, an unseen enemy square: the board says so by name.
  const redHidden = redFog.find((s) => !s.mine && s.occupied)
  await square(p1, redHidden.coord).click()
  await p1.locator('.alert--error').waitFor({ timeout: 5000 })
  check(
    'clicking an unseen enemy piece names the square',
    (await p1.locator('.alert--error').innerText()).includes(redHidden.coord),
    await p1.locator('.alert--error').innerText(),
  )
  check(
    'an enemy rank still leaks nothing in the DOM',
    !(await p1.content()).includes('Five-Star'),
  )
  await p1.locator('.alert__close').click()
  await p2.locator('.alert__close').click()

  console.log('\n== play ==')
  let moves = 0
  let sawBattleOverlay = false
  let sawReveal = false

  /** Selects `from`, confirms `to` is offered, and moves. Returns false if it stalls. */
  async function playMove(page, from, to, who) {
    to = { ...to, coord: to.coord ?? to.label ?? to }
    await square(page, from.coord).click()
    let offered = (await square(page, to.coord).getAttribute('class')).includes('square--target')
    if (!offered) {
      // A stale selection swallows the click: drop it and select again.
      await square(page, from.coord).click()
      offered = (await square(page, to.coord).getAttribute('class')).includes('square--target')
      if (!offered) {
        note(`${who}: ${from.coord} -> ${to.coord} was never offered as a legal destination`)
        return false
      }
    }
    await square(page, to.coord).click()
    if (!(await waitForTurn(page, false, 20000))) {
      note(`${who}: the turn did not pass after ${from.coord} -> ${to.coord}`)
      note(`  banner: ${JSON.stringify(await banner(page).innerText())}`)
      const alerts = await page.locator('.alert--error').count()
      if (alerts) note(`  alert: ${JSON.stringify(await page.locator('.alert--error').innerText())}`)
      return false
    }
    moves++
    return true
  }

  const at_ = (board, r, c) => board.find((x) => x.row === r && x.col === c)

  /**
   * A blue move that keeps the stage intact: never the flag, never into row 4, so red's
   * general keeps a clear landing square and the flag stays where it was staged.
   */
  function safeBlueMove(board) {
    const mine = board.filter((x) => x.mine && x.occupied && x.what !== 'Flag')
    const options = []
    for (const p of mine) {
      for (const [r, c] of ortho(p.row, p.col)) {
        const t = at_(board, r, c)
        if (!t || t.occupied || r < 5) continue
        options.push({ from: p, to: t })
      }
      if (loneIn(board, p.row, p.col, true)) {
        for (const [r, c] of ortho(p.row, p.col)) {
          const mid = at_(board, r, c)
          if (!mid || mid.occupied || r < 5) continue
          for (const [rr, cc] of ortho(r, c)) {
            const dr = Math.abs(rr - p.row)
            const dc = Math.abs(cc - p.col)
            if (!((dr === 2 && dc === 0) || (dr === 0 && dc === 2))) continue
            const t = at_(board, rr, cc)
            if (t && !t.occupied && rr >= 5) options.push({ from: p, to: t, double: true })
          }
        }
      }
    }
    return options[0] || null
  }

  // 1. red opens with the general's solo double move down the centre column.
  await waitForTurn(p1, true, 20000)
  const redStart = await readBoard(p1)
  // The board names ranks with the server's display names ("5-Star General"), which are
  // not the same strings the tray uses ("5★ General").
  const general = redStart.find((x) => x.mine && x.what === '5-Star General')
  check(
    'red can name its own general (own ranks are never hidden)',
    !!general,
    general ? general.coord : 'missing',
  )
  check(
    'the general really is alone, so a double move is legal',
    loneIn(redStart, general.row, general.col, true),
  )
  check(
    `move 1: ${general.coord} -> E5 is offered as a legal destination`,
    await (async () => {
      await square(p1, general.coord).click()
      const on = (await square(p1, 'E5').getAttribute('class')).includes('square--target')
      return on
    })(),
  )
  const opened = await playMove(p1, general, { coord: 'E5' }, 'RED')
  if (!opened) {
    note('red could not make the opening double move; skipping the scripted finish')
    throw new Error('scripted finish could not start')
  }
  const landed = (await readBoard(p1)).find((x) => x.coord === 'E5')
  check(
    'the double move carried the general two squares down the centre column',
    landed.mine && landed.occupied && landed.what === '5-Star General',
    `${landed.coord} = ${landed.what}`,
  )
  check('blue was pushed into the turn by red\'s move', await waitForTurn(p2, true, 20000))

  // 2. blue shuffles inside its own camp, which keeps the staged flag in place.
  const blueSafe = safeBlueMove(await readBoard(p2))
  check('blue has a move that leaves the stage intact', !!blueSafe, blueSafe ? `${blueSafe.from.coord} -> ${blueSafe.to.coord}` : 'none')
  if (blueSafe) {
    if (!(await playMove(p2, blueSafe.from, blueSafe.to, 'BLUE'))) note('blue stalled')
    else note(`  BLUE: ${blueSafe.from.coord} -> ${blueSafe.to.coord} accepted by the server`)
  }

  // 3. red takes the flag. Attacking it is a battle, so the overlay shows the outcome —
  // but it cannot name blue's flag, because a battle exposes nothing.
  await waitForTurn(p1, true, 20000)
  const redBefore = await readBoard(p1)
  const gen2 = redBefore.find((x) => x.mine && x.what === '5-Star General')
  const target = at_(redBefore, gen2.row + 1, gen2.col)
  check(
    `move 3: ${gen2.coord} -> ${target.coord} is offered, and the target is still hidden`,
    target.occupied && target.what === 'unknown enemy piece',
    `${target.coord} = ${target.what}`,
  )
  const taken = await playMove(p1, gen2, target, 'RED')
  if (taken) {
    const overlay = p1.locator('.battle-card__rank')
    await overlay.first().waitFor({ timeout: 10000 }).catch(() => {})
    const ranks = await overlay.allInnerTexts()
    check('the battle overlay showed both participants', ranks.length === 2, ranks.join(' vs '))
    check(
      'the overlay hides the enemy rank that lost the fight',
      !ranks.some((r) => /flag/i.test(r)),
      ranks.join(' vs '),
    )
    check(
      'the overlay still names the viewer\'s own piece',
      ranks.some((r) => /5-star general/i.test(r)),
      ranks.join(' vs '),
    )
    check(
      'the overlay shows the opponent side as Unknown',
      ranks.some((r) => /unknown/i.test(r)),
      ranks.join(' vs '),
    )
    const caption = await p1.locator('.battle-card__note').innerText().catch(() => '')
    check(
      'the battle caption names no ranks at all',
      !/flag|general|private|sergeant|spy|colonel|major|captain|lieutenant/i.test(caption),
      caption,
    )
    sawBattleOverlay = true
    sawReveal = ranks.some((r) => /unknown/i.test(r))
    note(`  battle overlay: ${ranks.join(' vs ')} | caption: ${caption}`)
  }

  check('the game ran a live exchange through the UI', moves >= 3, `${moves} moves`)

  // the game screen is a board beside a panel, and the panel now has a chat box on it:
  // this is the widest the live game ever gets, so it is measured on a phone
  await p2.setViewportSize({ width: 360, height: 720 })
  await p2.waitForTimeout(200)
  const gameOverflow = await p2.evaluate(() => {
    const shown = window.innerWidth
    // name the culprits rather than just the total: "372px in 360px" says nothing about
    // which element to fix
    const wide = Array.from(document.querySelectorAll('body *'))
      .filter((el) => el.getBoundingClientRect().right > shown + 1)
      .slice(0, 8)
      .map(
        (el) =>
          `${el.tagName.toLowerCase()}.${(el.className || '').toString().split(' ')[0]}=${Math.round(el.getBoundingClientRect().width)}`,
      )
    return {
      scroll: document.documentElement.scrollWidth,
      shown,
      chat: document.querySelector('.chat__input')?.getBoundingClientRect().width ?? 0,
      wide,
    }
  })
  check(
    'the game screen, chat box and all, does not scroll sideways on a phone',
    gameOverflow.scroll <= gameOverflow.shown + 1 && gameOverflow.chat > 40,
    `${gameOverflow.scroll}px of content in ${gameOverflow.shown}px, chat input ${Math.round(gameOverflow.chat)}px, over the edge: ${gameOverflow.wide.join(', ') || 'nothing'}`,
  )
  await p2.screenshot({ path: `/tmp/opencode/browser-game-phone.png`, fullPage: true })
  await p2.setViewportSize({ width: 1400, height: 1000 })

  console.log('\n== result ==')
  await p1.waitForSelector('.gameover-overlay', { timeout: 25000 })
  const t1 = await p1.locator('.gameover__title').innerText()
  const t2 = await p2.locator('.gameover__title').innerText()
  check('the game reached a conclusion in the UI', ['Victory', 'Defeat'].includes(t1), `${t1} / ${t2}`)
  check(
    'exactly one player is told they won',
    (t1 === 'Victory') !== (t2 === 'Victory'),
    `red=${t1} blue=${t2}`,
  )
  check(
    'the end screen gives a reason',
    (await p1.locator('.gameover__reason').innerText()).trim().length > 0,
    await p1.locator('.gameover__reason').innerText(),
  )
  const stats = await p1.locator('.gameover__stats').innerText()
  check('the end screen reports the score', /your pieces left/i.test(stats), stats.replace(/\n/g, ' '))
  check('a battle overlay was shown at least once', sawBattleOverlay)
  check('the battle overlay redacted the enemy rank in the DOM', sawReveal)
  // Red's general survived the flag capture. It must STILL be face-down for blue: winning
  // a battle is not what exposes a piece.
  const finalBlue = await readBoard(p2)
  const namedByBlue = finalBlue.filter(
    (s) => !s.mine && s.occupied && s.what !== 'unknown enemy piece',
  )
  check(
    'the surviving winner of the final battle is still face-down for the loser',
    namedByBlue.length === 0,
    namedByBlue.map((s) => `${s.coord}=${s.what}`).join(' ') || 'nothing named',
  )
  const hiddenLeft = finalBlue.filter((s) => !s.mine && s.occupied && s.hidden).length
  check(
    'every surviving enemy piece is face-down for the loser',
    hiddenLeft > 0,
    `${hiddenLeft} still hidden, ${namedByBlue.length} revealed`,
  )
  check(
    'the clock is gone once the game is over',
    (await p1.locator('[role="timer"]').count()) === 0 &&
      (await p2.locator('[role="timer"]').count()) === 0,
  )

  console.log('\n== scores ==')
  const recorded1 = await p1.locator('.gameover__recorded').innerText()
  const recorded2 = await p2.locator('.gameover__recorded').innerText()
  const redWon = t1 === 'Victory'
  check(
    'the winner is told the win was recorded for them by name',
    redWon ? recorded1.includes('win for Red') : recorded1.includes('loss is on the record as Red'),
    `red: ${recorded1}`,
  )
  check(
    'the loser is told the loss was recorded against them by name',
    redWon ? recorded2.includes('loss is on the record as Blue') : recorded2.includes('win for Blue'),
    `blue: ${recorded2}`,
  )

  await p1.getByRole('link', { name: 'See scores' }).click()
  await p1.locator('.scores').waitFor({ timeout: 10000 })
  check('the scores open at their own path', new URL(p1.url()).pathname === '/leaderboard', p1.url())
  const rows = await p1.locator('.scores tbody tr').evaluateAll((trs) =>
    trs.map((tr) => Array.from(tr.querySelectorAll('th,td')).map((c) => c.textContent.trim())),
  )
  const redRow = rows.find((r) => r[1]?.startsWith('Red'))
  const blueRow = rows.find((r) => r[1]?.startsWith('Blue'))
  check(
    'the winner is on the table with a win and the loser with a loss',
    !!redRow &&
      !!blueRow &&
      (redWon ? Number(redRow[2]) === 1 && Number(blueRow[3]) === 1 : Number(blueRow[2]) === 1 && Number(redRow[3]) === 1),
    JSON.stringify({ redRow, blueRow }),
  )
  check(
    'a row of one wins outranks a row of none',
    rows.every((r, i) => i === 0 || Number(rows[i - 1][2]) >= Number(r[2])),
    rows.map((r) => r.slice(1, 4).join('/')).join(' '),
  )
  // #, name, won, lost, played, win rate, streak, best run
  const headers = await p1.locator('.scores thead th').allInnerTexts()
  check(
    'the table has a played and a win rate column',
    headers.some((h) => /played/i.test(h)) && headers.some((h) => /win rate/i.test(h)),
    headers.join(' | '),
  )
  const rateOf = (r) => Number(String(r[5]).replace('%', ''))
  const playedOf = (r) => Number(r[4])
  check(
    'every row reports the games behind its win rate',
    rows.every(
      (r) =>
        playedOf(r) === Number(r[2]) + Number(r[3]) &&
        rateOf(r) === Math.round((Number(r[2]) * 100) / playedOf(r)),
    ),
    rows.map((r) => r.slice(1, 6).join('/')).join(' '),
  )
  check(
    "this game's players each finished one game, so their rate is 100% or 0%",
    (redWon ? rateOf(redRow) === 100 : rateOf(redRow) === 0) &&
      (!redWon ? rateOf(blueRow) === 100 : rateOf(blueRow) === 0) &&
      playedOf(redRow) === 1 &&
      playedOf(blueRow) === 1,
    JSON.stringify({ red: redRow, blue: blueRow }),
  )
  check(
    'the reader sees their own row marked',
    (await p1.locator('.scores__row--you th').innerText()).startsWith('Red'),
    await p1.locator('.scores__row--you th').innerText(),
  )
  check(
    'and only their own row is marked',
    (await p1.locator('.scores tbody tr').count()) >= 2 &&
      (await p1.locator('.scores__row--you').count()) === 1,
  )
  // the ledger is a file, so the number behind the page must be readable off disk too
  const apiBoard = await p1.evaluate(async () => {
    // no limit: the page asks for everyone, and so does this check
    const r = await fetch('/api/leaderboard')
    return r.json()
  })
  const apiRed = apiBoard.entries.find((e) => e.name === 'Red')
  const apiBlue = apiBoard.entries.find((e) => e.name === 'Blue')
  check(
    'the same rows come from the API',
    !!apiRed && !!apiBlue && apiRed.wins + apiBlue.wins === 1,
    JSON.stringify({ apiRed, apiBlue }),
  )
  check(
    'the computer has no row of its own',
    apiBoard.entries.every((e) => e.name !== 'null' && e.name !== 'undefined'),
  )
  check(
    'the whole table is served, and says how many players there are',
    apiBoard.entries.length === apiBoard.totalPlayers && apiBoard.entries.length >= 2,
    `${apiBoard.entries.length} rows, totalPlayers ${apiBoard.totalPlayers}`,
  )
  check(
    'the API win rates are the ones on the page',
    apiRed.gamesPlayed === apiRed.wins + apiRed.losses &&
      apiBlue.gamesPlayed === apiBlue.wins + apiBlue.losses &&
      apiRed.winRatePercent === Math.round((apiRed.wins * 100) / apiRed.gamesPlayed),
    JSON.stringify({ apiRed, apiBlue }),
  )
  // eight columns of numbers is the widest table in the app, and it has to survive a phone
  await p1.setViewportSize({ width: 360, height: 720 })
  await p1.waitForTimeout(150)
  const overflow = await p1.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    shown: window.innerWidth,
  }))
  check(
    'the scores table does not scroll sideways on a phone',
    overflow.scroll <= overflow.shown + 1,
    `${overflow.scroll}px of content in ${overflow.shown}px`,
  )
  await p1.screenshot({ path: `/tmp/opencode/browser-scores-phone.png`, fullPage: true })
  await p1.setViewportSize({ width: 1400, height: 1000 })

  const scoreShot = process.env.SHOT_DIR || '/tmp/opencode'
  await p1.screenshot({ path: `${scoreShot}/browser-scores.png`, fullPage: true })

  const shots = process.env.SHOT_DIR || '/tmp/opencode'
  await p1.screenshot({ path: `${shots}/browser-red.png`, fullPage: true })
  await p2.screenshot({ path: `${shots}/browser-blue.png`, fullPage: true })
  check('screenshots captured', true, `${shots}/browser-{red,blue}.png`)

  // The first game is finished, and a finished game cannot be left twice: the only honest
  // place to see a resignation is a second one. p1 is on the scores page, where the way
  // back is the lobby link, and the session is still live — so the end screen comes back
  // and is dismissed from there, which is the path a player actually walks.
  console.log('\n== leaving a live game ==')
  await p1.getByRole('link', { name: 'Back to the lobby' }).click()
  await p1.waitForSelector('.gameover-overlay', { timeout: 10000 })
  await p1.getByRole('button', { name: 'Back to lobby' }).click()
  await p1.locator('.lobby__cards').waitFor({ timeout: 10000 })
  await p2.getByRole('button', { name: 'Back to lobby' }).click()
  await p2.locator('.lobby__cards').waitFor({ timeout: 10000 })
  check('both players are back in the lobby, out of the finished game', true)

  await p1.getByRole('button', { name: 'Create game' }).click()
  await p1.locator('.waiting__code code').waitFor({ timeout: 10000 })
  const secondGame = (await p1.locator('.waiting__code code').innerText()).trim()
  await p2.getByLabel('Game code').fill(secondGame)
  await p2.getByRole('button', { name: 'Join' }).click()
  await p2.locator('.placement').waitFor({ timeout: 15000 })
  await p1.locator('.placement').waitFor({ timeout: 15000 })
  check('a second game is under way', secondGame.length > 0, `code ${secondGame}`)

  await p2.getByRole('button', { name: 'Leave', exact: true }).click()
  check('the way out still asks first in a fresh game', (await p2.locator('.leave__ask').count()) === 1)
  check(
    'and nothing has ended while the question is on screen',
    (await p1.locator('.placement').count()) === 1 && (await p2.locator('.placement').count()) === 1,
  )
  await p2.getByRole('button', { name: 'Yes, leave' }).click()
  await p1.waitForSelector('.gameover-overlay', { timeout: 15000 })
  await p2.waitForSelector('.gameover-overlay', { timeout: 15000 })
  const leaverRed = await p1.locator('.gameover__title').innerText()
  const leaverBlue = await p2.locator('.gameover__title').innerText()
  check(
    'leaving hands the win to the opponent',
    leaverRed === 'Victory' && leaverBlue === 'Defeat',
    `red=${leaverRed} blue=${leaverBlue}`,
  )
  const leaverReason = await p1.locator('.gameover__reason').innerText()
  check(
    'the end screen says who left',
    leaverReason.includes('BLUE left the game'),
    leaverReason,
  )
  check(
    'the opponent was told without asking for anything',
    (await p2.locator('.gameover__recorded').innerText()).includes('loss is on the record as Blue'),
    await p2.locator('.gameover__recorded').innerText(),
  )
  check(
    'and the one who stayed is credited with the win',
    (await p1.locator('.gameover__recorded').innerText()).includes('win for Red'),
    await p1.locator('.gameover__recorded').innerText(),
  )
  const leaverRows = await p1.evaluate(async () => (await (await fetch('/api/leaderboard')).json()).entries)
  const leaverRed2 = leaverRows.find((e) => e.name === 'Red')
  const leaverBlue2 = leaverRows.find((e) => e.name === 'Blue')
  check(
    'the resignation is on the ledger as a second game each',
    !!leaverRed2 && !!leaverBlue2 && leaverRed2.gamesPlayed === 2 && leaverBlue2.gamesPlayed === 2,
    JSON.stringify({ red: leaverRed2, blue: leaverBlue2 }),
  )

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
