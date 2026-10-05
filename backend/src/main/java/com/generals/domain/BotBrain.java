package com.generals.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.springframework.stereotype.Component;

/**
 * The computer opponent's mind: it enumerates every legal move for its own side and then
 * picks one.
 *
 * <p>The bot plays from the same information the human player gets, and no more. It sees
 * its own army in full, it sees revealed enemy pieces, and for everything else it reasons
 * about the <em>distribution</em> of the enemy ranks it has not yet uncovered. Attacking a
 * square holding an unrevealed piece is therefore scored against every rank still
 * outstanding in the standard roster, weighted by how many of each are left, rather than
 * against a rank it would have had to be told. See {@link #battleOdds}.
 *
 * <p>Scoring, in rough order of weight:
 * <ul>
 *   <li>a square holding a revealed enemy <b>flag</b> ends the game and wins outright;</li>
 *   <li>a battle is scored on its expected outcome — winning is good, a mutual kill is
 *       tolerated, losing the attacker is the worst thing on the board;</li>
 *   <li>otherwise, advancing toward the enemy camp is good, and the double-step a lone
 *       piece gets is free progress, so it is a bonus;</li>
 *   <li>stepping next to a known enemy piece that beats the mover is punished, because
 *       that piece will simply be taken next turn;</li>
 *   <li>moving the flag is heavily punished, and at {@link BotDifficulty#LEARNING} any
 *       move that continues an opening which has already lost is punished on top.</li>
 * </ul>
 */
@Component
public final class BotBrain {

    /** A move the bot may make. */
    public record Move(Position from, Position to) {

        /** The stable text form used to remember the line of play, e.g. {@code "C5-C6"}. */
        public String signature() {
            return from.label() + "-" + to.label();
        }

        @Override
        public String toString() {
            return from.label() + "->" + to.label();
        }
    }

    /** How a battle ends, as fractions that sum to one. */
    private record BattleOdds(double attackerWins, double bothDie, double attackerDies) {

        static BattleOdds of(BattleResult result) {
            if (result.attackerSurvives() && !result.defenderSurvives()) {
                return new BattleOdds(1, 0, 0);
            }
            if (!result.attackerSurvives() && result.defenderSurvives()) {
                return new BattleOdds(0, 0, 1);
            }
            return new BattleOdds(0, 1, 0);
        }
    }

    /** Capturing a revealed flag ends the game; nothing else can outrank this. */
    static final int WIN_SCORE = 1_000_000;

    /** Losing the attacker must hurt more than winning does, or the bot will trade freely. */
    private static final int BATTLE_WIN = 120;
    private static final int BATTLE_TRADE = 30;
    private static final int BATTLE_LOSS = 260;

    private static final int STEP_PROGRESS = 14;
    private static final int DOUBLE_MOVE_BONUS = 12;
    private static final int FRONT_LINE_BONUS = 20;
    private static final int PENETRATION_BONUS = 40;
    private static final int ADJACENT_TO_LOSING_PENALTY = 70;
    private static final int FLAG_MOVE_PENALTY = 200;
    private static final int REPEATED_LOSS_PENALTY = 400;
    private static final int UNDO_PENALTY = 60;

    /** How often the bot simply takes the best-scoring move; the rest of the time it explores. */
    private static final double GREEDINESS = 0.85;
    private final double greediness;

    private final MoveValidator validator;
    private final BattleResolver resolver;
    private final Random random;

    public BotBrain() {
        this(new MoveValidator(), new BattleResolver(), new Random());
    }

    public BotBrain(MoveValidator validator, BattleResolver resolver, Random random) {
        this(validator, resolver, random, GREEDINESS);
    }

    /**
     * @param greediness how often the best-scoring move wins outright; the remainder of the
     *                   time the bot picks at random. {@code 1.0} makes it deterministic,
     *                   which is what tests want.
     */
    BotBrain(MoveValidator validator, BattleResolver resolver, Random random, double greediness) {
        this.validator = validator;
        this.resolver = resolver;
        this.random = random;
        this.greediness = greediness;
    }

    /**
     * Every move {@code me} may legally make right now, in board order.
     *
     * <p>Enumeration is done for one side only, so this is simply the bot's own view.
     */
    public List<Move> legalMoves(Game game, PlayerColor me) {
        List<Move> moves = new ArrayList<>();
        for (Piece piece : game.board().piecesOf(me)) {
            for (Position target : game.board().possibleTargets(piece, validator.soloDoubleMoveEnabled())) {
                if (validator.isValid(game.board(), piece, target)) {
                    moves.add(new Move(piece.position(), target));
                }
            }
        }
        Collections.sort(moves, (a, b) -> {
            int byRow = a.from().compareTo(b.from());
            return byRow != 0 ? byRow : a.to().compareTo(b.to());
        });
        return List.copyOf(moves);
    }

