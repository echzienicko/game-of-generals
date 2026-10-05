import type { Coordinate, GameState, PlayerColor, Square } from './types'

export const ROWS = 8
export const COLS = 9

export function opponentOf(color: PlayerColor): PlayerColor {
  return color === 'RED' ? 'BLUE' : 'RED'
}

/** Rows 0-2 belong to RED, rows 5-7 to BLUE. */
export function isOwnCamp(row: number, color: PlayerColor): boolean {
  return color === 'RED' ? row <= 2 : row >= 5
}

export function deploymentZone(color: PlayerColor): Coordinate[] {
  const from = color === 'RED' ? 0 : 5
  const to = color === 'RED' ? 2 : 7
  const zone: Coordinate[] = []
  for (let row = from; row <= to; row++) {
    for (let col = 0; col < COLS; col++) {
      zone.push({ row, col })
    }
  }
  return zone
}

export function squareAt(game: GameState, row: number, col: number): Square | undefined {
  return game.board.find((s) => s.row === row && s.col === col)
}

function orthogonalNeighbours(row: number, col: number): Coordinate[] {
  const result: Coordinate[] = []
  if (row > 0) result.push({ row: row - 1, col })
  if (row < ROWS - 1) result.push({ row: row + 1, col })
  if (col > 0) result.push({ row, col: col - 1 })
  if (col < COLS - 1) result.push({ row, col: col + 1 })
  return result
}

/**
 * A piece with no friendly neighbour may advance two squares in a straight line.
 * Mirrors the server rule so the UI can highlight squares; the server still has
 * the final say on every move.
 */
export function isAlone(game: GameState, row: number, col: number, color: PlayerColor): boolean {
  return orthogonalNeighbours(row, col).every((n) => {
    const square = squareAt(game, n.row, n.col)
    return !(square && square.pieceId !== null && square.owner === color)
  })
}

export function legalTargets(game: GameState, from: Coordinate): Coordinate[] {
  const square = squareAt(game, from.row, from.col)
  if (!square || square.pieceId === null || square.owner !== game.youAre) {
    return []
  }
  const targets: Coordinate[] = []
  for (const n of orthogonalNeighbours(from.row, from.col)) {
    const occupant = squareAt(game, n.row, n.col)
    if (!occupant || occupant.pieceId === null || occupant.owner !== game.youAre) {
      targets.push(n)
    }
  }
  if (isAlone(game, from.row, from.col, game.youAre)) {
    for (const n of orthogonalNeighbours(from.row, from.col)) {
      for (const far of orthogonalNeighbours(n.row, n.col)) {
        const dr = Math.abs(far.row - from.row)
        const dc = Math.abs(far.col - from.col)
        const straightTwo = (dr === 2 && dc === 0) || (dr === 0 && dc === 2)
        if (!straightTwo) continue
        const occupant = squareAt(game, far.row, far.col)
        if (!occupant || occupant.pieceId === null) {
          targets.push(far)
        }
      }
    }
  }
  return targets
}

export function isYourTurn(game: GameState): boolean {
  return game.status === 'IN_PROGRESS' && game.currentPlayer === game.youAre
}

/**
 * Renders the board with the player's own camp at the bottom, the way a player
 * looking across a real board would see it.
 */
export function displayRows(game: GameState): number[] {
  return game.youAre === 'RED' ? [7, 6, 5, 4, 3, 2, 1, 0] : [0, 1, 2, 3, 4, 5, 6, 7]
}

export function squareKey(row: number, col: number): string {
  return `${row},${col}`
}

function labelFor(row: number, col: number): string {
  return String.fromCharCode(65 + col) + String(row + 1)
}

/**
 * A local guess at a quiet move, so the piece does not sit frozen while the
 * authoritative push is in flight. Only empty squares are handled: resolving a
 * battle here would mean keeping a second copy of the server's precedence rules
 * in sync, and the push is what drives the battle overlay anyway.
 *
 * Returns null when the destination is occupied, which tells the caller to leave
 * the board alone and wait for the server. Nothing is written for turnNumber, the
 * log or the piece counts, because only the server knows those.
 */
export function applyQuietMove(
  game: GameState,
  from: Coordinate,
  to: Coordinate,
): GameState | null {
  const source = squareAt(game, from.row, from.col)
  const target = squareAt(game, to.row, to.col)
  if (!source || source.pieceId === null || source.owner !== game.youAre) {
    return null
  }
  if (!target || target.pieceId !== null) {
    return null
  }
  if (source.row === to.row && source.col === to.col) {
    return null
  }

  const board = game.board.map((square) => {
    if (square.row === from.row && square.col === from.col) {
      return {
        ...square,
        pieceId: null,
        owner: null,
        rank: null,
        rankName: null,
        revealed: false,
      }
    }
    if (square.row === to.row && square.col === to.col) {
      return { ...square, ...source, row: to.row, col: to.col, label: labelFor(to.row, to.col) }
    }
    return square
  })

  return { ...game, board }
}
