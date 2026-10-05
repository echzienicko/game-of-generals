import { useCallback, useEffect, useState } from 'react'
import type { CSSProperties } from 'react'
import { api, ApiRequestError } from '../api/client'
import { useGame } from '../state/GameContext'
import { useLinkHandler } from '../routing'
import type { LeaderboardEntry } from '../types'

/**
 * The win table.
 *
 * <p>Read straight from the server on each visit rather than kept in the game store: a
 * score is shared between everyone playing under a name, and it survives a server restart,
 * so there is nothing authoritative to cache it against.
 */
export function LeaderboardPage() {
  const { playerName } = useGame()
  const toLobby = useLinkHandler('/lobby')
  const [entries, setEntries] = useState<LeaderboardEntry[] | null>(null)
  const [totalPlayers, setTotalPlayers] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [reload, setReload] = useState(0)

  useEffect(() => {
    let live = true
    api
      .leaderboard()
      .then((board) => {
        if (!live) return
        setEntries(board.entries)
        setTotalPlayers(board.totalPlayers ?? board.entries.length)
      })
      .catch((e: unknown) => {
        if (live) {
          setEntries([])
          setError(
            e instanceof ApiRequestError ? e.message : 'Could not load the scores',
          )
        }
      })
    return () => {
      live = false
    }
  }, [reload])

  // Non-zero only if a caller asked for a slice; this page asks for everyone.
  const shown = entries?.length ?? 0
  const hidden = Math.max(0, totalPlayers - shown)

  const retry = useCallback(() => {
    setError(null)
    setEntries(null)
    setReload((n) => n + 1)
  }, [])

  return (
    <main className="lobby">
      <header className="lobby__header">
        <h1>Scores</h1>
        <p className="lobby__tagline">
          Every game finished on this server, by name.
          {hidden > 0 && ` Showing the top ${shown} of ${totalPlayers}.`}
        </p>
      </header>

      <section className="card card--wide">
        {entries === null ? (
          <p className="card__hint">Loading scores…</p>
        ) : error ? (
          <div className="alert alert--error" role="alert">
            <span>{error}</span>
            <button type="button" className="alert__close" onClick={retry} aria-label="Dismiss">
              ×
            </button>
          </div>
        ) : entries.length === 0 ? (
          <p className="card__hint">
            No games have finished yet. The first win recorded here will be yours.
          </p>
        ) : (
          <div
            className="scores__scroll"
            role="region"
            aria-label="Players and their records"
            tabIndex={0}
          >
            <table className="scores">
              <caption className="sr-only">
                Every player who has finished a game, ranked by wins, with their win rate
              </caption>
              <thead>
                <tr>
                  <th scope="col">#</th>
                  <th scope="col">Player</th>
                  <th scope="col">Won</th>
                  <th scope="col">Lost</th>
                  <th scope="col">Played</th>
                  <th scope="col">Win rate</th>
                  <th scope="col">Streak</th>
                  <th scope="col">Best run</th>
                </tr>
              </thead>
              <tbody>
                {entries.map((entry, index) => {
                  const you = playerName !== null && sameName(entry.name, playerName)
                  return (
                    <tr key={entry.name} className={you ? 'scores__row--you' : undefined}>
                      <td>{index + 1}</td>
                      <th scope="row">
                        {entry.name}
                        {you && <span className="scores__you"> you</span>}
                      </th>
                      <td>{entry.wins}</td>
                      <td>{entry.losses}</td>
                      <td>{entry.gamesPlayed}</td>
                      <td className="scores__rate" style={rateMeter(entry)}>
                        {entry.winRatePercent}%
                      </td>
                      <td>{entry.streak}</td>
                      <td>{entry.bestStreak}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <p className="card__link">
        <a href="/lobby" onClick={toLobby}>
          Back to the lobby
        </a>
      </p>
    </main>
  )
}

/**
 * Fills the win-rate cell with a meter, as a background under the number.
 *
 * <p>The percentage is still the cell's text, so this is decoration and not the value: a
 * bar that carried the meaning would be invisible to a screen reader and unreadable in a
 * text dump. The percentage comes from the server, which is what files the results.
 */
function rateMeter(entry: LeaderboardEntry): CSSProperties {
  const percent = Math.max(0, Math.min(100, entry.winRatePercent))
  return {
    background: `linear-gradient(to right, rgba(58, 123, 213, 0.22) ${percent}%, transparent ${percent}%)`,
  }
}

/** The same rule the server keys rows by: case-insensitive, whitespace collapsed. */
function sameName(a: string, b: string): boolean {
  return a.trim().replace(/\s+/g, ' ').toLowerCase() === b.trim().replace(/\s+/g, ' ').toLowerCase()
}
