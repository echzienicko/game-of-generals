import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { ApiRequestError } from './api/client'
import type { GameSocket, GameSocketHandlers } from './api/socket'
import { OFFICER_ORDER, OFFICER_PIPS } from './emblems'
import { PieceView } from './components/PieceView'
import { RANK_LABELS, RANK_SHORT } from './hooks/useMeta'
import { conceal, makeBattle, makeGame, resetPieceIds } from './test/factories'
import type { GameState, Rank } from './types'

const apiMock = vi.hoisted(() => ({
  create: vi.fn(),
  join: vi.fn(),
  matchmake: vi.fn(),
  state: vi.fn(),
  placement: vi.fn(),
  move: vi.fn(),
  chat: vi.fn(),
  meta: vi.fn(),
  leaderboard: vi.fn(),
}))

let handlers: GameSocketHandlers | null = null

vi.mock('./api/socket', () => ({
  connectGameSocket: (_token: string, h: GameSocketHandlers) => {
    handlers = h
    const socket: GameSocket = {
      subscribe: () => {},
      sendMove: () => {},
      sendPlacement: () => {},
      requestState: () => {},
      disconnect: () => {},
    }
    return socket
  },
}))

vi.mock('./api/client', async () => {
  const actual = await vi.importActual<typeof import('./api/client')>('./api/client')
  return { ...actual, api: apiMock }
})

const SESSION = { gameId: 'abcdef123456', token: 'red-token', youAre: 'RED' as const }

function playingGame(overrides: Partial<GameState> = {}): GameState {
  return conceal(
    makeGame(
      [
        [2, 0, 'RED', 'PRIVATE'],
        [2, 1, 'RED', 'FIVE_STAR_GENERAL'],
        [5, 4, 'BLUE', 'FLAG'],
      ],
      { turnNumber: 12, currentPlayer: 'RED', ...overrides },
    ),
    5,
    4,
  )
}

/** Renders the app with no name remembered, as on a first visit. */
function renderFirstVisitUser() {
  localStorage.removeItem('generals.player')
  render(<App />)
  return userEvent.setup()
}

/** Renders the app already holding a session, and waits for the first view. */
async function renderInGame(state: GameState) {
  localStorage.setItem('generals.session', JSON.stringify(SESSION))
  apiMock.state.mockResolvedValue(state)
  render(<App />)
  await waitFor(() => expect(apiMock.state).toHaveBeenCalled())
  await waitFor(() => expect(handlers).not.toBeNull())
  act(() => handlers!.onStatus(true))
  return handlers!
}

beforeEach(() => {
  resetPieceIds()
  handlers = null
  localStorage.clear()
  // most of these tests are about a game in progress, which is past the name gate
  localStorage.setItem('generals.player', 'Tester')
  // the pathname outlives a test, and two of the screens are chosen by it
  window.history.pushState({}, '', '/')
  for (const mock of Object.values(apiMock)) mock.mockReset()
  apiMock.meta.mockResolvedValue({
    rows: 8,
    cols: 9,
    armySize: 21,
    ranks: [],
    roster: {},
  })
  apiMock.leaderboard.mockResolvedValue({ entries: [], totalPlayers: 0 })
})

