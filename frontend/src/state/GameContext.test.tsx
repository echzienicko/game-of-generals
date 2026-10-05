import { act, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { GameSocket, GameSocketHandlers } from '../api/socket'
import { GameProvider, useGame } from './GameContext'
import { ApiRequestError } from '../api/client'
import { makeGame, resetPieceIds } from '../test/factories'
import type { Coordinate, GameState } from '../types'
import { squareAt } from '../rules'

const apiMock = vi.hoisted(() => ({
  create: vi.fn(),
  join: vi.fn(),
  matchmake: vi.fn(),
  state: vi.fn(),
  placement: vi.fn(),
  move: vi.fn(),
}))

const sockets: FakeSocket[] = []

class FakeSocket implements GameSocket {
  readonly sentMoves: Array<{ from: Coordinate; to: Coordinate }> = []
  private handlers: GameSocketHandlers | null = null

  constructor(handlers: GameSocketHandlers) {
    this.handlers = handlers
    sockets.push(this)
  }
  subscribe(): void {}
  sendMove(_gameId: string, from: Coordinate, to: Coordinate): void {
    this.sentMoves.push({ from, to })
  }
  sendPlacement(): void {}
  requestState(): void {}
  disconnect(): void {}

  /** The server's authoritative view arriving on /user/queue/game/{id}. */
  push(state: GameState) {
    this.handlers?.onState(state)
  }
  /** A refusal arriving on /user/queue/errors. */
  reject(message: string) {
    this.handlers?.onError(message)
  }
  setConnected(value: boolean) {
    this.handlers?.onStatus(value)
  }
}

vi.mock('../api/socket', () => ({
  connectGameSocket: (_token: string, handlers: GameSocketHandlers) => new FakeSocket(handlers),
}))

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return { ...actual, api: apiMock }
})

const SESSION = { gameId: 'g1', token: 'red-token', youAre: 'RED' as const }

let store: ReturnType<typeof useGame> | null = null
const publish = (next: ReturnType<typeof useGame>) => {
  store = next
}

/**
 * Surfaces the store so assertions can read it and can call its actions. Held on an
 * object rather than reassigned as a bare variable, which is a render side effect.
 */
type Store = ReturnType<typeof useGame>

/**
 * Surfaces the store so tests can assert on it and call its actions. The publish
 * callback is a prop rather than a write to a module-level binding, which the react
 * lint rules rightly refuse during render.
 */
function Probe({ publish }: { publish: (store: Store) => void }) {
  const store = useGame()
  publish(store)
  return <div data-testid="probe">{store.game?.turnNumber ?? 'none'}</div>
}

function setup(state: GameState) {
  apiMock.state.mockResolvedValue(state)
  return render(
    <GameProvider>
      <Probe publish={publish} />
    </GameProvider>,
  )
}

/** Waits for the socket to exist and for the REST seed to land. */
async function ready() {
  await waitFor(() => expect(sockets).toHaveLength(1))
  await waitFor(() => expect(store?.game).not.toBeNull())
  act(() => sockets[0].setConnected(true))
}

const startingGame = () =>
  makeGame(
    [
      [2, 0, 'RED', 'PRIVATE'],
      [2, 1, 'RED', 'PRIVATE'],
    ],
    { turnNumber: 3 },
  )

beforeEach(() => {
  resetPieceIds()
  sockets.length = 0
  localStorage.clear()
  localStorage.setItem('generals.session', JSON.stringify(SESSION))
  // every seat is named now, so the gate must be past before any game action
  localStorage.setItem('generals.player', 'Tester')
  store = null
  for (const mock of Object.values(apiMock)) mock.mockReset()
})

