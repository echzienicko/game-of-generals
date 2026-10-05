import { useGame } from '../state/GameContext'
import { useLinkHandler } from '../routing'
import { opponentLabel } from '../seats'

export function GameOver() {
  const { game, leaveGame, playerName } = useGame()
  const toLeaderboard = useLinkHandler('/leaderboard')
  if (!game || game.status !== 'FINISHED') return null

  const won = game.winner === game.youAre
  const draw = game.winner === null

  return (
    <div className="gameover-overlay" role="dialog" aria-modal="true" aria-labelledby="gameover-title">
      <div className={`gameover ${won ? 'gameover--won' : 'gameover--lost'}`}>
        <h2 id="gameover-title" className="gameover__title">
          {draw ? 'Draw' : won ? 'Victory' : 'Defeat'}
        </h2>
        <p className="gameover__reason">{game.winReason ?? 'The game has ended.'}</p>
        <dl className="gameover__stats">
          <div>
            <dt>Turns played</dt>
            <dd>{game.turnNumber}</dd>
          </div>
          <div>
            <dt>Your pieces left</dt>
            <dd>{game.yourPiecesRemaining}</dd>
          </div>
          <div>
            <dt>Enemy pieces left</dt>
            <dd>{game.opponentPiecesRemaining}</dd>
          </div>
        </dl>
        <p className="gameover__recorded">
          {won
            ? `Recorded as a win for ${playerName ?? 'you'}.`
            : draw
              ? 'A draw is not recorded.'
              : `Your loss is on the record as ${playerName ?? 'you'}.`}
        </p>
        {!draw && <p className="gameover__against">Against {opponentLabel(game)}.</p>}
        <div className="gameover__actions">
          <button type="button" className="btn btn--primary" onClick={leaveGame}>
            Back to lobby
          </button>
          <a className="btn" href="/leaderboard" onClick={toLeaderboard}>
            See scores
          </a>
        </div>
      </div>
    </div>
  )
}
