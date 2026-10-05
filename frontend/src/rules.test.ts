import { describe, expect, it } from 'vitest'
import {
  applyQuietMove,
  displayRows,
  isAlone,
  isOwnCamp,
  isYourTurn,
  legalTargets,
  squareAt,
  squareKey,
} from './rules'
import { conceal, labelOf, makeGame, resetPieceIds } from './test/factories'
import type { GameState } from './types'

function gameWith(
  pieces: Array<[number, number, 'RED' | 'BLUE', Parameters<typeof makeGame>[0][number][3]]>,
  overrides = {},
): GameState {
  resetPieceIds()
  return makeGame(pieces, overrides)
}

const keys = (coords: Array<{ row: number; col: number }>) =>
  coords.map((c) => squareKey(c.row, c.col)).sort()

describe('camp ownership', () => {
  it('gives RED rows 0-2 and BLUE rows 5-7', () => {
    expect([0, 1, 2].every((row) => isOwnCamp(row, 'RED'))).toBe(true)
    expect([3, 4].some((row) => isOwnCamp(row, 'RED'))).toBe(false)
    expect([5, 6, 7].every((row) => isOwnCamp(row, 'BLUE'))).toBe(true)
    expect([0, 1, 2].some((row) => isOwnCamp(row, 'BLUE'))).toBe(false)
  })
})

describe('displayRows', () => {
  it("puts each player's own camp at the bottom of the screen", () => {
    const asRed = makeGame([])
    expect(displayRows(asRed)[0]).toBe(7)
    expect(displayRows(asRed).at(-1)).toBe(0)

    const asBlue = makeGame([], { youAre: 'BLUE' })
    expect(displayRows(asBlue)[0]).toBe(0)
    expect(displayRows(asBlue).at(-1)).toBe(7)
  })

  it('always covers every row exactly once', () => {
    for (const youAre of ['RED', 'BLUE'] as const) {
      const rows = displayRows(makeGame([], { youAre }))
      expect([...rows].sort((a, b) => a - b)).toEqual([0, 1, 2, 3, 4, 5, 6, 7])
    }
  })
})

describe('isAlone', () => {
  it('is false when a friendly piece touches any orthogonal square', () => {
    const game = gameWith([
      [2, 0, 'RED', 'PRIVATE'],
      [2, 1, 'RED', 'PRIVATE'],
    ])
    expect(isAlone(game, 2, 0, 'RED')).toBe(false)
  })

  it('does not count enemies as company', () => {
    const game = gameWith([
      [2, 0, 'RED', 'PRIVATE'],
      [2, 1, 'BLUE', 'PRIVATE'],
    ])
    expect(isAlone(game, 2, 0, 'RED')).toBe(true)
  })

  it('does not count diagonals as company', () => {
    const game = gameWith([
      [2, 0, 'RED', 'PRIVATE'],
      [3, 1, 'RED', 'PRIVATE'],
    ])
    expect(isAlone(game, 2, 0, 'RED')).toBe(true)
  })
})

describe('legalTargets', () => {
  it('offers the four orthogonal squares of a crowded piece', () => {
    const game = gameWith([
      [2, 0, 'RED', 'PRIVATE'],
      [2, 1, 'RED', 'PRIVATE'],
    ])
    expect(keys(legalTargets(game, { row: 2, col: 0 }))).toEqual(
      keys([
        { row: 1, col: 0 },
        { row: 3, col: 0 },
      ]),
    )
  })

  it('adds the straight two-square leap for a piece that is alone', () => {
    const game = gameWith([[2, 0, 'RED', 'PRIVATE']])
    // "two squares in a straight line" is not direction-restricted: the server's
    // Position.isTwoStepsInStraightLineTo accepts dr==2/dc==0 and dr==0/dc==2, so
    // the sideways leap is legal here too. This list must stay in step with it.
    expect(keys(legalTargets(game, { row: 2, col: 0 }))).toEqual(
      keys([
        { row: 1, col: 0 },
        { row: 2, col: 1 },
        { row: 3, col: 0 },
        { row: 0, col: 0 },
        { row: 4, col: 0 },
        { row: 2, col: 2 },
      ]),
    )
  })

  it('never offers a two-square leap onto an occupied square', () => {
    const game = gameWith([
      [2, 0, 'RED', 'PRIVATE'],
      [4, 0, 'BLUE', 'PRIVATE'],
    ])
    const targets = legalTargets(game, { row: 2, col: 0 })
    expect(targets.some((t) => t.row === 4 && t.col === 0)).toBe(false)
  })

  it('never offers a corner-cutting double move', () => {
    const game = gameWith([[3, 3, 'RED', 'PRIVATE']])
    const targets = legalTargets(game, { row: 3, col: 3 })
    // two squares diagonally is not a straight line
    expect(targets.some((t) => Math.abs(t.row - 3) === 2 && Math.abs(t.col - 3) === 2)).toBe(false)
  })

  it('lets a piece attack an enemy square but not a friendly one', () => {
    const game = gameWith([
      [2, 0, 'RED', 'PRIVATE'],
      [3, 0, 'BLUE', 'PRIVATE'],
      [2, 1, 'RED', 'PRIVATE'],
    ])
    const targets = legalTargets(game, { row: 2, col: 0 })
    expect(targets.some((t) => t.row === 3 && t.col === 0)).toBe(true)
    expect(targets.some((t) => t.row === 2 && t.col === 1)).toBe(false)
  })

  it('offers nothing for an enemy or empty square', () => {
    const game = gameWith([[5, 5, 'BLUE', 'FLAG']])
    expect(legalTargets(game, { row: 5, col: 5 })).toEqual([])
    expect(legalTargets(game, { row: 4, col: 4 })).toEqual([])
  })
})

