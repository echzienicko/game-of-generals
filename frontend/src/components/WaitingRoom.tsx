import { useState } from 'react'
import { useGame } from '../state/GameContext'
import { ChatPanel } from './ChatPanel'
import { yourLabel } from '../seats'
import { ThemeToggle } from './ThemeToggle'

export function WaitingRoom() {
  const { session, game, leaveGame, connected } = useGame()
  const [copied, setCopied] = useState(false)
  if (!session) return null

  async function copy() {
    if (!session) return
    try {
      await navigator.clipboard.writeText(session.gameId)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 2000)
    } catch {
      // clipboard blocked: the code is on screen, so just leave it there
    }
  }

  const youDeployed = game?.status === 'IN_PROGRESS' || game?.status === 'FINISHED'

  return (
    <main className="waiting">
      <ThemeToggle />
      <h1>Waiting for an opponent</h1>
      <p className="waiting__hint">
        Share this code. The game starts as soon as both players are in.
      </p>
      <div className="waiting__code">
        <code>{session.gameId}</code>
        <button type="button" className="btn" onClick={() => void copy()}>
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      <p className="waiting__hint">
        You are{' '}
        <strong className={`side side--${session.youAre.toLowerCase()}`}>
          <span className="side__name">{game ? yourLabel(game) : session.youAre}</span>
          <span className="side__color">{session.youAre}</span>
        </strong>
        {session.youAre === 'RED' ? ' and move first.' : '.'}
      </p>
      <p className={`conn conn--${connected ? 'on' : 'off'}`}>
        {connected ? 'connected' : 'connecting…'}
      </p>
      {youDeployed && <p className="waiting__hint">Your opponent is still deploying.</p>}
      <ChatPanel />
      <button type="button" className="btn btn--ghost" onClick={leaveGame}>
        Cancel
      </button>
    </main>
  )
}