describe('chat between the two players', () => {
  function chattyGame(chat: GameState['chat'] = []): GameState {
    return playingGame({ chat })
  }

  it('starts empty and says so', async () => {
    await renderInGame(chattyGame())
    expect(screen.getByRole('list', { name: /conversation/i })).toHaveTextContent(
      /nothing said yet/i,
    )
  })

  it('sends what was typed, and shows it as yours', async () => {
    const user = userEvent.setup()
    apiMock.chat.mockResolvedValue(chattyGame())
    await renderInGame(chattyGame())

    await user.type(screen.getByRole('textbox', { name: /message/i }), 'good luck')
    await user.click(screen.getByRole('button', { name: /send/i }))

    expect(apiMock.chat).toHaveBeenCalledWith(SESSION.gameId, SESSION.token, 'good luck')
    await waitFor(() => expect(screen.getByRole('textbox', { name: /message/i })).toHaveValue(''))
  })

  it('keeps what was typed when the server refuses it', async () => {
    const user = userEvent.setup()
    apiMock.chat.mockRejectedValue(new ApiRequestError(400, 'keep it under 200 characters'))
    await renderInGame(chattyGame())

    await user.type(screen.getByRole('textbox', { name: /message/i }), 'a very long line')
    await user.click(screen.getByRole('button', { name: /send/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/keep it under 200/i)
    expect(screen.getByRole('textbox', { name: /message/i })).toHaveValue('a very long line')
  })

  it('refuses to send a line the server would refuse, and says how long it is', async () => {
    const user = userEvent.setup()
    await renderInGame(chattyGame())

    const box = screen.getByRole('textbox', { name: /message/i })
    await user.type(box, 'x'.repeat(201))

    expect(screen.getByRole('button', { name: /send/i })).toBeDisabled()
    expect(screen.getByText(/201 characters/i)).toBeInTheDocument()
    expect(apiMock.chat).not.toHaveBeenCalled()
  })

  it('will not send an empty line at all', async () => {
    const user = userEvent.setup()
    await renderInGame(chattyGame())

    expect(screen.getByRole('button', { name: /send/i })).toBeDisabled()
    await user.type(screen.getByRole('textbox', { name: /message/i }), '   ')
    expect(screen.getByRole('button', { name: /send/i })).toBeDisabled()
    expect(apiMock.chat).not.toHaveBeenCalled()
  })

  it("shows the other player's line under their own name", async () => {
    await renderInGame(
      chattyGame([
        {
          id: 1,
          color: 'BLUE',
          author: 'Rival',
          text: 'you play too fast',
          at: '2026-02-01T10:00:00Z',
        },
      ]),
    )

    expect(screen.getByText('you play too fast').closest('li')).toHaveTextContent('Rival')
  })

  it('shows your own line as yours rather than repeating your name at you', async () => {
    await renderInGame(
      chattyGame([
        {
          id: 1,
          color: 'RED',
          author: 'Tester',
          text: 'sorry, that was me',
          at: '2026-02-01T10:00:00Z',
        },
      ]),
    )

    const line = screen.getByText('sorry, that was me').closest('li')!
    expect(line).toHaveTextContent('You')
    expect(line).not.toHaveTextContent('Tester')
  })

  it('shows what the other player said when it arrives on the socket', async () => {
    const h = await renderInGame(chattyGame())
    act(() =>
      h.onState(
        chattyGame([
          {
            id: 7,
            color: 'BLUE',
            author: 'Rival',
            text: 'good luck to you too',
            at: '2026-02-01T10:01:00Z',
          },
        ]),
      ),
    )

    expect(screen.getByText('good luck to you too').closest('li')).toHaveTextContent('Rival')
    expect(screen.queryByText(/nothing said yet/i)).not.toBeInTheDocument()
  })
})

describe('whose army is whose', () => {
  it('names both players over the board, and names them in the waiting banner', async () => {
    await renderInGame(
      playingGame({
        seats: [
          { color: 'RED', name: 'Tester', bot: false, you: true },
          { color: 'BLUE', name: 'Rival', bot: false, you: false },
        ],
        currentPlayer: 'BLUE',
      }),
    )

    expect(screen.getByRole('status')).toHaveTextContent(/waiting for rival to move/i)
    // both sides are named over the board, and the strength bar names them too
    expect(document.querySelector('.game__identity')).toHaveTextContent('Tester')
    expect(document.querySelector('.game__identity')).toHaveTextContent('Rival')
    expect(document.querySelector('.strength')).toHaveTextContent('Tester')
    expect(document.querySelector('.strength')).toHaveTextContent('Rival')
  })

  it('calls the computer the computer, and keeps your own name', async () => {
    await renderInGame(
      playingGame({
        seats: [
          { color: 'RED', name: 'Tester', bot: false, you: true },
          { color: 'BLUE', name: null, bot: true, you: false },
        ],
        currentPlayer: 'BLUE',
      }),
    )

    expect(screen.getByRole('status')).toHaveTextContent(/waiting for computer to move/i)
    expect(screen.getAllByText('Computer').length).toBeGreaterThan(0)
  })

  it('says who you were playing when the game ends', async () => {
    await renderInGame(
      playingGame({
        status: 'FINISHED',
        winner: 'RED',
        winReason: 'RED captured the flag',
        turnNumber: 40,
      }),
    )

    expect(screen.getByRole('dialog')).toHaveTextContent(/against rival/i)
  })

  it('names you on the waiting screen', async () => {
    await renderInGame(makeGame([], { status: 'WAITING_FOR_OPPONENT' }))
    expect(screen.getByText('Tester')).toBeInTheDocument()
  })

  it('names the opponent on the deployment screen', async () => {
    await renderInGame(
      makeGame([], {
        status: 'PLACEMENT',
        seats: [
          { color: 'RED', name: 'Tester', bot: false, you: true },
          { color: 'BLUE', name: 'Rival', bot: false, you: false },
        ],
      }),
    )

    expect(screen.getByText(/against rival/i)).toBeInTheDocument()
    expect(screen.getByText('Tester')).toBeInTheDocument()
  })
})

describe('routing by game status', () => {
  it('shows the lobby when there is no session', () => {
    render(<App />)
    expect(screen.getByRole('heading', { name: /game of the generals/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /create game/i })).toBeInTheDocument()
  })

  it('shows the waiting room until an opponent joins', async () => {
    const h = await renderInGame(makeGame([], { status: 'WAITING_FOR_OPPONENT' }))
    expect(screen.getByText(/waiting for an opponent/i)).toBeInTheDocument()
    expect(screen.getByText('abcdef123456')).toBeInTheDocument()

    act(() => h.onState(makeGame([], { status: 'PLACEMENT' })))
    expect(screen.queryByText(/waiting for your opponent/i)).not.toBeInTheDocument()
  })

  it('shows the placement screen while armies are being set up', async () => {
    const h = await renderInGame(makeGame([], { status: 'PLACEMENT' }))
    expect(await screen.findByRole('button', { name: /randomise/i })).toBeInTheDocument()

    act(() => h.onState(playingGame()))
    expect(screen.queryByRole('button', { name: /randomise/i })).not.toBeInTheDocument()
    expect(screen.getByTestId('board')).toBeInTheDocument()
  })

  it('shows the board once the game is live, and the result when it ends', async () => {
    const h = await renderInGame(playingGame())
    expect(screen.getByTestId('board')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    act(() =>
      h.onState({
        ...playingGame(),
        status: 'FINISHED',
        winner: 'RED',
        winReason: 'RED captured the enemy flag',
        currentPlayer: null,
      }),
    )
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveTextContent('Victory')
    expect(dialog).toHaveTextContent('RED captured the enemy flag')
  })

  it('reports a defeat without pretending it was a draw', async () => {
    const h = await renderInGame(playingGame())
    act(() =>
      h.onState({ ...playingGame(), status: 'FINISHED', winner: 'BLUE', winReason: 'BLUE took your flag' }),
    )
    expect(screen.getByRole('dialog')).toHaveTextContent('Defeat')
  })
})

describe('board rendering', () => {
  it('draws all 72 squares with their server labels', async () => {
    await renderInGame(playingGame())
    expect(screen.getAllByRole('button', { name: /, (empty|.*)/ })).toHaveLength(72)
    expect(screen.getByRole('button', { name: /^A1, empty$/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^I8, empty$/ })).toBeInTheDocument()
  })

  it('shows your own pieces face-up', async () => {
    await renderInGame(playingGame())
    expect(screen.getByRole('button', { name: 'A3, Private' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'B3, 5★ General' })).toBeInTheDocument()
  })

  it('stamps an officer pip per rank step, on top of the abbreviation', async () => {
    const { unmount } = render(<PieceView rank="SERGEANT" color="red" />)
    expect(document.querySelectorAll('.piece__pip')).toHaveLength(OFFICER_PIPS.SERGEANT)
    unmount()

    for (const rank of OFFICER_ORDER) {
      const view = render(<PieceView rank={rank} color="red" />)
      expect(document.querySelectorAll('.piece__pip'), rank).toHaveLength(OFFICER_PIPS[rank])
      // the text abbreviation survives alongside the emblem
      expect(screen.getByTitle(RANK_LABELS[rank])).toHaveTextContent(RANK_SHORT[rank])
      view.unmount()
    }
  })

  it('gives the three non-officer ranks a glyph rather than pips', async () => {
    for (const rank of ['FLAG', 'SPY', 'PRIVATE'] as const) {
      const view = render(<PieceView rank={rank} color="red" />)
      expect(document.querySelector('.piece__glyph'), rank).toBeInTheDocument()
      // a one-pip private would be indistinguishable from a Sergeant
      expect(document.querySelectorAll('.piece__pip'), rank).toHaveLength(0)
      view.unmount()
    }
  })

  it('stamps no emblem on a face-down piece, so nothing leaks the rank', () => {
    // every rank at once: if any emblem rendered, the DOM would differ per rank
    const seen = new Set<string>()
    for (const rank of [...OFFICER_ORDER, 'PRIVATE', 'SPY', 'FLAG'] as Rank[]) {
      const { container, unmount } = render(<PieceView rank={rank} color="blue" hidden />)
      expect(container.querySelector('.piece__emblem'), rank).toBeNull()
      seen.add(container.innerHTML)
      unmount()
    }
    expect(seen.size, 'every face-down piece renders identically').toBe(1)
  })

  it('keeps an enemy piece that has never fought face-down', async () => {
    await renderInGame(playingGame())
    const square = screen.getByRole('button', { name: 'E6, unknown enemy piece' })
    expect(square).toBeInTheDocument()
    expect(square).toHaveTextContent('?')
    // the real rank must not leak anywhere in the markup
    expect(square.innerHTML).not.toContain('Flag')
  })

  it('keeps the enemy piece that won a battle face-down', async () => {
    const h = await renderInGame(playingGame())
    // red's private charged the blue piece and died; the survivor is still hidden
    act(() =>
      h.onState({
        ...playingGame(),
        turnNumber: 13,
        lastBattle: makeBattle(),
      }),
    )
    const square = screen.getByRole('button', { name: 'E6, unknown enemy piece' })
    expect(square).toBeInTheDocument()
    expect(square.innerHTML).not.toContain('Flag')
  })

  it('reports the battle outcome while showing the enemy rank as Unknown', async () => {
    const h = await renderInGame(playingGame())
    act(() => h.onState({ ...playingGame(), turnNumber: 13, lastBattle: makeBattle() }))

    const overlay = document.querySelector('.battle-overlay')
    expect(overlay).not.toBeNull()
    expect(overlay).toHaveTextContent(/defender holds the square/i)
    expect(overlay).toHaveTextContent('Unknown')
    // red's own piece is named; the opponent's is not, and neither is in the prose
    expect(overlay).toHaveTextContent('Private')
    expect(overlay).not.toHaveTextContent('Flag')
  })

  it('offers your own pieces for selection only on your turn', async () => {
    const h = await renderInGame(playingGame())
    expect(screen.getByRole('button', { name: 'A3, Private' }).className).toContain(
      'square--selectable',
    )
    // an enemy square is never offered, but stays clickable so it can explain itself
    expect(screen.getByRole('button', { name: 'E6, unknown enemy piece' }).className).not.toContain(
      'square--selectable',
    )

    act(() => h.onState({ ...playingGame(), currentPlayer: 'BLUE' }))
    expect(screen.getByRole('button', { name: 'A3, Private' }).className).not.toContain(
      'square--selectable',
    )
  })

  it('highlights the squares a selected piece may reach', async () => {
    const user = userEvent.setup()
    await renderInGame(playingGame())
    await user.click(screen.getByRole('button', { name: 'A3, Private' }))

    // one square up into the empty centre lane
    const target = screen.getByRole('button', { name: 'A4, empty' })
    expect(target.className).toContain('square--target')
  })

  it('refuses to select one of your pieces when it is the opponent\'s turn', async () => {
    const user = userEvent.setup()
    const h = await renderInGame(playingGame())
    act(() => h.onState({ ...playingGame(), currentPlayer: 'BLUE' }))

    await user.click(screen.getByRole('button', { name: 'A3, Private' }))
    expect(screen.getByRole('alert')).toHaveTextContent(/not your turn/i)
  })

  it('does not leak the enemy rank when you poke at an unseen piece', async () => {
    const user = userEvent.setup()
    await renderInGame(playingGame())
    await user.click(screen.getByRole('button', { name: 'E6, unknown enemy piece' }))
    expect(screen.getByRole('alert')).toHaveTextContent(/unseen enemy piece/i)
  })
})

describe('the name gate', () => {
  it('asks for a name before anything else can be played', () => {
    renderFirstVisitUser()
    expect(screen.getByRole('heading', { name: /what should we call you/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /create game/i })).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/game code/i)).not.toBeInTheDocument()
  })

  it('keeps the name and moves on to the lobby', async () => {
    const user = renderFirstVisitUser()
    await user.type(screen.getByLabelText(/your name/i), 'Nick')
    await user.click(screen.getByRole('button', { name: /start playing/i }))

    expect(localStorage.getItem('generals.player')).toBe('Nick')
    expect(screen.getByRole('button', { name: /create game/i })).toBeInTheDocument()
    expect(screen.getByText('Nick')).toBeInTheDocument()
  })

  it('is not asked again on a later visit', () => {
    localStorage.setItem('generals.player', 'Nick')
    render(<App />)
    expect(screen.queryByRole('heading', { name: /what should we call you/i })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /create game/i })).toBeInTheDocument()
  })

  it('tidies the name the same way the server would', async () => {
    const user = renderFirstVisitUser()
    await user.type(screen.getByLabelText(/your name/i), '  Nick   frost  ')
    await user.click(screen.getByRole('button', { name: /start playing/i }))
    expect(localStorage.getItem('generals.player')).toBe('Nick frost')
  })

  it('will not continue with a name of nothing but spaces', async () => {
    const user = renderFirstVisitUser()
    const input = screen.getByLabelText(/your name/i)
    await user.type(input, '   ')
    expect(screen.getByRole('button', { name: /start playing/i })).toBeDisabled()
    expect(localStorage.getItem('generals.player')).toBeNull()
  })

  it('refuses a name that cannot be shown, and says why', async () => {
    const user = renderFirstVisitUser()
    const input = screen.getByLabelText(/your name/i)
    await user.click(input)
    await user.paste('Nick\u0007')
    await user.click(screen.getByRole('button', { name: /start playing/i }))
    expect(screen.getByRole('alert')).toHaveTextContent(/cannot show/i)
    expect(localStorage.getItem('generals.player')).toBeNull()
  })

  it.each([
    ['create game', 'create', ['Tester']],
    ['join', 'join', ['abcdef', 'Tester']],
    ['find opponent', 'matchmake', ['Tester']],
  ])('sends the name with %s', async (label, call, expected) => {
    const user = userEvent.setup()
    apiMock[call as 'create'].mockResolvedValue({
      gameId: 'g1',
      token: 'red-token',
      youAre: 'RED',
    })
    render(<App />)
    if (call === 'join') {
      await user.type(screen.getByLabelText(/game code/i), 'abcdef')
    }
    await user.click(screen.getByRole('button', { name: new RegExp(label, 'i') }))

    expect(apiMock[call as 'create']).toHaveBeenCalledWith(...expected)
  })

  it('sends the name the player chose, not a stale one', async () => {
    const user = renderFirstVisitUser()
    await user.type(screen.getByLabelText(/your name/i), 'Zoe')
    await user.click(screen.getByRole('button', { name: /start playing/i }))
    apiMock.create.mockResolvedValue({ gameId: 'g1', token: 'red-token', youAre: 'RED' })

    await user.click(screen.getByRole('button', { name: /create game/i }))
    expect(apiMock.create).toHaveBeenCalledWith('Zoe')
  })
})