    /**
     * Picks a move for {@code me}.
     *
     * @param line   the bot's own moves so far this game, oldest first
     * @param memory what the bot remembers about losing openings; may be {@link OpeningMemory#NONE}
     * @throws IllegalStateException when {@code me} has no legal move, which the rules say cannot happen
     */
    public Move choose(Game game, PlayerColor me, BotDifficulty difficulty, List<String> line,
            OpeningMemory memory) {
        List<Move> moves = legalMoves(game, me);
        if (moves.isEmpty()) {
            throw new IllegalStateException("no legal move available for " + me);
        }
        if (difficulty == BotDifficulty.RANDOM) {
            return moves.get(random.nextInt(moves.size()));
        }

        List<String> played = line == null ? List.of() : line;
        OpeningMemory remembered = memory == null ? OpeningMemory.none() : memory;

        List<Move> best = new ArrayList<>();
        int topScore = Integer.MIN_VALUE;
        for (Move move : moves) {
            int score = score(game, me, move, played, remembered);
            if (score > topScore) {
                topScore = score;
                best.clear();
                best.add(move);
            } else if (score == topScore) {
                best.add(move);
            }
        }

        if (random.nextDouble() > greediness) {
            return moves.get(random.nextInt(moves.size()));
        }
        return best.get(random.nextInt(best.size()));
    }

    /** The score {@link #choose} acts on. Exposed so tests can assert why a move was taken. */
    public int score(Game game, PlayerColor me, Move move, List<String> line, OpeningMemory memory) {
        OpeningMemory remembered = memory == null ? OpeningMemory.none() : memory;
        Piece mover = game.board().pieceAt(move.from()).orElseThrow();
        Piece target = game.board().pieceAt(move.to()).orElse(null);
        int score = 0;

        if (target != null) {
            if (target.isFlag() && target.isRevealed()) {
                return WIN_SCORE;
            }
            BattleOdds odds = battleOdds(game, me, mover, target);
            score += (int) Math.round(BATTLE_WIN * odds.attackerWins());
            score += (int) Math.round(BATTLE_TRADE * odds.bothDie());
            score -= (int) Math.round(BATTLE_LOSS * odds.attackerDies());
        } else {
            score += STEP_PROGRESS * progress(me, move.from(), move.to());
            if (move.to().isTwoStepsInStraightLineTo(move.from())) {
                score += DOUBLE_MOVE_BONUS;
            }
            if (isFrontLine(me, move.to())) {
                score += FRONT_LINE_BONUS;
            }
            if (move.to().isInEnemyCamp(me)) {
                score += PENETRATION_BONUS;
            }
            score -= adjacencyToBeater(game, me, mover, move.to()) * ADJACENT_TO_LOSING_PENALTY;
        }

        if (mover.isFlag()) {
            score -= FLAG_MOVE_PENALTY;
        }

        List<String> continued = new ArrayList<>(line == null ? List.of() : line);
        continued.add(move.signature());
        score -= REPEATED_LOSS_PENALTY * remembered.lossesAfter(continued);
        score -= undoPenalty(continued);

        return score;
    }