describe('isYourTurn', () => {
  it('is true only in progress and only on your own turn', () => {
    expect(isYourTurn(makeGame([], { currentPlayer: 'RED' }))).toBe(true)
    expect(isYourTurn(makeGame([], { currentPlayer: 'BLUE' }))).toBe(false)
    expect(
      isYourTurn(makeGame([], { currentPlayer: 'RED', status: 'PLACEMENT' })),
    ).toBe(false)
    expect(
      isYourTurn(makeGame([], { currentPlayer: 'RED', status: 'FINISHED' })),
    ).toBe(false)
  })
})

describe('applyQuietMove', () => {
  const setup = () =>
    gameWith([
      [2, 0, 'RED', 'FIVE_STAR_GENERAL'],
      [2, 1, 'RED', 'PRIVATE'],
    ])

  it('moves a piece onto an empty square and vacuums the old one', () => {
    const before = setup()
    const after = applyQuietMove(before, { row: 2, col: 0 }, { row: 3, col: 0 })

    expect(after).not.toBeNull()
    expect(squareAt(after!, 3, 0)).toMatchObject({
      pieceId: squareAt(before, 2, 0)!.pieceId,
      owner: 'RED',
      rank: 'FIVE_STAR_GENERAL',
      rankName: '5★ General',
      label: 'A4',
    })
    expect(squareAt(after!, 2, 0)).toMatchObject({
      pieceId: null,
      owner: null,
      rank: null,
      rankName: null,
    })
  })

  it('leaves the original state object untouched', () => {
    const before = setup()
    const snapshot = JSON.stringify(before)
    applyQuietMove(before, { row: 2, col: 0 }, { row: 3, col: 0 })
    expect(JSON.stringify(before)).toBe(snapshot)
  })

  it('declines a move onto an occupied square so the server can resolve the battle', () => {
    const before = gameWith([
      [2, 0, 'RED', 'PRIVATE'],
      [3, 0, 'BLUE', 'FLAG'],
    ])
    expect(applyQuietMove(before, { row: 2, col: 0 }, { row: 3, col: 0 })).toBeNull()
  })

  it('declines a move onto a friendly piece', () => {
    const before = setup()
    expect(applyQuietMove(before, { row: 2, col: 0 }, { row: 2, col: 1 })).toBeNull()
  })

  it('declines a move from an empty or enemy square', () => {
    const before = gameWith([[5, 0, 'BLUE', 'PRIVATE']])
    expect(applyQuietMove(before, { row: 4, col: 0 }, { row: 3, col: 0 })).toBeNull()
    expect(applyQuietMove(before, { row: 5, col: 0 }, { row: 4, col: 0 })).toBeNull()
  })

  it('declines a move onto the square it started from', () => {
    const before = setup()
    expect(applyQuietMove(before, { row: 2, col: 0 }, { row: 2, col: 0 })).toBeNull()
  })

  it('invents nothing the server owns: no turn number, log or piece counts', () => {
    const before = gameWith([[2, 0, 'RED', 'PRIVATE']], { turnNumber: 7, log: ['a log line'] })
    const after = applyQuietMove(before, { row: 2, col: 0 }, { row: 3, col: 0 })!
    expect(after.turnNumber).toBe(7)
    expect(after.log).toEqual(['a log line'])
    expect(after.yourPiecesRemaining).toBe(before.yourPiecesRemaining)
    expect(after.currentPlayer).toBe(before.currentPlayer)
  })

  it('carries an unrevealed enemy piece across untouched when it is the mover', () => {
    // the mover is always our own piece, but a concealed enemy elsewhere on the
    // board must stay concealed through the local guess
    const before = conceal(setup(), 5, 5)
    const after = applyQuietMove(before, { row: 2, col: 0 }, { row: 3, col: 0 })!
    expect(squareAt(after, 5, 5)).toMatchObject({ rank: null, revealed: false })
  })

  it('relabels the destination square in the server A1 style', () => {
    const before = setup()
    const after = applyQuietMove(before, { row: 2, col: 0 }, { row: 3, col: 0 })!
    expect(squareAt(after, 3, 0)!.label).toBe(labelOf(3, 0))
    expect(squareAt(after, 3, 0)!.label).toBe('A4')
  })
})
