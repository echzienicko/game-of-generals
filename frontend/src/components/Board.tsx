import { useMemo } from 'react'
import { useGame } from '../state/GameContext'
import { PieceView } from './PieceView'
import { displayRows, isYourTurn, legalTargets, squareAt, squareKey } from '../rules'
import type { GameState, Square } from '../types'

function SquareButton({
  square,
  game,
  selectable,
  isTarget,
  isBattle,
  onClick,
}: {
  square: Square
  game: GameState
  selectable: boolean
  isTarget: boolean
  isBattle: boolean
  onClick: (square: Square) => void
}) {
  const mine = square.owner === game.youAre
  const occupied = square.pieceId !== null
  // The server hides enemy ranks until they have fought; rank is null until then.
  const hidden = occupied && !mine && square.rank === null

  const classes = [
    'square',
    mine ? 'square--mine' : 'square--enemy',
    occupied ? 'square--filled' : 'square--empty',
    selectable ? 'square--selectable' : '',
    isTarget ? 'square--target' : '',
    isBattle ? 'square--battle' : '',
  ]
    .filter(Boolean)
    .join(' ')

  const label = occupied
    ? `${square.label}, ${hidden ? 'unknown enemy piece' : square.rankName}`
    : `${square.label}, empty`

  // Every square stays clickable on purpose. Disabling the ones you cannot act
  // on would swallow the click, so the board could never explain itself ("it is not
  // your turn yet", "an unseen enemy piece holds E6"). selectable only styles.
  return (
    <button
      type="button"
      className={classes}
      onClick={() => onClick(square)}
      aria-label={label}
    >
      <span className="square__coord">{square.label}</span>
      {occupied && square.rank && (
        <PieceView rank={square.rank} color={mine ? 'red' : 'blue'} />
      )}
      {hidden && <PieceView rank="FLAG" hidden color="blue" />}
    </button>
  )
}

export function Board() {
  const { game, selected, selectSquare, movePiece, busy, setError } = useGame()

  const targets = useMemo(
    () => (game && selected ? legalTargets(game, selected) : []),
    [game, selected],
  )

  if (!game) {
    return null
  }

  const yourTurn = isYourTurn(game)
  const battle = game.lastBattle
  const battleSquares = battle
    ? new Set([squareKey(battle.fromRow, battle.fromCol), squareKey(battle.toRow, battle.toCol)])
    : new Set<string>()

  function handleClick(square: Square) {
    if (!game) return
    const isTarget = targets.some((t) => t.row === square.row && t.col === square.col)

    if (selected && isTarget) {
      void movePiece(selected, { row: square.row, col: square.col })
      return
    }
    if (square.owner === game.youAre && square.pieceId !== null) {
      if (!yourTurn) {
        setError('It is not your turn yet.')
        return
      }
      if (busy) return
      const alreadySelected =
        selected?.row === square.row && selected?.col === square.col
      selectSquare(alreadySelected ? null : { row: square.row, col: square.col })
      return
    }
    if (selected) {
      setError('A piece moves one square up, down, left or right.')
      return
    }
    if (square.pieceId !== null) {
      setError(
        square.rank
          ? `Enemy ${square.rankName} on ${square.label}.`
          : `An unseen enemy piece holds ${square.label}.`,
      )
    }
  }

  return (
    <div className="board" data-testid="board">
      <div className="board__grid" role="grid" aria-label="Game board">
        {displayRows(game).map((row) => (
          <div className="board__row" key={row} role="row">
            {Array.from({ length: game.cols }, (_, col) => {
              const square = squareAt(game, row, col)
              if (!square) return null
              return (
                <SquareButton
                  key={squareKey(row, col)}
                  square={square}
                  game={game}
                  selectable={
                    yourTurn &&
                    !busy &&
                    square.owner === game.youAre &&
                    square.pieceId !== null
                  }
                  isTarget={targets.some((t) => t.row === row && t.col === col)}
                  isBattle={battleSquares.has(squareKey(row, col))}
                  onClick={handleClick}
                />
              )
            })}
          </div>
        ))}
      </div>
    </div>
  )
}
