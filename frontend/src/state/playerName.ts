/**
 * The name a player is recorded under.
 *
 * <p>A name is asked for once, on the very first visit, and kept in the browser. It is the
 * whole of a player's identity in the win table, so the same rules apply here as on the
 * server: whitespace is collapsed, and a name longer than the column or holding characters
 * that cannot be shown is refused. Checking the same rule on both sides means the message
 * arrives without a round trip, and a client that has been tampered with still gets refused
 * by the server.
 */
export const NAME_STORAGE_KEY = 'generals.player'

/** Matches Leaderboard.MAX_NAME_LENGTH on the server. */
export const MAX_NAME_LENGTH = 24

/**
 * Tidies a typed name, or returns null with the reason it cannot be used.
 *
 * <p>A line break inside a name becomes a space rather than being removed, so "Nick" and
 * "Ni ck" are not silently the same string.
 */
export function tidyName(raw: string): { name: string } | { error: string } {
  const name = raw.replace(/\s+/g, ' ').trim()
  if (!name) {
    return { error: 'Enter a name so your wins can be recorded' }
  }
  if (name.length > MAX_NAME_LENGTH) {
    return { error: `Keep it to ${MAX_NAME_LENGTH} characters or fewer` }
  }
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f]/.test(name.replace(/[\t\n\r\f\v]/g, ''))) {
    return { error: 'That name has characters we cannot show' }
  }
  return { name }
}

export function readStoredName(): string | null {
  try {
    const raw = window.localStorage.getItem(NAME_STORAGE_KEY)
    if (!raw) return null
    const tidy = tidyName(raw)
    return 'name' in tidy ? tidy.name : null
  } catch {
    // a corrupt or unavailable store just means we ask again
    return null
  }
}

export function storeName(name: string) {
  try {
    window.localStorage.setItem(NAME_STORAGE_KEY, name)
  } catch {
    // private browsing: the name lives as long as the tab, which is still playable
  }
}
