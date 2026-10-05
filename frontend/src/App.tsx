import { GameProvider, useGame } from './state/GameContext'
import { Lobby } from './components/Lobby'
import { WaitingRoom } from './components/WaitingRoom'
import { Placement } from './components/Placement'
import { GameScreen } from './components/GameScreen'
import { NameGate } from './components/NameGate'
import { LeaderboardPage } from './components/Leaderboard'
import { usePathname } from './routing'

/**
 * Which screen to show.
 *
 * <p>There is no router. The server forwards every path to this one document, so the
 * pathname decides between the pages that are not part of a game, and a game is decided by
 * the session and the game's own status. A game in progress outranks the pathname, so a
 * refresh on /lobby mid-game still lands on the board rather than in the lobby.
 *
 * <p>The scores outrank the name gate: a visitor can look at the table before saying who
 * they are, which is also the only way to reach it without creating a game.
 */
function Routes() {
  const { session, game, playerName } = useGame()
  const pathname = usePathname()

  if (pathname === '/leaderboard') {
    return <LeaderboardPage />
  }
  if (!playerName) {
    return <NameGate />
  }
  if (!session) {
    return <Lobby />
  }
  if (!game) {
    return <Loading />
  }
  switch (game.status) {
    case 'WAITING_FOR_OPPONENT':
      return <WaitingRoom />
    case 'PLACEMENT':
      return <Placement />
    case 'IN_PROGRESS':
    case 'FINISHED':
      return <GameScreen />
    default:
      return <Lobby />
  }
}

function Loading() {
  return (
    <main className="loading">
      <div className="loading__spinner" aria-hidden="true" />
      <p>Loading game…</p>
    </main>
  )
}

export default function App() {
  return (
    <GameProvider>
      <Routes />
    </GameProvider>
  )
}
