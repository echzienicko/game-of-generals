import { difficultyLabel } from './bot'
import type { GameState, PlayerColor } from './types'

/**
 * How the two players are named on screen.
 *
 * <p>Names are the point: "RED" and "BLUE" tell you which half of the board is yours,
 * which tells you nothing about who is on the other side of the table. These live outside
 * a component file because several components label the same two seats, and a helper
 * exported from a component file would make the whole module hot-reload badly.
 *
 * <p>Every fallback is a colour rather than a blank, so a screen never says nothing where
 * it should say who — an old client or a hand-written state has no seats, and a blank chip
 * looks broken.
 */
type Seats = Pick<GameState, 'seats' | 'youAre'>

type SeatDifficulty = Seats['seats'][number]['difficulty']

export function yourLabel(game: Seats): string {
  const seat = game.seats.find((s) => s.color === game.youAre)
  if (!seat) return colourName(game.youAre)
  if (seat.bot) return computerLabel(seat.difficulty)
  return seat.name ?? colourName(game.youAre)
}

export function opponentLabel(game: Seats): string {
  const seat = game.seats.find((s) => s.color !== game.youAre)
  if (!seat) return 'Opponent'
  if (seat.bot) return computerLabel(seat.difficulty)
  return seat.name ?? colourName(seat.color)
}

/** The name a seat is known by elsewhere — the screen a seat is not on. */
export function labelForColor(
  game: Seats,
  color: PlayerColor,
  fallback: string = colourName(color),
): string {
  const seat = game.seats.find((s) => s.color === color)
  if (!seat) return fallback
  if (seat.bot) return computerLabel(seat.difficulty)
  return seat.name ?? colourName(color)
}

/**
 * The computer, named by the level it is playing.
 *
 * <p>"Computer" alone leaves the player guessing after a refresh which of the three they
 * started, and the level is the one thing about the opponent a player is allowed to know —
 * they chose it themselves. An old or hand-written state with no difficulty on the seat
 * still reads as a plain "Computer" rather than as a blank.
 */
function computerLabel(difficulty: SeatDifficulty): string {
  const label = difficultyLabel(difficulty)
  return label ? `Computer (${label})` : 'Computer'
}

function colourName(color: PlayerColor): string {
  return color === 'RED' ? 'Red' : 'Blue'
}