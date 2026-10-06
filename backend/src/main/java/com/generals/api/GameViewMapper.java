package com.generals.api;

import com.generals.api.dto.BattleDto;
import com.generals.api.dto.ChatMessageDto;
import com.generals.api.dto.GameStateDto;
import com.generals.api.dto.SeatDto;
import com.generals.api.dto.SquareDto;
import com.generals.domain.BattleResult;
import com.generals.domain.Board;
import com.generals.domain.Game;
import com.generals.domain.MoveRecord;
import com.generals.domain.Piece;
import com.generals.domain.PlayerColor;
import com.generals.domain.Position;
import com.generals.service.GameSession;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link Game} into the view a single player is allowed to see.
 *
 * <p>The single most important job here is hiding enemy pieces. Under the official rules a
 * rank stays face-down for the whole game — a piece is not exposed by fighting, not by
 * surviving, not by dying. So an enemy square is sent with a {@code null} rank, and the
 * battle payload is redacted to match: the viewer sees which side held the square, and
 * {@code null} for any rank that is not their own. The client has no way to recover an
 * enemy rank from the payload, so the fog of war cannot be broken by inspecting it.
 */
@Component
public class GameViewMapper {

    private static final int LOG_LIMIT = 40;

    public GameStateDto toDto(Game game, GameSession session, PlayerColor viewer) {
        List<SquareDto> squares = boardFor(game, viewer);
        List<String> log = logFor(game, viewer);
        MoveRecord last = game.history().isEmpty() ? null : game.history().get(game.history().size() - 1);

        return new GameStateDto(
                game.id(),
                game.status().name(),
                viewer.name(),
                session.isAgainstBot(viewer),
                game.hasPlaced(viewer),
                game.status() == Game.Status.FINISHED ? null : game.currentPlayer().name(),
                game.winner() == null ? null : game.winner().name(),
                game.winReason(),
                game.isFlagEscapePending(),
                clockFor(game, session),
                session.turnSeconds(),
                game.history().size(),
                Board.ROWS,
                Board.COLS,
                game.board().countPieces(viewer),
                game.board().countPieces(viewer.opponent()),
                squares,
                last == null ? null : battleDto(last, viewer),
                log,
                session.seatsFor(viewer),
                session.chat());
    }

    /**
     * The moment the turn on the clock falls due, or null when nothing is being timed.
     *
     * <p>Null in every phase that is not a live turn, including a finished game and one
     * where the computer has the move: {@link com.generals.service.TurnClock} does not run
     * on the bot, so a deadline there would be a countdown to nothing.
     */
    private Long clockFor(Game game, GameSession session) {
        if (game.status() != Game.Status.IN_PROGRESS || game.isOver() || !session.isClockRunning()) {
            return null;
        }
        return session.turnDeadline();
    }

    private List<SquareDto> boardFor(Game game, PlayerColor viewer) {
        List<SquareDto> squares = new ArrayList<>(Board.ROWS * Board.COLS);
        for (int row = 0; row < Board.ROWS; row++) {
            for (int col = 0; col < Board.COLS; col++) {
                Position position = new Position(row, col);
                Piece piece = game.board().pieceAt(position).orElse(null);
                if (piece == null) {
                    squares.add(SquareDto.empty(row, col));
                    continue;
                }
                boolean known = isVisibleTo(piece, viewer);
                squares.add(new SquareDto(
                        row,
                        col,
                        position.label(),
                        piece.id(),
                        piece.owner().name(),
                        known ? piece.rank().name() : null,
                        known ? piece.rank().displayName() : null,
                        known));
            }
        }
        return squares;
    }

    /** You always know your own army; an enemy piece only once its identity is public. */
    private static boolean isVisibleTo(Piece piece, PlayerColor viewer) {
        return piece.owner() == viewer || piece.isRevealed();
    }

    private static String rankOf(Piece piece, PlayerColor viewer) {
        return isVisibleTo(piece, viewer) ? piece.rank().name() : null;
    }

    private static String rankNameOf(Piece piece, PlayerColor viewer) {
        return isVisibleTo(piece, viewer) ? piece.rank().displayName() : null;
    }

    /**
     * What the viewer is told about a finished contest, with no ranks in it.
     *
     * <p>{@link BattleResult#description()} is the arbiter's full account and names both
     * ranks, so it must never leave the server. A viewer is told which side held the square
     * — which the overlay already shows — and nothing about what the pieces were.
     */
    private static String battleDescription(BattleResult battle, PlayerColor viewer) {
        String attacker = side(battle.attacker().owner(), viewer);
        String defender = side(battle.defender().owner(), viewer);
        if (battle.attackerSurvives() && !battle.defenderSurvives()) {
            return attacker + " holds the square and " + defender + " is destroyed";
        }
        if (!battle.attackerSurvives() && battle.defenderSurvives()) {
            return defender + " holds the square and " + attacker + " is destroyed";
        }
        return attacker + " and " + defender + " are both destroyed";
    }

    private static String side(PlayerColor owner, PlayerColor viewer) {
        return owner == viewer ? "your piece" : "an unidentified enemy piece";
    }

    private static BattleDto battleDto(MoveRecord record, PlayerColor viewer) {
        BattleResult battle = record.battle();
        if (battle == null) {
            return null;
        }
        // the defender never moves, so its square is the contested one
        Position target = battle.defender().position();
        return new BattleDto(
                battle.attacker().id(),
                battle.attacker().owner().name(),
                rankOf(battle.attacker(), viewer),
                rankNameOf(battle.attacker(), viewer),
                record.from().row(),
                record.from().col(),
                battle.attackerSurvives(),
                battle.defender().id(),
                battle.defender().owner().name(),
                rankOf(battle.defender(), viewer),
                rankNameOf(battle.defender(), viewer),
                target.row(),
                target.col(),
                battle.defenderSurvives(),
                battleDescription(battle, viewer));
    }

    private List<String> logFor(Game game, PlayerColor viewer) {
        List<MoveRecord> history = game.history();
        int from = Math.max(0, history.size() - LOG_LIMIT);
        List<String> lines = new ArrayList<>();
        for (MoveRecord record : history.subList(from, history.size())) {
            lines.add(describe(record, viewer));
        }
        return lines;
    }

    private String describe(MoveRecord record, PlayerColor viewer) {
        String side = record.player().name();
        boolean rankKnown = record.moverRevealed() || record.player() == viewer;
        String who = rankKnown
                ? record.rank().displayName()
                : "a hidden piece";
        String action = record.wasContested()
                ? battleDescription(record.battle(), viewer)
                : "moved to " + record.to().label();
        // Both players are told when a move was made by the clock rather than by the mover.
        // Nothing is revealed by saying so — they both watched the countdown reach zero —
        // and a piece that moves by itself with no explanation reads as a bug.
        if (record.byClock()) {
            action = action + " (" + side + " ran out of time)";
        }
        return "#" + record.moveNumber() + " " + side + " " + who + " " + action;
    }
}