    /**
     * The chance a battle against {@code target} ends each way, from what the bot can
     * legitimately see.
     *
     * <p>A revealed target is resolved outright. An unrevealed one is scored against the
     * ranks still missing from the standard roster, which is exactly the set the target
     * could be.
     */
    private BattleOdds battleOdds(Game game, PlayerColor me, Piece attacker, Piece target) {
        if (target.isRevealed()) {
            return BattleOdds.of(resolver.resolve(attacker, target));
        }
        Map<Rank, Integer> outstanding = outstandingEnemyRanks(game, me);
        int total = outstanding.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            return BattleOdds.of(resolver.resolve(attacker, target));
        }
        double win = 0;
        double trade = 0;
        double lose = 0;
        for (Map.Entry<Rank, Integer> entry : outstanding.entrySet()) {
            Piece shadow = Piece.create(entry.getKey(), target.owner(), target.position());
            BattleOdds odds = BattleOdds.of(resolver.resolve(attacker, shadow));
            double share = (double) entry.getValue() / total;
            win += share * odds.attackerWins();
            trade += share * odds.bothDie();
            lose += share * odds.attackerDies();
        }
        return new BattleOdds(win, trade, lose);
    }

    /**
     * The enemy ranks the bot has not accounted for yet: the standard roster minus every
     * enemy piece it has seen identified.
     *
     * <p>This is how the bot stays honest. It never asks what the enemy is and is only told
     * what it could work out itself, so an unrevealed defender keeps all of its remaining
     * possibilities alive and the bot will not walk a General into a square a Private is
     * standing on.
     */
    private Map<Rank, Integer> outstandingEnemyRanks(Game game, PlayerColor me) {
        Map<Rank, Integer> outstanding = new EnumMap<>(Rank.class);
        for (Rank rank : ArmyFactory.standardRoster()) {
            outstanding.merge(rank, 1, Integer::sum);
        }
        PlayerColor enemy = me == PlayerColor.RED ? PlayerColor.BLUE : PlayerColor.RED;
        for (Piece piece : game.board().piecesOf(enemy)) {
            if (piece.isRevealed()) {
                outstanding.merge(piece.rank(), -1, Integer::sum);
            }
        }
        outstanding.values().removeIf(count -> count <= 0);
        return outstanding;
    }

    /**
     * How much worse it is to walk straight back to the square the bot just came from.
     *
     * <p>Without this, two bots playing identical heuristic scores can shove each other
     * back and forth across the same two squares forever: a game decided by capturing the
     * flag has to end, and a loop never will. It is also just a poor look for a piece to
     * appear to decide a move and then take it back.
     */
    private int undoPenalty(List<String> lineWithCandidate) {
        if (lineWithCandidate.size() < 2) {
            return 0;
        }
        String previous = lineWithCandidate.get(lineWithCandidate.size() - 2);
        String candidate = lineWithCandidate.get(lineWithCandidate.size() - 1);
        int dash = previous.indexOf('-');
        String cameFrom = previous.substring(0, dash);
        return candidate.endsWith("-" + cameFrom) ? UNDO_PENALTY : 0;
    }

    /** How many rows closer to the enemy camp a move goes; negative when it retreats. */
    private int progress(PlayerColor me, Position from, Position to) {
        int forward = me == PlayerColor.RED ? 1 : -1;
        return forward * (to.row() - from.row());
    }

    /** Whether a square is the one just outside the enemy camp, from which it can be entered. */
    private boolean isFrontLine(PlayerColor me, Position to) {
        return to.isInEnemyCamp(me) || adjacentToEnemyCamp(me, to);
    }

    private boolean adjacentToEnemyCamp(PlayerColor me, Position to) {
        for (Position neighbour : to.orthogonalNeighbours()) {
            if (neighbour.isInEnemyCamp(me)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the piece that would arrive at {@code to} stands next to an enemy piece the
     * bot has identified and would beat it, in which case the move walks into a capture.
     */
    private int adjacencyToBeater(Game game, PlayerColor me, Piece mover, Position to) {
        PlayerColor enemy = me == PlayerColor.RED ? PlayerColor.BLUE : PlayerColor.RED;
        for (Position neighbour : to.orthogonalNeighbours()) {
            Piece adjacent = game.board().pieceAt(neighbour).orElse(null);
            if (adjacent != null && adjacent.owner() == enemy && adjacent.isRevealed()) {
                BattleResult result = resolver.resolve(adjacent, mover);
                if (result.attackerSurvives()) {
                    return 1;
                }
            }
        }
        return 0;
    }

    /**
     * The deployment the bot makes for itself.
     *
     * <p>The ranks come from {@link ArmyFactory#standardRoster()} rather than being listed
     * out, so the bot can never deploy an army the game would reject: the Privates and Spies
     * form the front wall, the officers queue up behind them weakest first, and the flag is
     * left at the very back of the camp.
     *
     * <p>Squares are taken from the camp starting at the row nearest the enemy, so the two
     * full front rows are filled and the flag ends up in the rearmost row.
     */
    public Map<Position, Rank> deploy(PlayerColor me) {
        List<Position> squares = new ArrayList<>(Board.deploymentZone(me));
        squares.sort(Comparator
                .comparingInt((Position square) -> depth(me, square))
                .thenComparingInt(Position::col));
        if (squares.size() < ArmyFactory.ARMY_SIZE) {
            throw new IllegalStateException("the camp must hold at least "
                    + ArmyFactory.ARMY_SIZE + " squares but holds " + squares.size());
        }

        List<Rank> order = new ArrayList<>();
        // The wall first: the wall is every rank that is neither an officer nor the flag,
        // strongest of them first, so Privates hold the line and Spies sit behind them.
        ArmyFactory.standardRoster().stream()
                .filter(rank -> !rank.isOfficer() && !rank.isFlag())
                .sorted(Comparator.comparingInt(Rank::power).reversed())
                .forEach(order::add);
        // Then the officers, weakest at the front, so the bot's attack escalates as it
        // advances rather than all at once.
        ArmyFactory.standardRoster().stream()
                .filter(Rank::isOfficer)
                .sorted(Comparator.comparingInt(Rank::power))
                .forEach(order::add);
        // The flag goes last, which puts it in the rearmost camp row.
        order.add(Rank.FLAG);

        Map<Position, Rank> deployment = new LinkedHashMap<>();
        for (int i = 0; i < ArmyFactory.ARMY_SIZE; i++) {
            deployment.put(squares.get(i), order.get(i));
        }
        return deployment;
    }

    /** How far a square sits from the front of {@code me}'s camp: 0 is nearest the enemy. */
    private int depth(PlayerColor me, Position square) {
        return me == PlayerColor.RED ? 2 - square.row() : square.row() - 5;
    }
}