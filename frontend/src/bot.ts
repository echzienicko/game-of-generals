import type { BotDifficulty } from './types'

/**
 * How hard the computer tries, as the player is offered it.
 *
 * <p>The wording is taken from the bot's own rules rather than invented here, because the
 * three levels are a progression and a label that flatters one of them is a lie the player
 * finds out about mid-game: RANDOM picks any legal move and walks into things, HEURISTIC
 * scores every legal move, and LEARNING is the same plus a memory of the openings that have
 * lost. The server's own default is LEARNING, so that is what the picker opens on — a
 * client that quietly picked something else would play a different game from the one the
 * bare endpoint serves.
 *
 * <p>Not a component file: the lobby offers these and {@link module:seats} names the one
 * actually in play, and a helper exported from a component file would make the whole module
 * hot-reload badly.
 */
export interface DifficultyChoice {
  value: BotDifficulty
  label: string
  /** One sentence, saying what the level does rather than how hard it feels. */
  hint: string
}

export const DIFFICULTY_CHOICES: DifficultyChoice[] = [
  {
    value: 'RANDOM',
    label: 'Easy',
    hint: 'Picks a legal move at random, and will walk into anything.',
  },
  {
    value: 'HEURISTIC',
    label: 'Normal',
    hint: 'Scores every legal move and takes the best, with a little noise.',
  },
  {
    value: 'LEARNING',
    label: 'Hard',
    hint: 'The same, and it remembers the openings that have lost it games.',
  },
]

/** The default, which has to be the one the server uses when nothing is asked for. */
export const DEFAULT_DIFFICULTY: BotDifficulty = 'LEARNING'

/** How a level is named on screen: "Hard", or "Normal" beside the board. */
export function difficultyLabel(difficulty: BotDifficulty | null | undefined): string {
  if (!difficulty) return ''
  return DIFFICULTY_CHOICES.find((choice) => choice.value === difficulty)?.label ?? difficulty
}