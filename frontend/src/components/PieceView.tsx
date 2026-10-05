import type { Rank } from '../types'
import { RANK_SHORT, labelFor } from '../hooks/useMeta'
import { PieceEmblem } from './PieceEmblem'

interface PieceViewProps {
  rank: Rank
  /** Unknown enemy piece: rendered face-down so its identity stays hidden. */
  hidden?: boolean
  color: 'red' | 'blue'
  size?: 'board' | 'tray'
  /**
   * Set false when the surrounding control already names the piece in visible text, so a
   * screen reader does not hear the rank twice.
   */
  announce?: boolean
}

export function PieceView({ rank, hidden = false, color, size = 'board', announce = true }: PieceViewProps) {
  if (hidden) {
    // Deliberately no emblem: the pips vary by rank, so stamping one here would reveal it.
    return (
      <span className={`piece piece--${color} piece--face-down piece--${size}`} title="Unknown enemy piece">
        <span aria-hidden="true">?</span>
        <span className="sr-only">Unknown enemy piece</span>
      </span>
    )
  }

  const short = RANK_SHORT[rank]
  const isFlag = rank === 'FLAG'
  const isSpy = rank === 'SPY'
  const isPrivate = rank === 'PRIVATE'

  return (
    <span
      className={`piece piece--${color} piece--${size} ${
        isFlag ? 'piece--flag' : isSpy ? 'piece--spy' : isPrivate ? 'piece--private' : 'piece--officer'
      }`}
      title={labelFor(rank)}
    >
      <PieceEmblem rank={rank} />
      <span aria-hidden="true">{short}</span>
      {announce && <span className="sr-only">{labelFor(rank)}</span>}
    </span>
  )
}
