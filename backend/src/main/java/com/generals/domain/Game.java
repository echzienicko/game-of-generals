package com.generals.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A single match: the board, whose turn it is, and how the game ends.
 *
 * <p>Lifecycle: {@link Status#WAITING_FOR_OPPONENT} &rarr; {@link Status#PLACEMENT}
 * &rarr; {@link Status#IN_PROGRESS} &rarr; {@link Status#FINISHED}.
 *
 * <p>Two ways to win. Capturing the enemy flag ends the game immediately. Reaching the
 * enemy camp with a flag does not: the opponent is given one final move to capture it,
 * and only if the flag survives that move does its owner win.
 */
public final class Game {

    public enum Status {
        WAITING_FOR_OPPONENT,
        PLACEMENT,
        IN_PROGRESS,
        FINISHED
    }

    private final String id;
    private final Board board = new Board();
    private final BattleResolver resolver = new BattleResolver();
    private final MoveValidator moveValidator;
    private final List<MoveRecord> history = new ArrayList<>();
    private final Map<PlayerColor, Boolean> placementSubmitted = new EnumMap<>(PlayerColor.class);

    private Status status = Status.WAITING_FOR_OPPONENT;
    private PlayerColor currentPlayer = PlayerColor.RED;
    private PlayerColor winner;
    private String winReason;

    /** Set when a flag has reached the enemy camp and is awaiting the opponent's last move. */
    private boolean flagEscapePending;
    private PlayerColor flagEscapeOwner;

    public Game(String id) {
        this(id, true);
    }

    public Game(String id, boolean soloDoubleMoveEnabled) {
        this.id = id;
        this.moveValidator = new MoveValidator(soloDoubleMoveEnabled);
    }

    public String id() {
        return id;
    }

    public Board board() {
        return board;
    }

    public Status status() {
        return status;
    }

    public PlayerColor currentPlayer() {
        return currentPlayer;
    }

    public PlayerColor winner() {
        return winner;
    }

    public String winReason() {
        return winReason;
    }

    public boolean isFlagEscapePending() {
        return flagEscapePending;
    }

    public List<MoveRecord> history() {
        return List.copyOf(history);
    }

    public boolean isOver() {
        return status == Status.FINISHED;
    }

    /** Whether {@code color} has already deployed. False while the phase is still open. */
    public boolean hasPlaced(PlayerColor color) {
        return placementSubmitted.getOrDefault(color, false);
    }

    /** Whether both sides have deployed and the game is playable. */
    public boolean isDeployed() {
        return hasPlaced(PlayerColor.RED) && hasPlaced(PlayerColor.BLUE);
    }

    // ---------------------------------------------------------------- placement

    /** Called once both players have joined. */
    public void beginPlacement() {
        if (status != Status.WAITING_FOR_OPPONENT) {
            throw new IllegalStateException("placement has already started");
        }
        status = Status.PLACEMENT;
    }

    /**
     * Deploys the player's 21 pieces inside their own camp. All pieces are stored
     * face-down: the opponent never learns the deployment.
     */
    public void submitPlacement(PlayerColor color, Map<Position, Rank> deployment) {
        if (status == Status.WAITING_FOR_OPPONENT) {
            status = Status.PLACEMENT;
        }
        if (status != Status.PLACEMENT) {
            throw new IllegalStateException("pieces can only be placed during the placement phase");
        }
        if (placementSubmitted.getOrDefault(color, false)) {
            throw new IllegalMoveException(color + " has already deployed");
        }
        validateDeployment(color, deployment);

        for (Map.Entry<Position, Rank> entry : deployment.entrySet()) {
            board.place(Piece.createHidden(entry.getValue(), color, entry.getKey()));
        }
        placementSubmitted.put(color, true);

        if (placementSubmitted.getOrDefault(PlayerColor.RED, false)
                && placementSubmitted.getOrDefault(PlayerColor.BLUE, false)) {
            status = Status.IN_PROGRESS;
            currentPlayer = PlayerColor.RED;
        }
    }

    private void validateDeployment(PlayerColor color, Map<Position, Rank> deployment) {
        if (deployment == null || deployment.size() != ArmyFactory.ARMY_SIZE) {
            throw new IllegalMoveException(
                    "each side must deploy exactly " + ArmyFactory.ARMY_SIZE + " pieces");
        }
        for (Position position : deployment.keySet()) {
            if (!position.isInOwnCamp(color)) {
                throw new IllegalMoveException(
                        "pieces may only be placed in your own camp (" + position.label() + ")");
            }
        }
        Map<Rank, Integer> expected = countRanks(ArmyFactory.standardRoster());
        Map<Rank, Integer> actual = countRanks(deployment.values());
        if (!expected.equals(actual)) {
            throw new IllegalMoveException(
                    "deployment does not match the standard army: expected " + expected
                            + " but got " + actual);
        }
    }

    private Map<Rank, Integer> countRanks(Iterable<Rank> ranks) {
        Map<Rank, Integer> counts = new EnumMap<>(Rank.class);
        for (Rank rank : ranks) {
            counts.merge(rank, 1, Integer::sum);
        }
        return counts;
    }

    // -------------------------------------------------------------------- moves

    /** Plays one turn for {@code color} and returns the recorded move. */
    public MoveRecord move(PlayerColor color, Position from, Position to) {
        return play(color, from, to, false);
    }

    /**
     * The same move, played by the server because the player's clock ran out.
     *
     * <p>Nothing about the move itself is different — the same validation, the same battle
     * resolution, the same win checks. Only the move log says that nobody chose it, because
     * that is the one fact the two players cannot work out for themselves and because a
     * piece that moved by itself with no explanation reads as a bug.
     */
    public MoveRecord moveByClock(PlayerColor color, Position from, Position to) {
        return play(color, from, to, true);
    }

    private MoveRecord play(PlayerColor color, Position from, Position to, boolean byClock) {
        if (status != Status.IN_PROGRESS) {
            throw new IllegalStateException("the game is not in progress (status: " + status + ")");
        }
        if (color != currentPlayer) {
            throw new IllegalMoveException("it is not " + color + "'s turn");
        }
        Piece mover = board.pieceAt(from).orElseThrow(
                () -> new IllegalMoveException("no piece at " + from.label()));
        if (!mover.isOwnedBy(color)) {
            throw new IllegalMoveException("that piece belongs to " + mover.owner());
        }
        moveValidator.validate(board, mover, to);

        Piece defender = board.pieceAt(to).orElse(null);
        BattleResult battle = null;
        if (defender == null) {
            board.removeAt(from);
            mover.moveTo(to);
            board.place(mover);
        } else {
            // Fighting reveals nothing. The official rules keep both ranks face-down even
            // after a contest: the arbiter only announces who held the square. See GameViewMapper.
            battle = resolver.resolve(mover, defender);
            applyBattle(mover, defender, battle, from, to);
        }

        MoveRecord record = new MoveRecord(history.size() + 1, color, mover.id(), mover.rank(),
                mover.isRevealed(), from, to, battle,
                battle == null ? "moved to " + to.label() : battle.description(), byClock);
        history.add(record);

        resolveOutcome(color, mover);
        return record;
    }

    private void applyBattle(Piece attacker, Piece defender, BattleResult result, Position from, Position to) {
        board.removeAt(from);
        if (result.defenderSurvives()) {
            // the defender keeps the contested square
        } else {
            board.removeAt(to);
        }
        if (result.attackerSurvives()) {
            attacker.moveTo(to);
            board.place(attacker);
        }
    }

    /**
     * Decides whether the game is over, then hands the turn to the opponent.
     *
     * <p>Order matters: a captured flag ends everything at once. Otherwise a flag that
     * reached the enemy camp last turn survives only if the opponent just failed to
     * take it.
     */
    private void resolveOutcome(PlayerColor moverColor, Piece mover) {
        if (board.findFlag(moverColor.opponent()).isEmpty()) {
            declareWinner(moverColor, moverColor + " captured the enemy flag");
            return;
        }
        if (flagEscapePending) {
            if (board.findFlag(flagEscapeOwner).isPresent()) {
                declareWinner(flagEscapeOwner,
                        flagEscapeOwner + "'s flag reached the enemy camp and survived the final attack");
            } else {
                flagEscapePending = false;
            }
            return;
        }
        if (mover.isFlag() && board.findFlag(moverColor).isPresent()
                && mover.position().isInEnemyCamp(moverColor)) {
            flagEscapePending = true;
            flagEscapeOwner = moverColor;
        }
        currentPlayer = moverColor.opponent();
    }

    /**
     * {@code quitter} concedes the match, and the opponent wins on the spot.
     *
     * <p>The whole game, deployment included: a game nobody can leave is a game that waits
     * forever the moment one of the two walks away, and the phase changes nothing about who
     * concedes to whom. Two refusals, both of which would otherwise invent an opponent or
     * a second result — before anyone has joined there is nobody to beat, and once the game
     * is over its result is already decided (and filed).
     *
     * <p>Quitting the browser is deliberately not this: a dropped connection is not a
     * decision, and a player whose phone slept has not conceded anything. Only an explicit
     * resign ends a game this way.
     */
    public void resign(PlayerColor quitter) {
        if (status == Status.WAITING_FOR_OPPONENT) {
            throw new IllegalStateException("there is no opponent to concede to yet");
        }
        if (status == Status.FINISHED) {
            throw new IllegalStateException("the game is already over");
        }
        declareWinner(quitter.opponent(), quitter + " left the game");
    }

    private void declareWinner(PlayerColor winner, String reason) {
        this.winner = winner;
        this.winReason = reason;
        this.status = Status.FINISHED;
        this.flagEscapePending = false;
    }

    // --------------------------------------------------------------- test hooks
    // Package-private: lets tests in this package build mid-board positions that the
    // placement rules would otherwise forbid. Not part of the public API.

    void seed(Piece piece) {
        board.place(piece);
    }

    void startPlaying() {
        status = Status.IN_PROGRESS;
        currentPlayer = PlayerColor.RED;
    }
}
