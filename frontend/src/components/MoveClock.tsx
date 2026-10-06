import { useEffect, useState } from 'react'
import { CLOCK_WARN_SECONDS, secondsLeft, shareLeft } from '../clock'
import type { GameState } from '../types'

/**
 * How often the number is recomputed.
 *
 * A tenth of a second is finer than the display can show, which is the point: the number
 * flips on the second it is due rather than up to a tick late.
 */
const TICK_MS = 100

/**
 * The time left on the move.
 *
 * <p>Read from the server's deadline, never counted locally. The server is what ends the
 * turn and plays the move; a countdown this component counted for itself could disagree
 * with that by however long a push was delayed, and would show "3 seconds left" over a
 * game that had already moved on. What is left is therefore always measured against
 * {@link Date.now} at render time, from an instant the server chose.
 *
 * <p>The one thing this trusts is that the two clocks agree to within a second. Nothing in
 * the payload says what time it was on the server, so a machine whose clock is minutes out
 * shows a number that is minutes out. Putting the server's instant in the view would fix
 * that, at the cost of a field that has to be kept honest on every push.
 *
 * <p>`role="timer"` with the announcement turned off: the number changes every second, and a
 * live region that says a second is a live region nobody can listen to. It is still there
 * to be read on purpose, which is what the label inside it is for.
 */
export function MoveClock({ game }: { game: GameState }) {
  const deadline = game.turnDeadlineMillis
  const [sample, setSample] = useState(() => ({ for: deadline, at: Date.now() }))

  useEffect(() => {
    if (deadline == null) return
    const timer = setInterval(() => setSample({ for: deadline, at: Date.now() }), TICK_MS)
    return () => clearInterval(timer)
  }, [deadline])

  if (deadline == null || game.status !== 'IN_PROGRESS') return null

  // A sample is only worth anything for the deadline it was taken against, so the render
  // after a new turn measures afresh instead of waiting for the next tick — which is what
  // a setState in the effect above would have done, one render later and with a warning.
  // Reading the clock here is the point of the component and is as impure as it sounds: the
  // alternative is to keep the old reading for a tick, which puts "119s" on a 60s turn.
  // oxlint-disable-next-line react/purity
  const now = sample.for === deadline ? sample.at : Date.now()
  const left = secondsLeft(deadline, now)
  const tone = left <= 0 ? 'expired' : left <= CLOCK_WARN_SECONDS ? 'warn' : 'calm'
  const yours = game.currentPlayer === game.youAre

  return (
    <div className={`clock clock--${tone}`} role="timer" aria-live="off">
      <span className="sr-only">
        {yours ? 'Time left on your move' : 'Time left on their move'}:{' '}
      </span>
      <span className="clock__time">{left}s</span>
      <span className="clock__track" aria-hidden="true">
        <span className="clock__fill" style={{ width: `${shareLeft(left, game.turnSeconds)}%` }} />
      </span>
    </div>
  )
}