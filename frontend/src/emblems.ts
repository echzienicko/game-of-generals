import type { Rank } from './types'

/**
 * Officer pips per rank, weakest first.
 *
 * <p>The physical game distinguishes officers by the number of pips stamped on the piece,
 * so the emblem does the same: more pips means a stronger officer, which reads correctly
 * at a glance and needs no legend to interpret. The three non-officer ranks get their own
 * glyph instead — the flag and the spy each win and lose in ways no officer does, and a
 * one-pip private would otherwise be indistinguishable from a Sergeant.
 */
export const OFFICER_PIPS: Record<Rank, number> = {
  SERGEANT: 1,
  SECOND_LIEUTENANT: 2,
  FIRST_LIEUTENANT: 3,
  CAPTAIN: 4,
  MAJOR: 5,
  LIEUTENANT_COLONEL: 6,
  COLONEL: 7,
  ONE_STAR_GENERAL: 8,
  TWO_STAR_GENERAL: 9,
  THREE_STAR_GENERAL: 10,
  FOUR_STAR_GENERAL: 11,
  FIVE_STAR_GENERAL: 12,
  PRIVATE: 0,
  SPY: 0,
  FLAG: 0,
}

/** Officer ranks ordered weakest to strongest, the order the pips count follows. */
export const OFFICER_ORDER: Rank[] = [
  'SERGEANT',
  'SECOND_LIEUTENANT',
  'FIRST_LIEUTENANT',
  'CAPTAIN',
  'MAJOR',
  'LIEUTENANT_COLONEL',
  'COLONEL',
  'ONE_STAR_GENERAL',
  'TWO_STAR_GENERAL',
  'THREE_STAR_GENERAL',
  'FOUR_STAR_GENERAL',
  'FIVE_STAR_GENERAL',
]