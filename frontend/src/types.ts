/** Mirrors the DTOs served by the Spring Boot backend. */

export type PlayerColor = 'RED' | 'BLUE'
export type Rank =
  | 'FIVE_STAR_GENERAL'
  | 'FOUR_STAR_GENERAL'
  | 'THREE_STAR_GENERAL'
  | 'TWO_STAR_GENERAL'
  | 'ONE_STAR_GENERAL'
  | 'COLONEL'
  | 'LIEUTENANT_COLONEL'
  | 'MAJOR'
  | 'CAPTAIN'
  | 'FIRST_LIEUTENANT'
  | 'SECOND_LIEUTENANT'
  | 'SERGEANT'
  | 'PRIVATE'
  | 'SPY'
  | 'FLAG'

/** How hard the computer tries, as the server spells it on `?difficulty=`. */
export type BotDifficulty = 'RANDOM' | 'HEURISTIC' | 'LEARNING'

export type GameStatus =
  | 'WAITING_FOR_OPPONENT'
  | 'PLACEMENT'
  | 'IN_PROGRESS'
  | 'FINISHED'

export interface Square {
  row: number
  col: number
  label: string
  pieceId: number | null
  owner: PlayerColor | null
  /** null for every enemy piece: ranks stay face-down for the whole game. */
  rank: Rank | null
  rankName: string | null
  revealed: boolean
}

/**
 * The outcome of the last clash. Fighting reveals nothing, so the four rank fields are
 * null for whichever side the viewer does not own.
 */
export interface Battle {
  attackerId: number
  attackerOwner: PlayerColor
  attackerRank: Rank | null
  attackerRankName: string | null
  fromRow: number
  fromCol: number
  attackerSurvives: boolean
  defenderId: number
  defenderOwner: PlayerColor
  defenderRank: Rank | null
  defenderRankName: string | null
  toRow: number
  toCol: number
  defenderSurvives: boolean
  description: string
}

export interface GameState {
  gameId: string
  status: GameStatus
  youAre: PlayerColor
  /**
   * Whether this viewer has sent their army in. True only for the player it belongs to, and
   * the only deployment fact a client gets — it is what settles the deploy button, and it
   * survives a refresh, which a piece of local component state would not.
   */
  youPlaced: boolean
  currentPlayer: PlayerColor | null
  winner: PlayerColor | null
  winReason: string | null
  flagEscapePending: boolean
  /**
   * When the turn on the clock falls due, as the server's epoch milliseconds, or null when
   * no move is being timed: before deployment, on the computer's turn, and after the game.
   * An absolute instant rather than a number of seconds, because the countdown is read on
   * arrival and a delay in transit must not lengthen the turn.
   */
  turnDeadlineMillis: number | null
  /** Seconds a move is given, 0 when the clock is off. Drawn as the bar behind the number. */
  turnSeconds: number
  turnNumber: number
  rows: number
  cols: number
  yourPiecesRemaining: number
  opponentPiecesRemaining: number
  board: Square[]
  lastBattle: Battle | null
  log: string[]
  /** Both seats with their names, RED first. The computer's name is null. */
  seats: Seat[]
  /** What the two players have said, oldest first. Same for both players. */
  chat: ChatMessage[]
}

/**
 * One side of a game: who is in it, and whether that is you.
 *
 * `difficulty` is the computer's level and null for a person — the player chose it on the
 * way in, so the board can name the level it is playing rather than just "Computer".
 */
export interface Seat {
  color: PlayerColor
  name: string | null
  bot: boolean
  you: boolean
  difficulty: BotDifficulty | null
}

/** A line of chat. The author comes from the server's seat, never from the client. */
export interface ChatMessage {
  id: number
  color: PlayerColor
  author: string
  text: string
  /** ISO instant. */
  at: string
}

export interface JoinResult {
  gameId: string
  token: string
  youAre: PlayerColor
}

export interface MatchResult {
  gameId: string
  redToken: string
  red: PlayerColor
  blueToken: string
  blue: PlayerColor
}

/** One row of the win table. The computer has no name, so it has no row. */
export interface LeaderboardEntry {
  name: string
  wins: number
  losses: number
  /** Finished games on this row; the denominator of the win rate. */
  gamesPlayed: number
  /** Wins as a whole percentage of gamesPlayed, 0-100. */
  winRatePercent: number
  /** Wins in a row right now; reset by a loss. */
  streak: number
  bestStreak: number
  /** ISO instant of the last win, or null if they have never won. */
  lastWinAt: string | null
}

export interface Leaderboard {
  entries: LeaderboardEntry[]
  /** Rows on the ledger. More than entries.length() only when a limit was asked for. */
  totalPlayers: number
}

export interface RankInfo {
  name: Rank
  displayName: string
  power: number
  officer: boolean
}

export interface Meta {
  rows: number
  cols: number
  armySize: number
  ranks: RankInfo[]
  roster: Record<string, number>
}

export interface ApiError {
  status: number
  error: string
  message: string
  timestamp: string
}

export interface Coordinate {
  row: number
  col: number
}

export interface DeploymentEntry extends Coordinate {
  rank: Rank
}
