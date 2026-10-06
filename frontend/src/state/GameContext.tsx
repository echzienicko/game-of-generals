import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { api, ApiRequestError } from '../api/client'
import { connectGameSocket, type GameSocket } from '../api/socket'
import { applyQuietMove } from '../rules'
import { readStoredName, storeName } from './playerName'
import type {
  BotDifficulty,
  Coordinate,
  JoinResult,
  DeploymentEntry,
  GameState,
  PlayerColor,
} from '../types'

export interface Session {
  gameId: string
  token: string
  youAre: PlayerColor
}

const STORAGE_KEY = 'generals.session'

/**
 * The name to seat a game under.
 *
 * <p>The gate in front of the lobby means this should never fire, but a tampered-with
 * browser could reach a game action with no name stored, and the server would refuse the
 * game with a message about names — which reads as a bug rather than as a missing field.
 * Failing here says what is actually wrong.
 */
function requireName(playerName: string | null): string {
  if (!playerName) {
    throw new ApiRequestError(400, 'Tell us your name before you play')
  }
  return playerName
}

function readStoredSession(): Session | null {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Session
    if (parsed?.gameId && parsed?.token && parsed?.youAre) {
      return parsed
    }
  } catch {
    // a corrupt or unavailable store should never block the lobby
  }
  return null
}

function writeStoredSession(session: Session | null) {
  try {
    if (session) {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(session))
    } else {
      window.localStorage.removeItem(STORAGE_KEY)
    }
  } catch {
    // private browsing: the session simply will not survive a refresh
  }
}

interface GameStore {
  session: Session | null
  game: GameState | null
  /** The name wins are recorded under, or null until the player has chosen one. */
  playerName: string | null
  /** Stores the name for good; the app asks for it only once. */
  saveName: (name: string) => void
  connected: boolean
  busy: boolean
  error: string | null
  selected: Coordinate | null
  createGame: () => Promise<void>
  joinGame: (gameId: string) => Promise<void>
  matchmake: () => Promise<void>
  /** A game against the computer, at the level chosen. */
  playComputer: (difficulty: BotDifficulty) => Promise<void>
  leaveGame: () => void
  /**
   * Concedes the game: the server awards the win to the opponent and the finished board
   * comes back. Unlike {@link leaveGame} this is not local — the player stays in the seat
   * to be told what their leaving cost, and leaves for the lobby from the end screen.
   */
  resign: () => Promise<void>
  submitPlacement: (pieces: DeploymentEntry[]) => Promise<void>
  movePiece: (from: Coordinate, to: Coordinate) => Promise<void>
  /** Says something to the other player. Resolves once the server has taken it. */
  sendChat: (text: string) => Promise<boolean>
  /** True while a line of chat is in flight, so the box can hold it back. */
  sendingChat: boolean
  /** A refused line, shown beside the box rather than over the board. */
  chatError: string | null
  clearChatError: () => void
  selectSquare: (square: Coordinate | null) => void
  clearError: () => void
  setError: (message: string) => void
}

const GameContext = createContext<GameStore | null>(null)

