import { useState } from 'react'
import { useGame } from '../state/GameContext'
import { useLinkHandler } from '../routing'

export function Lobby() {
  const { createGame, joinGame, matchmake, busy, error, clearError, playerName } = useGame()
  const [code, setCode] = useState('')
  const toLeaderboard = useLinkHandler('/leaderboard')

  return (
    <main className="lobby">
      <header className="lobby__header">
        <h1>Game of the Generals</h1>
        <p className="lobby__tagline">
          Twenty-one pieces a side. Read your opponent's movements, guess their ranks, and take
          their flag.
        </p>
        <p className="lobby__identity">
          Playing as <strong>{playerName}</strong>{' '}
          <a href="/leaderboard" onClick={toLeaderboard}>
            Scores
          </a>
        </p>
      </header>

      {error && (
        <div className="alert alert--error" role="alert">
          <span>{error}</span>
          <button type="button" className="alert__close" onClick={clearError} aria-label="Dismiss">
            ×
          </button>
        </div>
      )}

      <div className="lobby__cards">
        <section className="card">
          <h2>Play a friend</h2>
          <p className="card__hint">Create a game and share the code with your opponent.</p>
          <button type="button" className="btn btn--primary" disabled={busy} onClick={createGame}>
            Create game
          </button>
        </section>

        <section className="card">
          <h2>Join a game</h2>
          <form
            className="card__form"
            onSubmit={(event) => {
              event.preventDefault()
              void joinGame(code)
            }}
          >
            <label className="field">
              <span>Game code</span>
              <input
                value={code}
                onChange={(event) => {
                  clearError()
                  setCode(event.target.value)
                }}
                placeholder="e.g. 4f9a1c22"
                autoComplete="off"
                spellCheck={false}
              />
            </label>
            <button type="submit" className="btn btn--primary" disabled={busy || !code.trim()}>
              Join
            </button>
          </form>
        </section>

        <section className="card">
          <h2>Quick match</h2>
          <p className="card__hint">
            Get paired with whoever is waiting. You will play as red and move first.
          </p>
          <button type="button" className="btn" disabled={busy} onClick={matchmake}>
            Find opponent
          </button>
        </section>
      </div>

      <section className="card card--wide">
        <h2>How it works</h2>
        <ul className="rules-list">
          <li>Deploy all 21 pieces inside your own three rows. Nothing crosses the centre line.</li>
          <li>Each turn a piece moves one square up, down, left or right.</li>
          <li>
            A piece with no friendly neighbour may instead advance two squares in a straight line
            onto an empty square.
          </li>
          <li>Red moves first. Take the enemy flag to win.</li>
        </ul>
      </section>
    </main>
  )
}
