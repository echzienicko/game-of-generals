import type {
  ApiError,
  BotDifficulty,
  DeploymentEntry,
  GameState,
  JoinResult,
  Leaderboard,
  MatchResult,
  Meta,
  Coordinate,
} from '../types'

const BASE = '/api'
const TOKEN_HEADER = 'X-Player-Token'

/** An error carrying the server's status and message, so the UI can show something useful. */
export class ApiRequestError extends Error {
  status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiRequestError'
    this.status = status
  }
}

async function request<T>(
  method: string,
  path: string,
  token?: string,
  body?: unknown,
): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (token) {
    headers[TOKEN_HEADER] = token
  }

  let response: Response
  try {
    response = await fetch(BASE + path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiRequestError(0, 'Cannot reach the server. Is the backend running?')
  }

  const text = await response.text()
  const payload = text ? JSON.parse(text) : null

  if (!response.ok) {
    const error = payload as ApiError | null
    throw new ApiRequestError(
      response.status,
      error?.message ?? `Request failed with status ${response.status}`,
    )
  }
  return payload as T
}

/**
 * Every seat a person takes is named, so all four of these carry the name the win will be
 * recorded under. The server refuses a game without one.
 */
export const api = {
  meta: () => request<Meta>('GET', '/meta'),

  create: (name: string) => request<JoinResult>('POST', '/games', undefined, { name }),

  join: (gameId: string, name: string) =>
    request<JoinResult>('POST', `/games/${gameId}/join`, undefined, { name }),

  matchmake: (name: string) =>
    request<MatchResult | JoinResult>('POST', '/games/matchmake', undefined, { name }),

  /**
   * A game against the computer. The level rides on the query string because that is where
   * the server reads it, and it is always sent: the server's own default is the hardest
   * level, so leaving it off would pick a game the player did not choose.
   */
  vsBot: (name: string, difficulty: BotDifficulty) =>
    request<JoinResult>('POST', `/games/vs-bot?difficulty=${difficulty}`, undefined, { name }),

  // No limit by default: the whole ledger, every player who has finished a game.
  leaderboard: (limit?: number) =>
    request<Leaderboard>('GET', limit === undefined ? '/leaderboard' : `/leaderboard?limit=${limit}`),

  chat: (gameId: string, token: string, text: string) =>
    request<GameState>('POST', `/games/${gameId}/chat`, token, { text }),

  state: (gameId: string, token: string) =>
    request<GameState>('GET', `/games/${gameId}`, token),

  placement: (gameId: string, token: string, pieces: DeploymentEntry[]) =>
    request<GameState>('POST', `/games/${gameId}/placement`, token, { pieces }),

  move: (gameId: string, token: string, from: Coordinate, to: Coordinate) =>
    request<GameState>('POST', `/games/${gameId}/move`, token, { from, to }),

  /**
   * Concedes the game. No body: the token says who is leaving, and the answer is the
   * finished game — for the loser, and (pushed) for the opponent who has just won it.
   */
  resign: (gameId: string, token: string) =>
    request<GameState>('POST', `/games/${gameId}/resign`, token),
}
