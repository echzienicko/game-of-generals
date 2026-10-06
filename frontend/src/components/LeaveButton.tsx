import { useState } from 'react'
import { useGame } from '../state/GameContext'

/**
 * The way out of a live game, and the only one that costs you the match.
 *
 * <p>Two steps, because there is no undo: the first press asks, the second goes. A single
 * tap on a phone in a pocket would otherwise hand the game to the opponent, and the server
 * has already declared the winner the moment the request lands.
 *
 * <p>This is deliberately not the way out of the waiting room — there is nobody to beat
 * there, and leaving one is free. See {@code leaveGame} for that.
 */
export function LeaveButton() {
  const { resign, busy } = useGame()
  const [confirming, setConfirming] = useState(false)

  if (confirming) {
    return (
      <span className="leave">
        <span className="leave__ask">Leave and lose the game?</span>
        <button
          type="button"
          className="btn btn--primary"
          onClick={() => void resign()}
          disabled={busy}
        >
          {busy ? 'Leaving…' : 'Yes, leave'}
        </button>
        <button type="button" className="btn btn--ghost" onClick={() => setConfirming(false)}>
          Stay
        </button>
      </span>
    )
  }

  return (
    <button type="button" className="btn btn--ghost" onClick={() => setConfirming(true)}>
      Leave
    </button>
  )
}
