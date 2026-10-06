import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { ApiRequestError } from './api/client'
import type { GameSocket, GameSocketHandlers } from './api/socket'
import { OFFICER_ORDER, OFFICER_PIPS } from './emblems'
import { PieceView } from './components/PieceView'
import { RANK_LABELS, RANK_SHORT } from './hooks/useMeta'
import {
  FULL_ARMY,
  TEST_META,
  conceal,
  makeBattle,
  makeGame,
  resetPieceIds,
} from './test/factories'
import type { GameState, Rank } from './types'

const apiMock = vi.hoisted(() => ({
  create: vi.fn(),
  join: vi.fn(),
  matchmake: vi.fn(),
  vsBot: vi.fn(),
  state: vi.fn(),
  placement: vi.fn(),
  move: vi.fn(),
  chat: vi.fn(),
  meta: vi.fn(),
  leaderboard: vi.fn(),
  resign: vi.fn(),
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
  // the theme lives on <html>, which survives a localStorage.clear()
  delete document.documentElement.dataset.theme
  // most of these tests are about a game in progress, which is past the name gate
  localStorage.setItem('generals.player', 'Tester')
  // the pathname outlives a test, and two of the screens are chosen by it
  window.history.pushState({}, '', '/')
  for (const mock of Object.values(apiMock)) mock.mockReset()
  // A real roster, not a stub: useMeta caches the first answer it sees for the whole file, so
  // an empty roster here would leave the deployment tray empty for every later test.
  apiMock.meta.mockResolvedValue(TEST_META)
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
          { color: 'RED', name: 'Tester', bot: false, you: true, difficulty: null },
          { color: 'BLUE', name: 'Rival', bot: false, you: false, difficulty: null },
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

  it('calls the computer the computer, names the level, and keeps your own name', async () => {
    await renderInGame(
      playingGame({
        seats: [
          { color: 'RED', name: 'Tester', bot: false, you: true, difficulty: null },
          { color: 'BLUE', name: null, bot: true, you: false, difficulty: 'HEURISTIC' },
        ],
        currentPlayer: 'BLUE',
      }),
    )

    expect(screen.getByRole('status')).toHaveTextContent(/waiting for computer/i)
    // the level beside the name: a player who refreshes mid-game can still tell which of
    // the three they started
    expect(screen.getAllByText('Computer (Normal)').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Tester').length).toBeGreaterThan(0)
  })

  it('calls the computer the computer even when no level came with the seat', async () => {
    await renderInGame(
      playingGame({
        seats: [
          { color: 'RED', name: 'Tester', bot: false, you: true, difficulty: null },
          { color: 'BLUE', name: null, bot: true, you: false, difficulty: null },
        ],
      }),
    )

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
          { color: 'RED', name: 'Tester', bot: false, you: true, difficulty: null },
          { color: 'BLUE', name: 'Rival', bot: false, you: false, difficulty: null },
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

describe('leaving a live game', () => {
  /** What the server answers a resignation: the finished game, won by the other side. */
  function lostByResigning(): GameState {
    return {
      ...playingGame(),
      status: 'FINISHED',
      winner: 'BLUE',
      winReason: 'RED left the game',
      currentPlayer: null,
    }
  }

  it('asks before it concedes, and the first press does nothing', async () => {
    const user = userEvent.setup()
    await renderInGame(playingGame())

    await user.click(screen.getByRole('button', { name: 'Leave' }))

    expect(await screen.findByText('Leave and lose the game?')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Yes, leave' })).toBeInTheDocument()
    expect(apiMock.resign).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Stay' }))

    expect(screen.queryByText('Leave and lose the game?')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Leave' })).toBeInTheDocument()
    expect(apiMock.resign).not.toHaveBeenCalled()
  })

  it('concedes to the server and shows the defeat it decided', async () => {
    const user = userEvent.setup()
    apiMock.resign.mockResolvedValue(lostByResigning())
    await renderInGame(playingGame())

    await user.click(screen.getByRole('button', { name: 'Leave' }))
    await user.click(screen.getByRole('button', { name: 'Yes, leave' }))

    expect(apiMock.resign).toHaveBeenCalledWith(SESSION.gameId, SESSION.token)
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('Defeat')
    expect(dialog).toHaveTextContent('RED left the game')
    // the seat is kept so the player sees what leaving cost them; the lobby comes next
    expect(JSON.parse(localStorage.getItem('generals.session') ?? 'null')).not.toBeNull()
  })

  it('offers the same way out during deployment', async () => {
    const user = userEvent.setup()
    await renderInGame(makeGame([], { status: 'PLACEMENT' }))

    await user.click(screen.getByRole('button', { name: 'Leave' }))
    expect(await screen.findByText('Leave and lose the game?')).toBeInTheDocument()
    expect(apiMock.resign).not.toHaveBeenCalled()

    apiMock.resign.mockResolvedValue(lostByResigning())
    await user.click(screen.getByRole('button', { name: 'Yes, leave' }))

    expect(apiMock.resign).toHaveBeenCalledTimes(1)
    expect(await screen.findByRole('dialog')).toHaveTextContent('Defeat')
  })

  it('reports a refusal from the server and leaves the game as it was', async () => {
    const user = userEvent.setup()
    apiMock.resign.mockRejectedValue(new ApiRequestError(409, 'the game is already over'))
    await renderInGame(playingGame())

    await user.click(screen.getByRole('button', { name: 'Leave' }))
    await user.click(screen.getByRole('button', { name: 'Yes, leave' }))

    expect(await screen.findByText('the game is already over')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByTestId('board')).toBeInTheDocument()
  })
})

describe('the deploy button once the army is in', () => {
  /** The 21 pieces a deployment fills, as the server would report them back. */
  function deployedArmy(): GameState {
    // rows 0-2 across the nine columns, which is exactly the shape a 21-piece deployment takes
    const placed = FULL_ARMY.map<[number, number, 'RED', Rank]>(
      (rank, index) => [Math.floor(index / 9), index % 9, 'RED', rank],
    )
    return makeGame([...placed], { status: 'PLACEMENT', youPlaced: true })
  }

  it('turns green with a tick when the deployment is accepted', async () => {
    const user = userEvent.setup()
    apiMock.placement.mockResolvedValue(deployedArmy())
    await renderInGame(makeGame([], { status: 'PLACEMENT' }))

    await user.click(screen.getByRole('button', { name: 'Randomise' }))
    const ready = screen.getByRole('button', { name: 'Ready' })
    expect(ready.className).not.toContain('btn--done')
    await user.click(ready)

    // the server's answer, not the click: a request that fails must not paint a tick
    await waitFor(() => expect(screen.getByRole('button', { name: /army placed/i })).toBeInTheDocument())
    const done = screen.getByRole('button', { name: /army placed/i })
    expect(done.className).toContain('btn--done')
    expect(done).toBeDisabled()
    expect(done.querySelector('.btn__tick')).not.toBeNull()
    expect(screen.queryByRole('button', { name: 'Ready' })).not.toBeInTheDocument()
  })

  it('stays on the deployment screen while it waits for the opponent', async () => {
    const user = userEvent.setup()
    apiMock.placement.mockResolvedValue(deployedArmy())
    await renderInGame(makeGame([], { status: 'PLACEMENT' }))

    await user.click(screen.getByRole('button', { name: 'Randomise' }))
    await user.click(screen.getByRole('button', { name: 'Ready' }))

    expect(await screen.findByRole('button', { name: /army placed/i })).toBeInTheDocument()
    expect(screen.getByText(/nothing to do now but wait/i)).toBeInTheDocument()
    // the camp still shows all 21 pieces, because the tick claims something was sent
    expect(screen.getByText(/^21 \/ 21$/)).toBeInTheDocument()
    expect(
      screen.getByRole('region', { name: /your camp/i }).querySelectorAll('.square--filled'),
    ).toHaveLength(21)
  })

  it('is settled already on a refresh, from the server rather than from a click', async () => {
    await renderInGame(deployedArmy())

    expect(screen.getByRole('button', { name: /army placed/i })).toBeInTheDocument()
    // and the camp is rebuilt from the board the server holds, or it would claim to be
    // waiting on an empty camp
    expect(screen.getByRole('region', { name: /your camp/i }).querySelectorAll('.square--filled')).toHaveLength(21)
  })

  it('will not offer a second deployment the server would refuse', async () => {
    const user = userEvent.setup()
    await renderInGame(deployedArmy())

    expect(screen.getByRole('button', { name: 'Randomise' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Clear' })).toBeDisabled()
    // clicking a camp square must not rearrange an army already in
    await user.click(screen.getByRole('button', { name: /^Row 0 column 0/ }))
    expect(apiMock.placement).not.toHaveBeenCalled()
  })

  it('says nothing about being sent before the army is sent', async () => {
    await renderInGame(makeGame([], { status: 'PLACEMENT' }))

    expect(screen.getByRole('button', { name: 'Ready' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Randomise' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: /army placed/i })).not.toBeInTheDocument()
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

describe('playing the computer', () => {
  it('offers the three levels, opening on the one the server defaults to', () => {
    render(<App />)
    const picker = screen.getByLabelText(/difficulty/i)
    expect(picker).toHaveValue('LEARNING')
    expect(
      Array.from((picker as HTMLSelectElement).options).map((option) => option.value),
    ).toEqual(['RANDOM', 'HEURISTIC', 'LEARNING'])
    expect((picker as HTMLSelectElement).selectedOptions[0]).toHaveTextContent('Hard')
  })

  it('says what each level does, and the sentence follows the picker', async () => {
    const user = userEvent.setup()
    render(<App />)
    expect(screen.getByText(/remembers the openings that have lost/i)).toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText(/difficulty/i), 'RANDOM')
    expect(screen.getByText(/walk into anything/i)).toBeInTheDocument()
    expect(screen.queryByText(/remembers the openings that have lost/i)).not.toBeInTheDocument()
  })

  it('starts a game against the computer at the level chosen', async () => {
    const user = userEvent.setup()
    apiMock.vsBot.mockResolvedValue({ gameId: 'g1', token: 'red-token', youAre: 'RED' })
    render(<App />)

    await user.selectOptions(screen.getByLabelText(/difficulty/i), 'RANDOM')
    await user.click(screen.getByRole('button', { name: /start game/i }))

    expect(apiMock.vsBot).toHaveBeenCalledWith('Tester', 'RANDOM')
    // the seat it handed back is what the rest of the app runs on, so a refresh lands
    // back in the game rather than in the lobby
    expect(JSON.parse(localStorage.getItem('generals.session') ?? '{}')).toEqual({
      gameId: 'g1',
      token: 'red-token',
      youAre: 'RED',
    })
  })

  it('asks for no level it was not given, so the server is never left guessing', async () => {
    const user = userEvent.setup()
    apiMock.vsBot.mockResolvedValue({ gameId: 'g1', token: 'red-token', youAre: 'RED' })
    render(<App />)

    await user.click(screen.getByRole('button', { name: /start game/i }))
    expect(apiMock.vsBot).toHaveBeenCalledWith('Tester', 'LEARNING')
  })

  it('will not start a game before a name, because the win would have nowhere to go', () => {
    renderFirstVisitUser()
    expect(screen.queryByRole('button', { name: /start game/i })).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/difficulty/i)).not.toBeInTheDocument()
  })

  it('shows what the server refused, and stays in the lobby', async () => {
    const user = userEvent.setup()
    apiMock.vsBot.mockRejectedValue(new ApiRequestError(400, 'unknown difficulty'))
    render(<App />)

    await user.click(screen.getByRole('button', { name: /start game/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/unknown difficulty/i)
    expect(localStorage.getItem('generals.session')).toBeNull()
  })

  it('puts the computer on the board as the level it is playing', async () => {
    apiMock.vsBot.mockResolvedValue({ gameId: 'g1', token: 'red-token', youAre: 'RED' })
    apiMock.state.mockResolvedValue(
      makeGame([], {
        status: 'PLACEMENT',
        seats: [
          { color: 'RED', name: 'Tester', bot: false, you: true, difficulty: null },
          { color: 'BLUE', name: null, bot: true, you: false, difficulty: 'RANDOM' },
        ],
      }),
    )
    render(<App />)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /start game/i }))

    expect(await screen.findByText(/against computer \(easy\)/i)).toBeInTheDocument()
  })

  it('sends the name with a game against the computer', async () => {
    const user = userEvent.setup()
    apiMock.vsBot.mockResolvedValue({ gameId: 'g1', token: 'red-token', youAre: 'RED' })
    render(<App />)
    await user.selectOptions(screen.getByLabelText(/difficulty/i), 'HEURISTIC')
    await user.click(screen.getByRole('button', { name: /start game/i }))

    expect(apiMock.vsBot).toHaveBeenCalledWith('Tester', 'HEURISTIC')
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

describe('light and dark themes', () => {
  it('is offered on the first visit, the lobby and the scores page', async () => {
    renderFirstVisitUser()
    expect(screen.getByRole('button', { name: /switch to the light theme/i })).toBeInTheDocument()
    cleanup()

    render(<App />)
    expect(screen.getByRole('button', { name: /switch to the light theme/i })).toBeInTheDocument()
    cleanup()

    window.history.pushState({}, '', '/leaderboard')
    render(<App />)
    expect(screen.getByRole('button', { name: /switch to the light theme/i })).toBeInTheDocument()
  })

  it('flips the page and remembers the choice', async () => {
    const user = renderFirstVisitUser()
    document.documentElement.dataset.theme = 'dark'

    await user.click(screen.getByRole('button', { name: /switch to the light theme/i }))

    expect(document.documentElement.dataset.theme).toBe('light')
    expect(localStorage.getItem('generals.theme')).toBe('light')
    // the button now names the theme in force; its accessible name is the other one
    expect(screen.getByRole('button', { name: /switch to the dark theme/i })).toBeInTheDocument()
    expect(screen.getByText('Light')).toBeInTheDocument()
  })

  it('names the theme already on the page, not the one it would give you', async () => {
    renderFirstVisitUser()
    document.documentElement.dataset.theme = 'light'
    await act(async () => {
      render(<App />)
    })

    // this is what a reader who picked light last time sees: the toggle says Light, and the
    // way out of it is the accessible name on the button
    expect(screen.getByText('Light')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /switch to the dark theme/i })).toBeInTheDocument()
  })

  it('sits inside the game header, where the controls already are', async () => {
    await renderInGame(playingGame())
    const toggle = screen.getByRole('button', { name: /switch to the light theme/i })
    expect(toggle.closest('.game__controls')).not.toBeNull()
  })
})

describe('the turn clock on the board', () => {
  it('counts down on a live turn and is gone once the game is over', async () => {
    const h = await renderInGame(playingGame({ turnSeconds: 60 }))
    expect(screen.getByRole('timer')).toHaveTextContent('60s')

    act(() =>
      h.onState({
        ...playingGame({ turnSeconds: 60 }),
        status: 'FINISHED',
        winner: 'RED',
        winReason: 'RED captured the enemy flag',
        currentPlayer: null,
        turnDeadlineMillis: null,
      }),
    )
    expect(screen.queryByRole('timer')).not.toBeInTheDocument()
  })

  it('is absent while armies are still being placed', async () => {
    await renderInGame(makeGame([], { status: 'PLACEMENT', turnDeadlineMillis: null }))
    expect(screen.queryByRole('timer')).not.toBeInTheDocument()
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
