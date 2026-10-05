import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { Meta, Rank } from '../types'

let cached: Meta | null = null
let inflight: Promise<Meta> | null = null

export const RANK_LABELS: Record<Rank, string> = {
  FIVE_STAR_GENERAL: '5★ General',
  FOUR_STAR_GENERAL: '4★ General',
  THREE_STAR_GENERAL: '3★ General',
  TWO_STAR_GENERAL: '2★ General',
  ONE_STAR_GENERAL: '1★ General',
  COLONEL: 'Colonel',
  LIEUTENANT_COLONEL: 'Lt. Colonel',
  MAJOR: 'Major',
  CAPTAIN: 'Captain',
  FIRST_LIEUTENANT: '1st Lt.',
  SECOND_LIEUTENANT: '2nd Lt.',
  SERGEANT: 'Sergeant',
  PRIVATE: 'Private',
  SPY: 'Spy',
  FLAG: 'Flag',
}

/** Compact label that fits inside a single board square. */
export const RANK_SHORT: Record<Rank, string> = {
  FIVE_STAR_GENERAL: '5★',
  FOUR_STAR_GENERAL: '4★',
  THREE_STAR_GENERAL: '3★',
  TWO_STAR_GENERAL: '2★',
  ONE_STAR_GENERAL: '1★',
  COLONEL: 'Col',
  LIEUTENANT_COLONEL: 'LtCol',
  MAJOR: 'Maj',
  CAPTAIN: 'Capt',
  FIRST_LIEUTENANT: '1Lt',
  SECOND_LIEUTENANT: '2Lt',
  SERGEANT: 'Sgt',
  PRIVATE: 'P',
  SPY: 'Spy',
  FLAG: 'Flag',
}

export function labelFor(rank: Rank): string {
  return RANK_LABELS[rank]
}

/** Loads /api/meta once per page load; the roster and board size never change. */
export function useMeta(): Meta | null {
  const [meta, setMeta] = useState<Meta | null>(cached)

  useEffect(() => {
    if (cached) {
      return
    }
    let active = true
    inflight ??= api.meta()
    inflight
      .then((result) => {
        cached = result
        if (active) {
          setMeta(result)
        }
      })
      .catch(() => {
        inflight = null
      })
    return () => {
      active = false
    }
  }, [])

  return meta
}