export function GameProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(readStoredSession)
  const [playerName, setPlayerName] = useState<string | null>(readStoredName)
  const [game, setGame] = useState<GameState | null>(null)
  const [connected, setConnected] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [selected, setSelected] = useState<Coordinate | null>(null)
  const [sendingChat, setSendingChat] = useState(false)
  const [chatError, setChatError] = useState<string | null>(null)

  const socketRef = useRef<GameSocket | null>(null)
  /**
   * Counts the pushes that have landed. A REST response and a socket push can arrive in
   * either order, because they travel on different connections: the push for the change
   * we just made can overtake the response to the request that caused it. The socket is
   * authoritative, so a response overtaken by a push is dropped instead of rewinding the
   * board. This is not merely defensive: it is what stopped a player from being stranded
   * on the deployment screen while the game was already under way.
   */
  const pushSeqRef = useRef(0)

  /** Applies a REST response only if no push overtook it while the request was in flight. */
  const applyUnlessPushed = useCallback(async (load: () => Promise<GameState>) => {
    const seq = pushSeqRef.current
    const state = await load()
    if (pushSeqRef.current === seq) setGame(state)
  }, [])

  /**
   * A move that has been sent but not yet confirmed. Holding the pre-move state
   * lets an illegal move be rolled back without inventing anything: if the server
   * refuses, the board really did not change.
   */
  const pendingRef = useRef<{ previous: GameState } | null>(null)

  useEffect(() => {
    if (!session) {
      return
    }

    const socket = connectGameSocket(session.token, {
      onState: (state: GameState) => {
        // The push is authoritative and supersedes any optimistic guess.
        pushSeqRef.current += 1
        pendingRef.current = null
        setGame(state)
        setError(null)
        setSelected(null)
      },
      onError: (message: string) => {
        const pending = pendingRef.current
        if (pending) {
          // The move was refused, so the board still looks like it did before.
          pendingRef.current = null
          setGame(pending.previous)
        }
        setError(message)
      },
      onStatus: (up: boolean) => {
        setConnected(up)
        if (!up) {
          // A confirmation can never arrive on a closed socket; unblock the player
          // so the REST fallback can take over.
          pendingRef.current = null
        }
      },
    })
    socketRef.current = socket
    socket.subscribe(session.gameId)

    // The socket may take a moment to connect, so seed the view over REST too.
    applyUnlessPushed(() => api.state(session.gameId, session.token)).catch((e: unknown) => {
      if (e instanceof ApiRequestError && e.status === 404) {
        // the backend restarted and lost this game
        writeStoredSession(null)
        setSession(null)
        setError('That game no longer exists. Start a new one.')
      } else {
        setError(e instanceof Error ? e.message : 'Could not load the game')
      }
    })

    return () => {
      socket.disconnect()
      socketRef.current = null
    }
  }, [session, applyUnlessPushed])

  const run = useCallback(async (action: () => Promise<void>) => {
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (e: unknown) {
      setError(e instanceof ApiRequestError ? e.message : 'Something went wrong')
    } finally {
      setBusy(false)
    }
  }, [])

  /**
   * Takes the seat the server just handed out.
   *
   * <p>Every way into a game ends here — created, joined, matched or against the computer —
   * because what the rest of the app needs from all four is identical: a session stored so
   * a refresh lands back in the game, and a session in state so the socket connects and the
   * board loads. What differs is only which endpoint was asked.
   */
  const seat = useCallback((result: JoinResult) => {
    const next: Session = { gameId: result.gameId, token: result.token, youAre: result.youAre }
    writeStoredSession(next)
    setSession(next)
  }, [])

  const createGame = useCallback(
    () =>
      run(async () => {
        seat(await api.create(requireName(playerName)))
      }),
    [run, playerName, seat],
  )

  const joinGame = useCallback(
    (gameId: string) =>
      run(async () => {
        const trimmed = gameId.trim()
        if (!trimmed) {
          throw new ApiRequestError(400, 'Enter a game code')
        }
        seat(await api.join(trimmed, requireName(playerName)))
      }),
    [run, playerName, seat],
  )

  const playComputer = useCallback(
    (difficulty: BotDifficulty) =>
      run(async () => {
        seat(await api.vsBot(requireName(playerName), difficulty))
      }),
    [run, playerName, seat],
  )

  const matchmake = useCallback(
    () =>
      run(async () => {
        const result = await api.matchmake(requireName(playerName))
        // The server either pairs us with a waiting player (returning both tokens)
        // or hands us a brand new game.
        const isMatch = 'redToken' in result
        const next: Session = isMatch
          ? {
              gameId: result.gameId,
              token: result.redToken,
              youAre: 'RED',
            }
          : { gameId: result.gameId, token: result.token, youAre: result.youAre }
        writeStoredSession(next)
        setSession(next)
      }),
    [run, playerName],
  )

  const leaveGame = useCallback(() => {
    socketRef.current?.disconnect()
    socketRef.current = null
    pendingRef.current = null
    writeStoredSession(null)
    setSession(null)
    setGame(null)
    setSelected(null)
    setError(null)
    setChatError(null)
    setConnected(false)
  }, [])

  /**
   * Concedes the game for this session's seat.
   *
   * <p>Routed through {@code run} like any other move, because it changes the board for
   * both players and a refusal ("the game is already over") deserves the same banner as
   * any other. The answer is a finished game, so the end screen appears from the response
   * or from the push that overtakes it — either way the player is left sitting in the seat
   * to see what leaving cost them, and goes back to the lobby from there.
   */
  const resign = useCallback(
    () =>
      run(async () => {
        if (!session) return
        await applyUnlessPushed(() => api.resign(session.gameId, session.token))
      }),
    [run, session, applyUnlessPushed],
  )

  const submitPlacement = useCallback(
    (pieces: DeploymentEntry[]) =>
      run(async () => {
        if (!session) return
        await applyUnlessPushed(() =>
          api.placement(session.gameId, session.token, pieces),
        )
      }),
    [run, session, applyUnlessPushed],
  )

  const movePiece = useCallback(
    (from: Coordinate, to: Coordinate) =>
      run(async () => {
        if (!session) return
        const socket = socketRef.current
        if (socket && connected) {
          if (pendingRef.current) {
            // One move in flight at a time, so a double click cannot race the
            // server into rejecting the second one as out of turn.
            return
          }
          const previous = game
          const guess = game ? applyQuietMove(game, from, to) : null
          if (previous && guess) {
            pendingRef.current = { previous }
            setGame(guess)
          }
          setSelected(null)
          // the server broadcasts both players' fresh views
          socket.sendMove(session.gameId, from, to)
          return
        }
        // fall back to HTTP when the socket is down, so play is not blocked
        await applyUnlessPushed(() => api.move(session.gameId, session.token, from, to))
        setSelected(null)
      }),
    [run, session, connected, game, applyUnlessPushed],
  )

  /**
   * Sends a line of chat, keeping its own busy flag and its own error.
   *
   * <p>Deliberately not routed through {@code run}: that flag blocks the board, and a
   * refusal ("keep it under 200 characters") has nothing to do with the move the player is
   * in the middle of. It also returns whether the line was taken, so the box can be cleared
   * on success and left alone on failure — losing what you typed because the server said no
   * is the sort of thing that makes people stop chatting.
   */
  const sendChat = useCallback(
    async (text: string) => {
      if (!session || !text.trim()) return false
      setSendingChat(true)
      setChatError(null)
      try {
        await applyUnlessPushed(() => api.chat(session.gameId, session.token, text))
        return true
      } catch (e: unknown) {
        setChatError(e instanceof ApiRequestError ? e.message : 'Could not send that')
        return false
      } finally {
        setSendingChat(false)
      }
    },
    [session, applyUnlessPushed],
  )

  const saveName = useCallback((name: string) => {
    storeName(name)
    setPlayerName(name)
  }, [])

  const value = useMemo<GameStore>(
    () => ({
      session,
      game,
      playerName,
      saveName,
      connected,
      busy,
      error,
      selected,
      createGame,
      joinGame,
      matchmake,
      playComputer,
      leaveGame,
      resign,
      submitPlacement,
      movePiece,
      sendChat,
      sendingChat,
      chatError,
      clearChatError: () => setChatError(null),
      selectSquare: setSelected,
      clearError: () => setError(null),
      setError,
    }),
    [
      session,
      game,
      playerName,
      saveName,
      connected,
      busy,
      error,
      selected,
      createGame,
      joinGame,
      matchmake,
      playComputer,
      leaveGame,
      resign,
      submitPlacement,
      movePiece,
      sendChat,
      sendingChat,
      chatError,
    ],
  )

  return <GameContext.Provider value={value}>{children}</GameContext.Provider>
}

export function useGame(): GameStore {
  const store = useContext(GameContext)
  if (!store) {
    throw new Error('useGame must be used inside <GameProvider>')
  }
  return store
}
