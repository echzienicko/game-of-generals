import { RANK_LABELS } from '../hooks/useMeta'
import type {
  Battle,
  ChatMessage,
  GameState,
  GameStatus,
  Meta,
  PlayerColor,
  Rank,
  Seat,
  Square,
} from '../types'

export const ROWS = 8
export const COLS = 9

/** Mirrors SquareDto.label: a column letter followed by the 1-based row. */
export function labelOf(row: number, col: number): string {
  return String.fromCharCode(65 + col) + String(row + 1)
}

export function emptySquare(row: number, col: number): Square {
  return {
    row,
    col,
    label: labelOf(row, col),
    pieceId: null,
    owner: null,
    rank: null,
    rankName: null,
    revealed: false,
  }
}

let nextPieceId = 1

export function resetPieceIds() {
  nextPieceId = 1
}

function occupiedSquare(row: number, col: number, owner: PlayerColor, rank: Rank): Square {
  return {
    row,
    col,
    label: labelOf(row, col),
    pieceId: nextPieceId++,
    owner,
    rank,
    rankName: RANK_LABELS[rank],
    revealed: true,
  }
}

export interface GameOverrides {
  status?: GameStatus
  youAre?: PlayerColor
  currentPlayer?: PlayerColor | null
  winner?: PlayerColor | null
  winReason?: string | null
  turnNumber?: number
  /** Whether this viewer has deployed; default false, as if still choosing. */
  youPlaced?: boolean
  lastBattle?: Battle | null
  log?: string[]
  /** A deadline in epoch millis, defaulting to 60 seconds from now (see makeGame). */
  turnDeadlineMillis?: number | null
  turnSeconds?: number
  yourPiecesRemaining?: number
  opponentPiecesRemaining?: number
  seats?: Seat[]
  chat?: ChatMessage[]
}

/**
 * An 8x9 board laid out from a sparse list, so a test only has to name the squares
 * it actually cares about. Every piece is face-up, as if already revealed.
 */
export function makeGame(
  pieces: Array<[row: number, col: number, owner: PlayerColor, rank: Rank]>,
  overrides: GameOverrides = {},
): GameState {
  const grid: Square[][] = Array.from({ length: ROWS }, (_, row) =>
    Array.from({ length: COLS }, (_, col) => emptySquare(row, col)),
  )
  for (const [row, col, owner, rank] of pieces) {
    grid[row][col] = occupiedSquare(row, col, owner, rank)
  }

  return {
    gameId: 'test-game',
    status: 'IN_PROGRESS',
    youAre: 'RED',
    youPlaced: overrides.youPlaced ?? false,
    currentPlayer: 'RED',
    winner: null,
    winReason: null,
    flagEscapePending: false,
    // a clock running, as it is for any live turn; overrides.turnDeadlineMillis: null turns it off
    turnDeadlineMillis: overrides.turnDeadlineMillis ?? Date.now() + 60_000,
    turnSeconds: overrides.turnSeconds ?? 60,
    turnNumber: 0,
    rows: ROWS,
    cols: COLS,
    yourPiecesRemaining: 21,
    opponentPiecesRemaining: 21,
    board: grid.flat(),
    lastBattle: null,
    log: [],
    seats: overrides.seats ?? [
      { color: 'RED', name: 'Tester', bot: false, you: true, difficulty: null },
      { color: 'BLUE', name: 'Rival', bot: false, you: false, difficulty: null },
    ],
    chat: overrides.chat ?? [],
    ...overrides,
  }
}

/**
 * Blanks an enemy piece's rank the way the server always does for an enemy square:
 * the piece still occupies the square, but its identity is withheld.
 */
export function conceal(game: GameState, row: number, col: number): GameState {
  return {
    ...game,
    board: game.board.map((square) =>
      square.row === row && square.col === col
        ? { ...square, rank: null, rankName: null, revealed: false }
        : square,
    ),
  }
}

export interface BattleOverrides {
  attackerOwner?: PlayerColor
  defenderOwner?: PlayerColor
  /** null for whichever side the viewer does not own — the server redacts it. */
  attackerRankName?: string | null
  defenderRankName?: string | null
  attackerSurvives?: boolean
  defenderSurvives?: boolean
  description?: string
}

/** A battle as the server reports it: the outcome is public, the ranks are not. */
export function makeBattle(overrides: BattleOverrides = {}): Battle {
  const {
    attackerOwner = 'RED',
    defenderOwner = 'BLUE',
    attackerRankName = 'Private',
    defenderRankName = null,
    attackerSurvives = false,
    defenderSurvives = true,
    description = 'an unidentified enemy piece holds the square and your piece is destroyed',
  } = overrides
  return {
    attackerId: 101,
    attackerOwner,
    attackerRank: null,
    attackerRankName,
    fromRow: 4,
    fromCol: 4,
    attackerSurvives,
    defenderId: 202,
    defenderOwner,
    defenderRank: null,
    defenderRankName,
    toRow: 3,
    toCol: 4,
    defenderSurvives,
    description,
  }
}

/** The 21-piece army the server hands out, in roster order. */
export const FULL_ARMY: Rank[] = [
  'FIVE_STAR_GENERAL',
  'FOUR_STAR_GENERAL',
  'THREE_STAR_GENERAL',
  'TWO_STAR_GENERAL',
  'ONE_STAR_GENERAL',
  'COLONEL',
  'LIEUTENANT_COLONEL',
  'MAJOR',
  'CAPTAIN',
  'FIRST_LIEUTENANT',
  'SECOND_LIEUTENANT',
  'SERGEANT',
  'PRIVATE',
  'PRIVATE',
  'PRIVATE',
  'PRIVATE',
  'PRIVATE',
  'PRIVATE',
  'SPY',
  'SPY',
  'FLAG',
]

function roster(): Record<string, number> {
  const counts: Record<string, number> = {}
  for (const rank of FULL_ARMY) {
    counts[rank] = (counts[rank] ?? 0) + 1
  }
  return counts
}

export const TEST_META: Meta = {
  rows: ROWS,
  cols: COLS,
  armySize: 21,
  ranks: (Object.keys(RANK_LABELS) as Rank[]).map((name) => ({
    name,
    displayName: RANK_LABELS[name],
    power: 0,
    officer: !['PRIVATE', 'SPY', 'FLAG'].includes(name),
  })),
  roster: roster(),
}