describe('the scores page', () => {
  const SCORES = {
    totalPlayers: 3,
    entries: [
      {
        name: 'Ada',
        wins: 7,
        losses: 2,
        gamesPlayed: 9,
        winRatePercent: 78,
        streak: 3,
        bestStreak: 5,
        lastWinAt: '2026-02-01T10:00:00Z',
      },
      {
        name: 'Nick',
        wins: 4,
        losses: 3,
        gamesPlayed: 7,
        winRatePercent: 57,
        streak: 1,
        bestStreak: 4,
        lastWinAt: '2026-02-02T10:00:00Z',
      },
      {
        name: 'Bo',
        wins: 0,
        losses: 6,
        gamesPlayed: 6,
        winRatePercent: 0,
        streak: 0,
        bestStreak: 0,
        lastWinAt: null,
      },
    ],
  }

  /** The numbers of one row, read off the rendered table in column order. */
  function cellsOf(name: RegExp) {
    const row = screen.getByRole('rowheader', { name })
    return Array.from(row.closest('tr')!.querySelectorAll('td')).map((c) => c.textContent!.trim())
  }

  it('is served at its own path and lists the table', async () => {
    window.history.pushState({}, '', '/leaderboard')
    apiMock.leaderboard.mockResolvedValue(SCORES)
    render(<App />)

    expect(await screen.findByRole('rowheader', { name: /ada/i })).toBeInTheDocument()
    expect(screen.getByRole('rowheader', { name: /nick/i })).toBeInTheDocument()
    expect(screen.getByRole('rowheader', { name: /bo/i })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: /^won$/i })).toBeInTheDocument()
    expect(apiMock.leaderboard).toHaveBeenCalled()
  })

  it('shows every player their wins, losses, games played and win rate', async () => {
    window.history.pushState({}, '', '/leaderboard')
    apiMock.leaderboard.mockResolvedValue(SCORES)
    render(<App />)

    expect(await screen.findByRole('columnheader', { name: /played/i })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: /win rate/i })).toBeInTheDocument()
    // the widest table in the app scrolls inside its own box, so the page does not
    expect(screen.getByRole('region', { name: /players and their records/i })).toBeInTheDocument()
    // # , name, won, lost, played, win rate, streak, best run
    expect(cellsOf(/ada/i)).toEqual(['1', '7', '2', '9', '78%', '3', '5'])
    expect(cellsOf(/bo/i)).toEqual(['3', '0', '6', '6', '0%', '0', '0'])
  })

  it('asks for the whole table, not a page of it', async () => {
    window.history.pushState({}, '', '/leaderboard')
    apiMock.leaderboard.mockResolvedValue(SCORES)
    render(<App />)

    await screen.findByRole('rowheader', { name: /ada/i })
    expect(apiMock.leaderboard).toHaveBeenCalledWith()
  })

  it('says so when the server showed only part of the table', async () => {
    window.history.pushState({}, '', '/leaderboard')
    apiMock.leaderboard.mockResolvedValue({ ...SCORES, totalPlayers: 40 })
    render(<App />)

    expect(await screen.findByText(/showing the top 3 of 40/i)).toBeInTheDocument()
  })

  it('marks the reader’s own row', async () => {
    localStorage.setItem('generals.player', 'Nick')
    window.history.pushState({}, '', '/leaderboard')
    apiMock.leaderboard.mockResolvedValue(SCORES)
    render(<App />)

    const mine = await screen.findByRole('rowheader', { name: /nick/i })
    expect(mine).toHaveTextContent(/you/)
    expect(screen.getByRole('rowheader', { name: /ada/i })).not.toHaveTextContent(/you/)
  })

  it('matches the reader’s row the way the server keys names', async () => {
    localStorage.setItem('generals.player', 'NICK')
    window.history.pushState({}, '', '/leaderboard')
    apiMock.leaderboard.mockResolvedValue(SCORES)
    render(<App />)

    expect(await screen.findByRole('rowheader', { name: /nick/i })).toHaveTextContent(/you/)
  })

  it('can be reached from the lobby without reloading', async () => {
    const user = userEvent.setup()
    apiMock.leaderboard.mockResolvedValue(SCORES)
    render(<App />)

    await user.click(screen.getByRole('link', { name: /scores/i }))
    expect(window.location.pathname).toBe('/leaderboard')
    expect(await screen.findByRole('rowheader', { name: /ada/i })).toBeInTheDocument()

    await user.click(screen.getByRole('link', { name: /back to the lobby/i }))
    expect(window.location.pathname).toBe('/lobby')
    expect(screen.getByRole('button', { name: /create game/i })).toBeInTheDocument()
  })

  it('reaches the scores from the name gate, before any game exists', async () => {
    apiMock.leaderboard.mockResolvedValue(SCORES)
    const user = renderFirstVisitUser()
    await user.click(screen.getByRole('link', { name: /see the scores/i }))
    expect(await screen.findByRole('rowheader', { name: /ada/i })).toBeInTheDocument()
  })

  it('says so when nobody has finished a game yet', async () => {
    window.history.pushState({}, '', '/leaderboard')
    apiMock.leaderboard.mockResolvedValue({ entries: [], totalPlayers: 0 })
    render(<App />)
    expect(await screen.findByText(/no games have finished yet/i)).toBeInTheDocument()
  })

  it('reports a failure rather than showing an empty table', async () => {
    window.history.pushState({}, '', '/leaderboard')
    apiMock.leaderboard.mockRejectedValue(new Error('offline'))
    render(<App />)
    expect(await screen.findByRole('alert')).toHaveTextContent(/could not load the scores/i)
  })
})

describe('a finished game', () => {
  it('says who the win was recorded for', async () => {
    const h = await renderInGame(playingGame())
    act(() =>
      h.onState({
        ...playingGame(),
        status: 'FINISHED',
        winner: 'RED',
        winReason: 'RED captured the enemy flag',
      }),
    )
    expect(screen.getByRole('dialog')).toHaveTextContent(/recorded as a win for Tester/i)
    expect(screen.getByRole('link', { name: /see scores/i })).toBeInTheDocument()
  })

  it('counts a loss on the record too', async () => {
    const h = await renderInGame(playingGame())
    act(() =>
      h.onState({
        ...playingGame(),
        status: 'FINISHED',
        winner: 'BLUE',
        winReason: 'BLUE captured the enemy flag',
      }),
    )
    expect(screen.getByRole('dialog')).toHaveTextContent(/loss is on the record as Tester/i)
  })
})
