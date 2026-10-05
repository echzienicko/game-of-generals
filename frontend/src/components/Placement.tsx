import { useMemo, useState } from 'react'
import { useGame } from '../state/GameContext'
import { useMeta, labelFor } from '../hooks/useMeta'
import { deploymentZone, squareKey } from '../rules'
import { PieceView } from './PieceView'
import { ChatPanel } from './ChatPanel'
import { opponentLabel } from '../seats'
import type { Coordinate, Rank } from '../types'

const FALLBACK_ROSTER: Record<Rank, number> = {
  FIVE_STAR_GENERAL: 1,
  FOUR_STAR_GENERAL: 1,
  THREE_STAR_GENERAL: 1,
  TWO_STAR_GENERAL: 1,
  ONE_STAR_GENERAL: 1,
  COLONEL: 1,
  LIEUTENANT_COLONEL: 1,
  MAJOR: 1,
  CAPTAIN: 1,
  FIRST_LIEUTENANT: 1,
  SECOND_LIEUTENANT: 1,
  SERGEANT: 1,
  PRIVATE: 6,
  SPY: 2,
  FLAG: 1,
}

/** Front row nearest the centre line first, back row last, whichever side you are. */
function campRows(color: string): number[] {
  return color === 'RED' ? [2, 1, 0] : [5, 6, 7]
}

export function Placement() {
  const { game, submitPlacement, busy, error, clearError } = useGame()
  const meta = useMeta()
  const color = game?.youAre ?? 'RED'
  const side: 'red' | 'blue' = color === 'RED' ? 'red' : 'blue'
  const yourSeat =
    game?.seats.find((seat) => seat.color === color)?.name ??
    (color === 'RED' ? 'Red' : 'Blue')

  const [board, setBoard] = useState<Map<string, Rank>>(() => new Map())
  const [holding, setHolding] = useState<Rank | null>(null)

  const roster = useMemo<Record<string, number>>(
    () => meta?.roster ?? FALLBACK_ROSTER,
    [meta],
  )

  const zone = useMemo(() => deploymentZone(color), [color])
  const rows = useMemo(() => campRows(color), [color])

  const remaining = useMemo(() => {
    const counts: Partial<Record<Rank, number>> = {}
    for (const [rank, count] of Object.entries(roster)) {
      counts[rank as Rank] = count
    }
    for (const rank of board.values()) {
      counts[rank] = (counts[rank] ?? 0) - 1
    }
    return counts
  }, [roster, board])

  const placed = board.size
  const total = Object.values(roster).reduce((sum, n) => sum + n, 0)
  const complete = placed === total

  function clickTray(rank: Rank) {
    if ((remaining[rank] ?? 0) <= 0) return
    clearError()
    setHolding((current) => (current === rank ? null : rank))
  }

  function clickSquare(square: Coordinate) {
    clearError()
    const key = squareKey(square.row, square.col)
    const existing = board.get(key)
    if (existing) {
      // pick the piece back up so it can be moved elsewhere
      const next = new Map(board)
      next.delete(key)
      setBoard(next)
      setHolding(existing)
      return
    }
    if (holding && (remaining[holding] ?? 0) > 0) {
      setBoard(new Map(board).set(key, holding))
      setHolding(null)
    }
  }

  function randomize() {
    clearError()
    const pool: Rank[] = []
    for (const [rank, count] of Object.entries(roster)) {
      for (let i = 0; i < count; i++) {
        pool.push(rank as Rank)
      }
    }
    for (let i = pool.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1))
      ;[pool[i], pool[j]] = [pool[j], pool[i]]
    }
    const next = new Map<string, Rank>()
    // The camp has 27 squares but the army has 21 pieces: fill only what fits.
    zone.slice(0, pool.length).forEach((square, index) => {
      next.set(squareKey(square.row, square.col), pool[index])
    })
    setBoard(next)
    setHolding(null)
  }

  function clear() {
    clearError()
    setBoard(new Map())
    setHolding(null)
  }

  function ready() {
    if (!complete) return
    const pieces = [...board.entries()].map(([key, rank]) => {
      const [row, col] = key.split(',').map(Number)
      return { row: row as number, col: col as number, rank }
    })
    void submitPlacement(pieces)
  }

  return (
    <main className="placement">
      <header className="placement__header">
        <div>
          <h1>Deploy your army</h1>
          <p className="placement__hint">
            You play as{' '}
            <strong className={`side side--${color.toLowerCase()}`}>
              <span className="side__name">{yourSeat}</span>
              <span className="side__color">{color}</span>
            </strong>{' '}
            against {game ? opponentLabel(game) : 'them'}. Click a piece below, then a square in
            your camp. Click a placed piece to pick it back up.
          </p>
        </div>
        <div className="placement__progress">
          <span className={complete ? 'progress__count progress__count--done' : 'progress__count'}>
            {placed} / {total}
          </span>
          <div className="placement__actions">
            <button type="button" className="btn" onClick={randomize} disabled={busy}>
              Randomise
            </button>
            <button type="button" className="btn" onClick={clear} disabled={busy || placed === 0}>
              Clear
            </button>
            <button
              type="button"
              className="btn btn--primary"
              onClick={ready}
              disabled={!complete || busy}
            >
              {busy ? 'Sending…' : 'Ready'}
            </button>
          </div>
        </div>
      </header>

      {error && (
        <div className="alert alert--error" role="alert">
          {error}
        </div>
      )}

      <div className="placement__layout">
        <section className="board board--camp" aria-label="Your camp">
          <div className="board__grid">
            {rows.map((row) => (
              <div className="board__row" key={row}>
                {Array.from({ length: 9 }, (_, col) => {
                  const key = squareKey(row, col)
                  const rank = board.get(key)
                  return (
                    <button
                      type="button"
                      key={key}
                      className={`square square--mine ${
                        holding ? 'square--target' : ''
                      } ${rank ? 'square--filled' : ''}`}
                      onClick={() => clickSquare({ row, col })}
                      aria-label={`Row ${row} column ${col}${rank ? `, ${labelFor(rank)}` : ', empty'}`}
                    >
                      {rank && (
                        <PieceView
                          rank={rank}
                          color={side}
                          size="tray"
                          announce={false}
                        />
                      )}
                    </button>
                  )
                })}
              </div>
            ))}
          </div>
        </section>

        <aside className="tray" aria-label="Available pieces">
          <h2 className="tray__title">Army</h2>
          <ul className="tray__list">
            {Object.entries(roster).map(([rank]) => {
              const left = remaining[rank as Rank] ?? 0
              return (
                <li key={rank}>
                  <button
                    type="button"
                    className={`tray__item ${
                      holding === rank ? 'tray__item--selected' : ''
                    } ${left === 0 ? 'tray__item--spent' : ''}`}
                    onClick={() => clickTray(rank as Rank)}
                    disabled={left === 0}
                    title={labelFor(rank as Rank)}
                  >
                    <PieceView
                      rank={rank as Rank}
                      color={side}
                      size="tray"
                      announce={false}
                    />
                    <span className="tray__name">{labelFor(rank as Rank)}</span>
                    <span className="tray__count">
                      {left}
                      <span className="sr-only"> remaining</span>
                    </span>
                  </button>
                </li>
              )
            })}
          </ul>
        </aside>
      </div>

      <div className="placement__chat">
        <ChatPanel />
      </div>
    </main>
  )
}
