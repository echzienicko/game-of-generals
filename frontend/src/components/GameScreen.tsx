import { useGame } from '../state/GameContext'
import { Board } from './Board'
import { LogPanel } from './LogPanel'
import { opponentLabel, yourLabel } from '../seats'
import { BattleOverlay } from './BattleOverlay'
import { GameOver } from './GameOver'
import { isYourTurn } from '../rules'

export function GameScreen() {
  const { game, session, connected, error, clearError, leaveGame, selected } = useGame()
  if (!game) return null

  const yourTurn = isYourTurn(game)
  const enemyColor = game.youAre === 'RED' ? 'BLUE' : 'RED'
  const enemy = opponentLabel(game)

  let banner: string
  let tone = 'neutral'
  if (!connected) {
    banner = 'Reconnecting to the server…'
    tone = 'warn'
  } else if (game.flagEscapePending && yourTurn) {
    banner = `${enemy} is one move from their flag. Take it or you lose.`
    tone = 'alert'
  } else if (yourTurn) {
    banner = 'Your move'
    tone = 'go'
  } else {
    banner = `Waiting for ${enemy} to move…`
    tone = 'neutral'
  }

  return (
    <main className="game">
      <header className="game__header">
        <div className="game__identity">
          <span className={`side side--${game.youAre.toLowerCase()}`}>
            <span className="side__name">{yourLabel(game)}</span>
            <span className="side__color">{game.youAre}</span>
          </span>
          <span className="game__vs">vs</span>
          <span className={`side side--${enemyColor.toLowerCase()}`}>
            <span className="side__name">{enemy}</span>
            <span className="side__color">{enemyColor}</span>
          </span>
          <code className="game__code" title="Game code">
            {session?.gameId.slice(0, 8)}
          </code>
        </div>
        <div className="game__controls">
          <span className={`conn conn--${connected ? 'on' : 'off'}`}>
            {connected ? 'live' : 'offline'}
          </span>
          <button type="button" className="btn btn--ghost" onClick={leaveGame}>
            Leave
          </button>
        </div>
      </header>

      <div className={`banner banner--${tone}`} role="status" aria-live="polite">
        {banner}
        {selected && <span className="banner__hint">Pick a highlighted square.</span>}
      </div>

      {error && (
        <div className="alert alert--error" role="alert">
          <span>{error}</span>
          <button type="button" className="alert__close" onClick={clearError} aria-label="Dismiss">
            ×
          </button>
        </div>
      )}

      <div className="game__layout">
        <Board />
        <LogPanel />
      </div>

      <BattleOverlay />
      <GameOver />
    </main>
  )
}
