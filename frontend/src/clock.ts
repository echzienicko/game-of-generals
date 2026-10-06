/** The turn clock's arithmetic, kept out of the component so it can be tested on its own. */

/** At or below this many seconds the clock changes its tone. */
export const CLOCK_WARN_SECONDS = 10

/** Whole seconds left, never negative — a turn that is over shows zero, not a debt. */
export function secondsLeft(deadlineMillis: number, now: number): number {
  return Math.max(0, Math.ceil((deadlineMillis - now) / 1000))
}

/**
 * How much of the turn is left, as a percentage of it.
 *
 * <p>Clamped, because the two clocks are not one clock: a browser whose time is behind the
 * server's would otherwise fill the bar past its end and read as a turn longer than any
 * turn is.
 */
export function shareLeft(left: number, turnSeconds: number): number {
  if (turnSeconds <= 0) return 0
  return Math.min(100, Math.max(0, Math.round((left / turnSeconds) * 100)))
}