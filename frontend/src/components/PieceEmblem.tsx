import { OFFICER_PIPS } from '../emblems'
import type { Rank } from '../types'

/**
 * The mark stamped on a face-up piece: pips for officers (see {@link OFFICER_PIPS}), and a
 * glyph for the three ranks that are not officers.
 *
 * <p>Always `aria-hidden`. A piece already announces its rank through screen-reader text,
 * and this must not become a second, differently-worded announcement of the same thing.
 *
 * <p>Nothing here may render for a face-down piece — that is the whole fog of war, and an
 * emblem that varies by rank would hand the rank straight over.
 */
export function PieceEmblem({ rank }: { rank: Rank }) {
  const pips = OFFICER_PIPS[rank]

  return (
    <span className="piece__emblem" aria-hidden="true">
      {pips > 0 ? <Pips count={pips} /> : <Glyph rank={rank} />}
    </span>
  )
}

/** Laid out four to a row, so twelve pips take three rows and stay square. */
function Pips({ count }: { count: number }) {
  return (
    <span className="piece__pips">
      {Array.from({ length: count }, (_, i) => (
        <span key={i} className="piece__pip" />
      ))}
    </span>
  )
}

function Glyph({ rank }: { rank: Rank }) {
  if (rank === 'FLAG') {
    return (
      <svg className="piece__glyph" viewBox="0 0 14 12" fill="none" stroke="currentColor">
        <path d="M2 11.5V1" strokeWidth="1.7" strokeLinecap="round" />
        <path d="M2.6 1.6h9.2l-2.1 3.2 2.1 3.2H2.6z" fill="currentColor" strokeWidth="0" />
      </svg>
    )
  }
  if (rank === 'SPY') {
    return (
      <svg className="piece__glyph" viewBox="0 0 14 10" fill="none" stroke="currentColor">
        <path d="M.8 5S3 1.2 7 1.2 13.2 5 13.2 5 11 8.8 7 8.8.8 5 .8 5Z" strokeWidth="1.4" strokeLinejoin="round" />
        <circle cx="7" cy="5" r="1.7" fill="currentColor" strokeWidth="0" />
      </svg>
    )
  }
  // Private: a single pip would be a Sergeant's, so this is a chevron instead.
  return (
    <svg className="piece__glyph" viewBox="0 0 10 12" fill="currentColor">
      <path d="M1.2 0.6 8 6l-6.8 5.4z" />
    </svg>
  )
}