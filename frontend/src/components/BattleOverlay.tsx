import { useEffect, useState } from 'react'
import { useGame } from '../state/GameContext'

const BATTLE_MS = 2600

/** A battle never exposes the opponent's rank, so the server sends null for their side. */
function rankLabel(rankName: string | null): string {
  return rankName ?? 'Unknown'
}

/** Shows the outcome of the last clash for a moment, then gets out of the way. */
export function BattleOverlay() {
  const { game } = useGame()
  const [dismissed, setDismissed] = useState<string | null>(null)

  const battle = game?.lastBattle ?? null
  const battleKey =
    game && battle
      ? `${game.turnNumber}:${battle.toRow},${battle.toCol}:${battle.defenderId}`
      : null
  // A battle stays on screen once, even if the component re-renders meanwhile.
  const visible = battle !== null && battleKey !== null && battleKey !== dismissed

  useEffect(() => {
    if (!visible || !battleKey) {
      return
    }
    const timer = window.setTimeout(() => setDismissed(battleKey), BATTLE_MS)
    return () => window.clearTimeout(timer)
  }, [visible, battleKey])

  if (!battle || !visible) {
    return null
  }

  const attackerWon = battle.attackerSurvives && !battle.defenderSurvives
  const bothDied = !battle.attackerSurvives && !battle.defenderSurvives

  return (
    <div className="battle-overlay" role="status" aria-live="polite">
      <div className="battle-card">
        <h2 className="battle-card__title">
          {bothDied ? 'Both pieces are lost' : attackerWon ? 'Attacker holds the square' : 'Defender holds the square'}
        </h2>
        <div className="battle-card__duel">
          <div className={`battle-card__side battle-card__side--${battle.attackerOwner.toLowerCase()}`}>
            <span className="battle-card__rank">{rankLabel(battle.attackerRankName)}</span>
            <span className="battle-card__origin">from {battle.attackerOwner} {battle.fromRow + 1}-{battle.fromCol + 1}</span>
            <span className="battle-card__fate">
              {battle.attackerSurvives ? 'survives' : 'destroyed'}
            </span>
          </div>
          <span className="battle-card__vs">vs</span>
          <div className={`battle-card__side battle-card__side--${battle.defenderOwner.toLowerCase()}`}>
            <span className="battle-card__rank">{rankLabel(battle.defenderRankName)}</span>
            <span className="battle-card__origin">on {battle.defenderOwner} {battle.toRow + 1}-{battle.toCol + 1}</span>
            <span className="battle-card__fate">
              {battle.defenderSurvives ? 'survives' : 'destroyed'}
            </span>
          </div>
        </div>
        <p className="battle-card__note">{battle.description}</p>
      </div>
    </div>
  )
}
