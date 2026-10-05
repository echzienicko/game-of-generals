import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import type { DeploymentEntry, GameState, Coordinate, Rank } from '../types'

/** Matches GameBroadcaster.DESTINATION on the server. */
const QUEUE = '/queue/game/'

/** Where GameSocketController's @MessageExceptionHandler reports rejected actions. */
const ERRORS = '/user/queue/errors'

export interface GameSocketHandlers {
  onState: (state: GameState) => void
  onError: (message: string) => void
  onStatus: (connected: boolean) => void
}

export interface GameSocket {
  subscribe: (gameId: string) => void
  sendMove: (gameId: string, from: Coordinate, to: Coordinate) => void
  sendPlacement: (gameId: string, pieces: DeploymentEntry[]) => void
  requestState: (gameId: string) => void
  disconnect: () => void
}

export function connectGameSocket(
  token: string,
  handlers: GameSocketHandlers,
): GameSocket {
  let subscription: StompSubscription | null = null
  let errorSubscription: StompSubscription | null = null
  let currentGameId: string | null = null

  const client = new Client({
    webSocketFactory: () => new SockJS('/ws'),
    connectHeaders: { token },
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: () => {
      handlers.onStatus(true)
      if (currentGameId) {
        subscribe(currentGameId)
      }
    },
    onWebSocketClose: () => handlers.onStatus(false),
    onStompError: (frame) => {
      handlers.onError(frame.headers['message'] ?? 'WebSocket error')
    },
  })

  function subscribe(id: string) {
    if (!subscription) {
      // /user/queue/game/{id} is the server's per-player destination: each side
      // receives its own redacted view, never the opponent's.
      subscription = client.subscribe(`/user${QUEUE}${id}`, (message: IMessage) => {
        if (message.body) {
          handlers.onState(JSON.parse(message.body) as GameState)
        }
      })
    }
    if (!errorSubscription) {
      // Without this, a rejected move over the socket would be dropped silently.
      errorSubscription = client.subscribe(ERRORS, (message: IMessage) => {
        handlers.onError(readError(message.body) ?? 'That move was not allowed')
      })
    }
  }

  function readError(body: string): string | null {
    try {
      const parsed = JSON.parse(body) as { message?: string }
      return parsed?.message ?? null
    } catch {
      return null
    }
  }

  client.onConnect = () => {
    handlers.onStatus(true)
    if (currentGameId) {
      subscribe(currentGameId)
    }
  }

  client.activate()

  return {
    subscribe(id: string) {
      currentGameId = id
      if (client.connected) {
        subscribe(id)
      }
    },
    sendMove(id: string, from: Coordinate, to: Coordinate) {
      client.publish({ destination: `/app/game/${id}/move`, body: JSON.stringify({ from, to }) })
    },
    sendPlacement(id: string, pieces: DeploymentEntry[]) {
      client.publish({
        destination: `/app/game/${id}/placement`,
        body: JSON.stringify({ pieces }),
      })
    },
    requestState(id: string) {
      client.publish({ destination: `/app/game/${id}/state`, body: '{}' })
    },
    disconnect() {
      subscription = null
      errorSubscription = null
      void client.deactivate()
    },
  }
}

export const RANKS: Rank[] = [
  'FIVE_STAR_GENERAL',
  'FOUR_STAR_GENERAL',
  'THREE_STAR_GENERAL',
  'TWO_STAR_GENERAL',
  'ONE_STAR_GENERAL',
  'COLONEL',
  'LIEUTENANT_COLONEL',
  'MAJOR',
  'CAPTAIN',
  'FIRST_LIEUTENANT',
  'SECOND_LIEUTENANT',
  'SERGEANT',
  'PRIVATE',
  'SPY',
  'FLAG',
]
