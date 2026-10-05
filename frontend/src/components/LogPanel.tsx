import { useEffect, useRef } from 'react'
import { useGame } from '../state/GameContext'
import { ChatPanel } from './ChatPanel'
import { opponentLabel, yourLabel } from '../seats'

export function LogPanel() {
  const { game } = useGame()
  const listRef = useRef<HTMLOListElement>(null)

  useEffect(() => {
    const list = listRef.current
    if (list) {
      list.scrollTop = list.scrollHeight
    }
  }, [game?.log.length])

  if (!game) return null

  return (
    <aside className="panel">
      <section className="panel__section">
        <h2 className="panel__title">Strength</h2>
        <div className="strength">
          <div className="strength__side">
            <span className="strength__label">{yourLabel(game)}</span>
            <span className="strength__count">{game.yourPiecesRemaining}</span>
          </div>
          <div className="strength__bar">
            <div
              className="strength__bar-fill"
              style={{
                width: `${(game.yourPiecesRemaining /
                  (game.yourPiecesRemaining + game.opponentPiecesRemaining || 1)) * 100}%`,
              }}
            />
          </div>
          <div className="strength__side">
            <span className="strength__count">{game.opponentPiecesRemaining}</span>
            <span className="strength__label">{opponentLabel(game)}</span>
          </div>
        </div>
      </section>

      <section className="panel__section panel__section--grow">
        <h2 className="panel__title">Move log</h2>
        <ol className="log" ref={listRef}>
          {game.log.length === 0 && <li className="log__empty">No moves yet.</li>}
          {game.log.map((entry, index) => (
            <li
              key={`${game.turnNumber}-${index}`}
              className={`log__entry ${entry.startsWith('You') ? 'log__entry--mine' : ''}`}
            >
              {entry}
            </li>
          ))}
        </ol>
      </section>

      <ChatPanel />
    </aside>
  )
}