describe('GameProvider', () => {
  it('seeds the view over REST and connects the socket', async () => {
    setup(startingGame())
    await ready()

    expect(apiMock.state).toHaveBeenCalledWith('g1', 'red-token')
    expect(store?.connected).toBe(true)
    expect(store?.session).toEqual(SESSION)
  })

  it('shows a quiet move immediately and still sends it to the server', async () => {
    setup(startingGame())
    await ready()

    act(() => {
      void store!.movePiece({ row: 2, col: 0 }, { row: 3, col: 0 })
    })

    // the piece has already moved on screen
    expect(squareAt(store!.game!, 3, 0)?.rank).toBe('PRIVATE')
    expect(squareAt(store!.game!, 2, 0)?.pieceId).toBeNull()
    expect(sockets[0].sentMoves).toEqual([{ from: { row: 2, col: 0 }, to: { row: 3, col: 0 } }])
  })

  it('lets the server push override the optimistic guess', async () => {
    setup(startingGame())
    await ready()

    act(() => {
      void store!.movePiece({ row: 2, col: 0 }, { row: 3, col: 0 })
    })
    const authoritative = startingGame()
    authoritative.turnNumber = 4
    authoritative.currentPlayer = 'BLUE'
    authoritative.board = authoritative.board.map((square) =>
      square.row === 3 && square.col === 0
        ? { ...square, pieceId: 99, owner: 'RED', rank: 'PRIVATE', rankName: 'Private' }
        : square,
    )
    act(() => sockets[0].push(authoritative))

    expect(store?.game?.turnNumber).toBe(4)
    expect(store?.game?.currentPlayer).toBe('BLUE')
    expect(squareAt(store!.game!, 3, 0)?.pieceId).toBe(99)
  })

  it('rolls the board back when the server refuses the move', async () => {
    setup(startingGame())
    await ready()
    const before = store!.game!

    act(() => {
      void store!.movePiece({ row: 2, col: 0 }, { row: 3, col: 0 })
    })
    expect(store!.game).not.toBe(before)

    act(() => sockets[0].reject('a piece moves one square up, down, left or right'))

    expect(store?.error).toBe('a piece moves one square up, down, left or right')
    expect(store?.game?.board).toEqual(before.board)
    expect(store?.game?.turnNumber).toBe(3)
  })

  it('does not guess at a battle, leaving the board for the server to resolve', async () => {
    const game = makeGame([
      [2, 0, 'RED', 'PRIVATE'],
      [3, 0, 'BLUE', 'FLAG'],
    ])
    setup(game)
    await ready()

    act(() => {
      void store!.movePiece({ row: 2, col: 0 }, { row: 3, col: 0 })
    })

    expect(store!.game).toBe(game)
    expect(sockets[0].sentMoves).toHaveLength(1)
  })

  it('holds back a second move while the first is still in flight', async () => {
    setup(startingGame())
    await ready()

    act(() => {
      void store!.movePiece({ row: 2, col: 0 }, { row: 3, col: 0 })
    })
    act(() => {
      void store!.movePiece({ row: 2, col: 1 }, { row: 3, col: 1 })
    })

    // a double click must not race the server into an out-of-turn refusal
    expect(sockets[0].sentMoves).toHaveLength(1)
  })

  it('accepts the next move once the push confirms the last one', async () => {
    setup(startingGame())
    await ready()

    act(() => {
      void store!.movePiece({ row: 2, col: 0 }, { row: 3, col: 0 })
    })
    const confirmed = startingGame()
    confirmed.turnNumber = 4
    act(() => sockets[0].push(confirmed))
    act(() => {
      void store!.movePiece({ row: 2, col: 1 }, { row: 3, col: 1 })
    })

    expect(sockets[0].sentMoves).toHaveLength(2)
  })

  it('unblocks the player when the socket drops mid-move', async () => {
    setup(startingGame())
    await ready()

    act(() => {
      void store!.movePiece({ row: 2, col: 0 }, { row: 3, col: 0 })
    })
    act(() => sockets[0].setConnected(false))
    // the REST fallback must not be blocked by the abandoned confirmation
    apiMock.move.mockResolvedValue(startingGame())
    act(() => {
      void store!.movePiece({ row: 2, col: 1 }, { row: 3, col: 1 })
    })

    expect(apiMock.move).toHaveBeenCalledWith('g1', 'red-token', { row: 2, col: 1 }, { row: 3, col: 1 })
  })

  it('falls back to REST when the socket never connected', async () => {
    apiMock.state.mockResolvedValue(startingGame())
    render(
      <GameProvider>
        <Probe publish={publish} />
      </GameProvider>,
    )
    await waitFor(() => expect(store?.game).not.toBeNull())
    apiMock.move.mockResolvedValue(startingGame())

    act(() => {
      void store!.movePiece({ row: 2, col: 0 }, { row: 3, col: 0 })
    })

    expect(apiMock.move).toHaveBeenCalledOnce()
    expect(sockets[0].sentMoves).toHaveLength(0)
  })

  it('forgets a session the backend no longer knows about', async () => {
    apiMock.state.mockRejectedValue(new ApiRequestError(404, 'Not Found'))
    render(
      <GameProvider>
        <Probe publish={publish} />
      </GameProvider>,
    )

    await waitFor(() => expect(store?.session).toBeNull())
    expect(localStorage.getItem('generals.session')).toBeNull()
    expect(store?.error).toMatch(/no longer exists/i)
    // and the player is back in the lobby, not stuck on a spinner
    expect(screen.queryByTestId('probe')).not.toBeNull()
  })

  it('ignores a REST response that a socket push overtook', async () => {
    // The response to our own request and the push describing it travel on different
    // connections, so the push can arrive first. Applying the stale response afterwards
    // used to strand a player on the deployment screen while the game was already live.
    const stale = makeGame([[2, 0, 'RED', 'PRIVATE']], { status: 'PLACEMENT', turnNumber: 0 })
    const fresh = makeGame([[2, 0, 'RED', 'PRIVATE']], { status: 'IN_PROGRESS', turnNumber: 4 })
    let release!: (state: GameState) => void
    apiMock.state.mockReturnValue(
      new Promise<GameState>((resolve) => {
        release = resolve
      }),
    )
    render(
      <GameProvider>
        <Probe publish={publish} />
      </GameProvider>,
    )
    await waitFor(() => expect(sockets).toHaveLength(1))

    act(() => sockets[0].push(fresh))
    await act(async () => {
      release(stale)
    })

    expect(store?.game?.status).toBe('IN_PROGRESS')
    expect(store?.game?.turnNumber).toBe(4)
  })

  it('still applies a REST response that no push overtook', async () => {
    const state = startingGame()
    apiMock.state.mockResolvedValue(state)
    render(
      <GameProvider>
        <Probe publish={publish} />
      </GameProvider>,
    )
    await waitFor(() => expect(store?.game).toEqual(state))
  })
})
