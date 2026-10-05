import { useState, type FormEvent } from 'react'
import { useGame } from '../state/GameContext'
import { MAX_NAME_LENGTH, tidyName } from '../state/playerName'
import { useLinkHandler } from '../routing'

/**
 * Asked once, on the very first visit.
 *
 * <p>Every seat carries a name, because the name is what a win is recorded against. There
 * is no account and no password: the name is kept in this browser and sent with every game
 * the player starts, so two people playing under the same name on one server share a row.
 */
export function NameGate() {
  const { saveName } = useGame()
  const [typed, setTyped] = useState('')
  const [error, setError] = useState<string | null>(null)
  const toLeaderboard = useLinkHandler('/leaderboard')

  function submit(event: FormEvent) {
    event.preventDefault()
    const result = tidyName(typed)
    if ('error' in result) {
      setError(result.error)
      return
    }
    setError(null)
    saveName(result.name)
  }

  return (
    <main className="lobby">
      <header className="lobby__header">
        <h1>Game of the Generals</h1>
        <p className="lobby__tagline">
          Twenty-one pieces a side. Read your opponent's movements, guess their ranks, and take
          their flag.
        </p>
      </header>

      <section className="card card--wide namegate">
        <h2>What should we call you?</h2>
        <p className="card__hint">
          Your name is how your wins are kept. It is remembered in this browser, and there is no
          password — anyone who plays under the same name on this server shares the score.
        </p>
        <form className="card__form" onSubmit={submit}>
          <label className="field">
            <span>Your name</span>
            <input
              value={typed}
              onChange={(event) => {
                setError(null)
                setTyped(event.target.value)
              }}
              maxLength={MAX_NAME_LENGTH}
              placeholder="e.g. Nick"
              autoComplete="nickname"
              autoFocus
              spellCheck={false}
              aria-describedby={error ? 'namegate-error' : undefined}
              aria-invalid={error ? true : undefined}
            />
          </label>
          {error && (
            <p className="field__error" id="namegate-error" role="alert">
              {error}
            </p>
          )}
          <button type="submit" className="btn btn--primary" disabled={!typed.trim()}>
            Start playing
          </button>
        </form>
        <a className="card__link" href="/leaderboard" onClick={toLeaderboard}>
          See the scores so far
        </a>
      </section>
    </main>
  )
}
