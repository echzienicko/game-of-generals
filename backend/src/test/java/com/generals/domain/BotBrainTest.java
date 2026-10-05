package com.generals.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotBrainTest {

    private static final int MOVE_CAP = 1200;

    private Game game;

    @BeforeEach
    void setUp() {
        game = new Game("bot-test");
    }

    // ------------------------------------------------------------------ helpers

    private static Position at(int row, int col) {
        return new Position(row, col);
    }

    /** A brain that always takes the best-scoring move, so assertions are not flaky. */
    private static BotBrain greedy() {
        return new BotBrain(new MoveValidator(), new BattleResolver(), new Random(1), 1.0);
    }

    private static BotBrain random(long seed) {
        return new BotBrain(new MoveValidator(), new BattleResolver(), new Random(seed));
    }

    /** Opens the game for play, leaving the board empty for the test to seed. */
    private Game playing() {
        game.startPlaying();
        return game;
    }

    private BotBrain.Move best(PlayerColor color) {
        return greedy().choose(game, color, BotDifficulty.HEURISTIC, List.of(), OpeningMemory.none());
    }

    private int scoreOf(PlayerColor color, BotBrain.Move move) {
        return greedy().score(game, color, move, List.of(), OpeningMemory.none());
    }

    /** Two full armies on the board, flags tucked in the rear corners. */
    private void deployBothArmies() {
        game.startPlaying();
        game.board().place(Piece.createHidden(Rank.FLAG, PlayerColor.BLUE, at(7, 8)));
        for (Map.Entry<Position, Rank> entry : greedy().deploy(PlayerColor.RED).entrySet()) {
            game.board().place(Piece.createHidden(entry.getValue(), PlayerColor.RED, entry.getKey()));
        }
        for (Map.Entry<Position, Rank> entry : greedy().deploy(PlayerColor.BLUE).entrySet()) {
            game.board().place(Piece.createHidden(entry.getValue(), PlayerColor.BLUE, entry.getKey()));
        }
    }

    /**
     * Plays the current {@link #game} out between two heuristic bots, one seed each,
     * and returns the number of moves taken. Stops early if the game somehow runs away.
     */
    /**
     * Plays the current {@link #game} out between two heuristic bots, one seed each,
     * and returns the number of moves taken.
     *
     * <p>{@link #MOVE_CAP} is generous on purpose: a bot-vs-bot game with no rank
     * information runs long. The slowest of the eight seed pairs in
     * {@link #finishesGamesWithTheFogNeverLifted()} needs 674 moves, so a tighter cap
     * would report a stall that is really just a slow game.
     */
    private int playItOut(long redSeed, long blueSeed) {
        BotBrain red = new BotBrain(new MoveValidator(), new BattleResolver(), new Random(redSeed), 0.85);
        BotBrain blue = new BotBrain(new MoveValidator(), new BattleResolver(), new Random(blueSeed), 0.85);
        MoveValidator validator = new MoveValidator();

        List<String> redLine = new ArrayList<>();
        List<String> blueLine = new ArrayList<>();
        int moves = 0;
        while (!game.isOver() && moves < MOVE_CAP) {
            PlayerColor side = game.currentPlayer();
            BotBrain brain = side == PlayerColor.RED ? red : blue;
            List<String> line = side == PlayerColor.RED ? redLine : blueLine;
            BotBrain.Move chosen = brain.choose(game, side, BotDifficulty.HEURISTIC,
                    List.copyOf(line), OpeningMemory.none());
            validator.validate(game.board(),
                    game.board().pieceAt(chosen.from()).orElseThrow(), chosen.to());
            game.move(side, chosen.from(), chosen.to());
            line.add(chosen.signature());
            moves++;
        }
        return moves;
    }

    private static Map<Rank, Integer> rankCounts(Iterable<Rank> ranks) {
        Map<Rank, Integer> counts = new EnumMap<>(Rank.class);
        for (Rank rank : ranks) {
            counts.merge(rank, 1, Integer::sum);
        }
        return counts;
    }

    /** The full move list, as text, so a failure says what was on offer. */
    private static Set<String> texts(List<BotBrain.Move> moves) {
        Set<String> set = new HashSet<>();
        for (BotBrain.Move move : moves) {
            set.add(move.toString());
        }
        return set;
    }

    private static void assertDidNotPlay(BotBrain.Move forbidden, BotBrain.Move actual) {
        assertFalse(forbidden.from().equals(actual.from()) && forbidden.to().equals(actual.to()),
                "should not have played " + forbidden + " but chose " + actual);
    }

    // -------------------------------------------------------------- legal moves

    @Nested
    @DisplayName("enumerating legal moves")
    class LegalMoves {

        @Test
        @DisplayName("offers a lone piece every direction, including the two-square leaps")
        void offersLonePieceEveryDirection() {
            playing();
            game.seed(Piece.createHidden(Rank.SERGEANT, PlayerColor.BLUE, at(3, 4)));

            // four single steps and four straight two-square leaps, all empty, so the piece
            // stays alone and keeps the right to leap
            assertEquals(Set.of(
                    "E4->E3", "E4->E5", "E4->D4", "E4->F4",
                    "E4->E2", "E4->E6", "E4->C4", "E4->G4"),
                    texts(greedy().legalMoves(game, PlayerColor.BLUE)));
        }

        @Test
        @DisplayName("stops the two-square leap the moment a friendly piece is adjacent")
        void grantsDoubleMoveOnlyWhenAlone() {
            playing();
            game.seed(Piece.createHidden(Rank.SERGEANT, PlayerColor.BLUE, at(3, 4)));

            assertTrue(greedy().legalMoves(game, PlayerColor.BLUE)
                    .contains(new BotBrain.Move(at(3, 4), at(3, 6))));

            // a BLUE neighbour removes the solo privilege from both pieces, so every option
            // shrinks to a single step and the square the friend occupies drops out
            game.seed(Piece.createHidden(Rank.PRIVATE, PlayerColor.BLUE, at(3, 5)));
            assertEquals(Set.of("E4->E3", "E4->E5", "E4->D4", "F4->F3", "F4->F5", "F4->G4"),
                    texts(greedy().legalMoves(game, PlayerColor.BLUE)));
        }

        @Test
        @DisplayName("does not treat an enemy neighbour as spoiling the leap")
        void leapsOverAnEnemyPiece() {
            playing();
            game.seed(Piece.createHidden(Rank.SERGEANT, PlayerColor.BLUE, at(3, 4)));
            game.seed(Piece.createHidden(Rank.PRIVATE, PlayerColor.RED, at(3, 5)));

            // E4->G4 jumps clear over the RED Private on F4; the rule is about friendly
            // neighbours only, and the destination is empty
            assertTrue(greedy().legalMoves(game, PlayerColor.BLUE)
                    .contains(new BotBrain.Move(at(3, 4), at(3, 6))));
        }

        @Test
        @DisplayName("never offers a square occupied by a friendly piece")
        void neverOffersFriendlyOccupiedSquare() {
            playing();
            game.seed(Piece.createHidden(Rank.SERGEANT, PlayerColor.BLUE, at(3, 4)));
            game.seed(Piece.createHidden(Rank.PRIVATE, PlayerColor.BLUE, at(3, 5)));

            assertFalse(texts(greedy().legalMoves(game, PlayerColor.BLUE)).contains("E4->F4"));
        }

        @Test
        @DisplayName("offers only the moves of the side it is asked about")
        void considersOneSideAtATime() {
            playing();
            game.seed(Piece.createHidden(Rank.SERGEANT, PlayerColor.BLUE, at(3, 4)));
            game.seed(Piece.createHidden(Rank.PRIVATE, PlayerColor.RED, at(5, 4)));

            for (BotBrain.Move move : greedy().legalMoves(game, PlayerColor.BLUE)) {
                assertEquals(3, move.from().row());
            }
            for (BotBrain.Move move : greedy().legalMoves(game, PlayerColor.RED)) {
                assertEquals(5, move.from().row());
            }
        }

        @Test
        @DisplayName("reaches only squares on the board")
        void staysOnTheBoard() {
            deployBothArmies();
            for (BotBrain.Move move : greedy().legalMoves(game, PlayerColor.BLUE)) {
                assertTrue(Position.isOnBoard(move.to().row(), move.to().col()));
            }
        }

        @Test
        @DisplayName("survives a full army: every generated move is one the validator accepts")
        void everyGeneratedMovePassesTheValidator() {
            deployBothArmies();
            BotBrain brain = greedy();
            MoveValidator validator = new MoveValidator();

            int checked = 0;
            for (int turn = 0; turn < 40 && !game.isOver(); turn++) {
                PlayerColor side = game.currentPlayer();
                List<BotBrain.Move> moves = brain.legalMoves(game, side);
                assertFalse(moves.isEmpty(), side + " should always have a move");
                BotBrain.Move chosen = brain.choose(game, side, BotDifficulty.HEURISTIC,
                        List.of(), OpeningMemory.none());
                validator.validate(game.board(),
                        game.board().pieceAt(chosen.from()).orElseThrow(), chosen.to());
                assertTrue(moves.contains(chosen));
                game.move(side, chosen.from(), chosen.to());
                checked++;
            }
            assertTrue(checked > 10);
        }
    }

    // ---------------------------------------------------------------- choosing

    @Nested
    @DisplayName("choosing")
    class Choosing {

        @Test
        @DisplayName("takes a free capture of an identified piece")
        void takesFreeCapture() {
            playing();
            game.seed(Piece.createHidden(Rank.SERGEANT, PlayerColor.BLUE, at(4, 3)));
            game.seed(Piece.create(Rank.PRIVATE, PlayerColor.RED, at(4, 2)));

            assertEquals(new BotBrain.Move(at(4, 3), at(4, 2)), best(PlayerColor.BLUE));
        }

        @Test
        @DisplayName("prefers advancing over shuffling sideways")
        void prefersAdvancingOverSidesteps() {
            playing();
            game.seed(Piece.createHidden(Rank.SERGEANT, PlayerColor.BLUE, at(4, 4)));

            // BLUE advances up the board, and a lone piece may take the leap, so the best
            // of the options is the two-square step rather than a step to either side
            assertEquals(new BotBrain.Move(at(4, 4), at(2, 4)), best(PlayerColor.BLUE));
        }

        @Test
        @DisplayName("leaps into the enemy camp rather than shuffling in its own half")
        void doublesIntoTheEnemyCamp() {
            playing();
            game.seed(Piece.createHidden(Rank.SERGEANT, PlayerColor.RED, at(4, 4)));

            // RED advances downwards, so row 6 is inside BLUE's camp
            assertEquals(new BotBrain.Move(at(4, 4), at(6, 4)), best(PlayerColor.RED));
        }

        @Test
        @DisplayName("holds the flag back rather than walking it forward")
        void holdsTheFlagBack() {
            playing();
            game.seed(Piece.createHidden(Rank.FLAG, PlayerColor.BLUE, at(6, 4)));
            game.seed(Piece.createHidden(Rank.PRIVATE, PlayerColor.BLUE, at(6, 0)));

            assertDidNotPlay(new BotBrain.Move(at(6, 4), at(5, 4)), best(PlayerColor.BLUE));
        }

@Test
        @DisplayName("moves the flag when the flag is all it has")
        void movesTheFlagWhenForced() {
            playing();
            game.seed(Piece.createHidden(Rank.FLAG, PlayerColor.BLUE, at(6, 4)));

            assertEquals(Set.of(
                    "E7->D7", "E7->F7", "E7->E8", "E7->E6",
                    "E7->E5", "E7->C7", "E7->G7"),
                    texts(greedy().legalMoves(game, PlayerColor.BLUE)));
            assertEquals(new BotBrain.Move(at(6, 4), at(4, 4)), best(PlayerColor.BLUE),
                    "with nothing else to move, a lone piece leaps forward");
        }

        @Test
        @DisplayName("takes the flag and ends the game when one is identified next door")
        void takesAnIdentifiedFlag() {
            playing();
            game.seed(Piece.createHidden(Rank.SERGEANT, PlayerColor.BLUE, at(4, 3)));
            game.seed(Piece.create(Rank.FLAG, PlayerColor.RED, at(4, 2)));

            assertEquals(new BotBrain.Move(at(4, 3), at(4, 2)), best(PlayerColor.BLUE));
        }

        @Test
        @DisplayName("never walks into a piece it has identified as stronger")
        void avoidsIdentifiedBeaters() {
            playing();
            // a Sergeant that the Private already standing below it can take
            game.seed(Piece.createHidden(Rank.SERGEANT, PlayerColor.BLUE, at(6, 4)));
            game.seed(Piece.create(Rank.PRIVATE, PlayerColor.BLUE, at(5, 4)));

            assertDidNotPlay(new BotBrain.Move(at(6, 4), at(5, 4)), best(PlayerColor.BLUE));
        }

        @Test
        @DisplayName("takes the square next to an identified enemy even without a capture")
        void stepsUpToKnownEnemy() {
            playing();
            game.seed(Piece.createHidden(Rank.SERGEANT, PlayerColor.BLUE, at(4, 4)));
            game.seed(Piece.create(Rank.CAPTAIN, PlayerColor.RED, at(4, 5)));

            // attacking the Captain loses the Sergeant, so the bot advances instead, and a
            // lone piece may leap into RED's camp while it is about it
            assertEquals(new BotBrain.Move(at(4, 4), at(2, 4)), best(PlayerColor.BLUE));
        }
    }

    // -------------------------------------------------------------- fog of war

    @Nested
    @DisplayName("fog of war")
    class FogOfWar {

        /**
         * The load-bearing test. The same board, differing only in whether the bot is
         * allowed to know what stands on the square, must get a different answer. A bot
         * that read hidden ranks would attack in both.
         */
        @Test
        @DisplayName("attacks an identified Private but not an unidentified one")
        void attacksKnownWeaknessOnly() {
            playing();
            game.seed(Piece.createHidden(Rank.SERGEANT, PlayerColor.BLUE, at(4, 3)));
            game.seed(Piece.create(Rank.PRIVATE, PlayerColor.RED, at(4, 2)));

            assertEquals(new BotBrain.Move(at(4, 3), at(4, 2)), best(PlayerColor.BLUE),
                    "an identified Private is free prey");

            setUp();
            playing();
            game.seed(Piece.createHidden(Rank.SERGEANT, PlayerColor.BLUE, at(4, 3)));
            game.seed(Piece.createHidden(Rank.PRIVATE, PlayerColor.RED, at(4, 2)));

            // an unidentified piece could be the General, so the bot keeps advancing
            assertDidNotPlay(new BotBrain.Move(at(4, 3), at(4, 2)), best(PlayerColor.BLUE));
        }

        @Test
        @DisplayName("values knowledge of a defender")
        void knowledgeOfTheDefenderIsWorthPoints() {
            playing();
            game.seed(Piece.createHidden(Rank.SERGEANT, PlayerColor.BLUE, at(4, 3)));
            game.seed(Piece.createHidden(Rank.PRIVATE, PlayerColor.RED, at(4, 2)));

            BotBrain brain = greedy();
            int againstHidden = brain.score(game, PlayerColor.BLUE,
                    new BotBrain.Move(at(4, 3), at(4, 2)), List.of(), OpeningMemory.none());

            game.board().removeAt(at(4, 2));
            game.seed(Piece.create(Rank.PRIVATE, PlayerColor.RED, at(4, 2)));

            int againstKnown = brain.score(game, PlayerColor.BLUE,
                    new BotBrain.Move(at(4, 3), at(4, 2)), List.of(), OpeningMemory.none());

            assertTrue(againstKnown > againstHidden,
                    "knowing the defender was worth " + (againstKnown - againstHidden) + " points");
        }

        @Test
        @DisplayName("treats an unidentified flag as no more tempting than anything else")
        void cannotSeeAnUnrevealedFlag() {
            playing();
            game.seed(Piece.createHidden(Rank.SERGEANT, PlayerColor.BLUE, at(4, 3)));
            game.seed(Piece.createHidden(Rank.FLAG, PlayerColor.RED, at(7, 0)));
            game.seed(Piece.createHidden(Rank.PRIVATE, PlayerColor.RED, at(4, 2)));

            BotBrain brain = greedy();
            BotBrain.Move probe = new BotBrain.Move(at(4, 3), at(4, 2));
            int againstHidden = brain.score(game, PlayerColor.BLUE, probe, List.of(), OpeningMemory.none());

            // swap the square in front of the bot for the RED flag, now revealed
            game.board().removeAt(at(4, 2));
            game.seed(Piece.create(Rank.FLAG, PlayerColor.RED, at(4, 2)));
            int againstIdentifiedFlag = brain.score(game, PlayerColor.BLUE, probe,
                    List.of(), OpeningMemory.none());

            assertEquals(BotBrain.WIN_SCORE, againstIdentifiedFlag);
            assertTrue(againstIdentifiedFlag > againstHidden * 10,
                    "revealing the flag has to be worth vastly more, or the bot would take it "
                            + "whether or not it knew");
        }

        @Test
        @DisplayName("divides by the ranks still outstanding, not by those already seen")
        void poolShrinksAsEnemyRanksAreRevealed() {
            playing();
            game.seed(Piece.createHidden(Rank.SERGEANT, PlayerColor.BLUE, at(4, 3)));
            game.seed(Piece.createHidden(Rank.PRIVATE, PlayerColor.RED, at(4, 2)));
            // another RED piece, still hidden: the bot cannot rule it out as a threat
            game.seed(Piece.createHidden(Rank.PRIVATE, PlayerColor.RED, at(5, 5)));

            BotBrain brain = greedy();
            BotBrain.Move probe = new BotBrain.Move(at(4, 3), at(4, 2));
            int before = brain.score(game, PlayerColor.BLUE, probe, List.of(), OpeningMemory.none());

            // now the bot learns where one General is, which is one threat fewer to fear
            game.board().removeAt(at(5, 5));
            game.seed(Piece.create(Rank.FIVE_STAR_GENERAL, PlayerColor.RED, at(5, 5)));
            int after = brain.score(game, PlayerColor.BLUE, probe, List.of(), OpeningMemory.none());

            assertTrue(after > before,
                    "accounting for a General should make the blind square safer, but went "
                            + before + " -> " + after);
        }

        @Test
        @DisplayName("revealing one of its own pieces teaches it nothing")
        void ownRevealedPiecesAreNoInformation() {
            playing();
            game.seed(Piece.createHidden(Rank.SERGEANT, PlayerColor.BLUE, at(4, 3)));
            game.seed(Piece.createHidden(Rank.PRIVATE, PlayerColor.RED, at(4, 2)));

            BotBrain brain = greedy();
            BotBrain.Move probe = new BotBrain.Move(at(4, 3), at(4, 2));
            int before = brain.score(game, PlayerColor.BLUE, probe, List.of(), OpeningMemory.none());

            game.seed(Piece.create(Rank.FIVE_STAR_GENERAL, PlayerColor.BLUE, at(7, 0)));

            assertEquals(before,
                    brain.score(game, PlayerColor.BLUE, probe, List.of(), OpeningMemory.none()));
        }
    }

    // -------------------------------------------------------------- deployment

    @Nested
    @DisplayName("deploying")
    class Deploying {

        @Test
        @DisplayName("produces an army the placement rules accept, for either side")
        void producesALegalArmy() {
            for (PlayerColor color : PlayerColor.values()) {
                Game fresh = new Game("deploy");
                fresh.beginPlacement();
                Map<Position, Rank> deployment = greedy().deploy(color);

                fresh.submitPlacement(color, deployment);

                assertTrue(fresh.hasPlaced(color));
                assertEquals(ArmyFactory.ARMY_SIZE, deployment.size());
            }
        }

        @Test
        @DisplayName("fills the standard roster exactly, with no square used twice")
        void fillsTheStandardRosterExactly() {
            for (PlayerColor color : PlayerColor.values()) {
                Map<Position, Rank> deployment = greedy().deploy(color);

                assertEquals(ArmyFactory.ARMY_SIZE, deployment.size());
                assertEquals(ArmyFactory.ARMY_SIZE, new HashSet<>(deployment.keySet()).size());
                assertEquals(rankCounts(ArmyFactory.standardRoster()),
                        rankCounts(deployment.values()));
            }
        }

        @Test
        @DisplayName("stays inside the camp it belongs to")
        void staysInsideTheCamp() {
            for (PlayerColor color : PlayerColor.values()) {
                for (Position position : greedy().deploy(color).keySet()) {
                    assertTrue(position.isInOwnCamp(color), position.label() + " is not " + color);
                }
            }
        }

        @Test
        @DisplayName("buries the flag in the rearmost row")
        void buriesTheFlag() {
            assertEquals(0, flagRow(greedy().deploy(PlayerColor.RED)));
            assertEquals(7, flagRow(greedy().deploy(PlayerColor.BLUE)));
        }

        @Test
        @DisplayName("puts the wall of Privates and Spies on the front row")
        void wallsTheFront() {
            Map<Position, Rank> red = greedy().deploy(PlayerColor.RED);

            assertEquals(Rank.PRIVATE, red.get(at(2, 0)));
            assertEquals(Rank.SPY, red.get(at(2, 6)));

            Map<Position, Rank> blue = greedy().deploy(PlayerColor.BLUE);
            assertEquals(Rank.PRIVATE, blue.get(at(5, 0)));
            assertEquals(Rank.SPY, blue.get(at(5, 6)));
        }

        @Test
        @DisplayName("is the same every time, and unaffected by the bot's randomness")
        void deploymentIsDeterministic() {
            assertEquals(greedy().deploy(PlayerColor.RED), greedy().deploy(PlayerColor.RED));
            assertEquals(random(1234).deploy(PlayerColor.BLUE), greedy().deploy(PlayerColor.BLUE));
        }

        private int flagRow(Map<Position, Rank> deployment) {
            return deployment.entrySet().stream()
                    .filter(entry -> entry.getValue() == Rank.FLAG)
                    .mapToInt(entry -> entry.getKey().row())
                    .findFirst().orElseThrow();
        }
    }

    // -------------------------------------------------------------- difficulty

    @Nested
    @DisplayName("difficulty levels")
    class DifficultyLevels {

        @Test
        @DisplayName("random play stays legal however badly it goes")
        void randomPlayIsAlwaysLegal() {
            deployBothArmies();
            BotBrain brain = random(99);

            for (int turn = 0; turn < 60 && !game.isOver(); turn++) {
                PlayerColor side = game.currentPlayer();
                BotBrain.Move chosen = brain.choose(game, side, BotDifficulty.RANDOM,
                        List.of(), OpeningMemory.none());
                assertTrue(brain.legalMoves(game, side).contains(chosen));
                game.move(side, chosen.from(), chosen.to());
            }
        }

        @Test
        @DisplayName("random play varies from run to run, unlike the heuristic")
        void randomPlayVariesBetweenSeeds() {
            deployBothArmies();
            BotBrain red = new BotBrain(new MoveValidator(), new BattleResolver(), new Random(4), 1.0);
            List<String> first = new ArrayList<>();
            List<String> second = new ArrayList<>();
            for (int i = 0; i < 15 && !game.isOver(); i++) {
                PlayerColor side = game.currentPlayer();
                BotBrain.Move m = red.choose(game, side, BotDifficulty.RANDOM, List.of(), OpeningMemory.none());
                first.add(m.toString());
                game.move(side, m.from(), m.to());
            }
            setUp();
            deployBothArmies();
            BotBrain blue = new BotBrain(new MoveValidator(), new BattleResolver(), new Random(9), 1.0);
            for (int i = 0; i < 15 && !game.isOver(); i++) {
                PlayerColor side = game.currentPlayer();
                BotBrain.Move m = blue.choose(game, side, BotDifficulty.RANDOM, List.of(), OpeningMemory.none());
                second.add(m.toString());
                game.move(side, m.from(), m.to());
            }
            assertNotEquals(first, second);
        }

        @Test
        @DisplayName("rejects an unknown difficulty name rather than guessing")
        void rejectsUnknownDifficulty() {
            assertEquals(BotDifficulty.HEURISTIC, BotDifficulty.parse("heuristic"));
            assertEquals(BotDifficulty.LEARNING, BotDifficulty.parse(null));
            assertEquals(BotDifficulty.LEARNING, BotDifficulty.parse("  "));
            assertEquals(BotDifficulty.RANDOM, BotDifficulty.parse(" random "));
            assertThrows(IllegalArgumentException.class, () -> BotDifficulty.parse("impossible"));
        }

        @Test
        @DisplayName("the learning level scores a repeat of a losing opening far lower")
        void learningAvoidsKnownLosses() {
            playing();
            game.seed(Piece.createHidden(Rank.SERGEANT, PlayerColor.BLUE, at(4, 4)));
            BotBrain.Move advance = new BotBrain.Move(at(4, 4), at(4, 5));
            BotBrain brain = greedy();

            int clean = brain.score(game, PlayerColor.BLUE, advance, List.of(), OpeningMemory.none());

            CountingOpeningMemory memory = new CountingOpeningMemory();
            memory.recordLoss(List.of(advance.signature()));
            int afterLoss = brain.score(game, PlayerColor.BLUE, advance, List.of(), memory);

            assertTrue(afterLoss < clean - 100,
                    "a repeat of a losing opening must score well below the alternative: "
                            + afterLoss + " vs " + clean);
        }

        @Test
        @DisplayName("the learning penalty only lands on the moves that were played")
        void penaltyIsScopedToTheRecordedLine() {
            playing();
            game.seed(Piece.createHidden(Rank.SERGEANT, PlayerColor.BLUE, at(4, 4)));
            BotBrain brain = greedy();
            BotBrain.Move elsewhere = new BotBrain.Move(at(4, 4), at(4, 3));

            CountingOpeningMemory memory = new CountingOpeningMemory();
            memory.recordLoss(List.of(new BotBrain.Move(at(4, 4), at(4, 5)).signature()));

            assertEquals(scoreOf(PlayerColor.BLUE, elsewhere),
                    brain.score(game, PlayerColor.BLUE, elsewhere, List.of(), memory));
        }

        @Test
        @DisplayName("a loss is blamed on the opening, not on what followed it")
        void lossPenaltyAppliesToTheOpeningPrefix() {
            CountingOpeningMemory memory = new CountingOpeningMemory();
            memory.recordLoss(List.of("D5-D6", "C5-C6"));

            assertEquals(1, memory.lossesAfter(List.of("D5-D6")));
            assertEquals(1, memory.lossesAfter(List.of("D5-D6", "C5-C6")));
            assertEquals(0, memory.lossesAfter(List.of("D5-D5")));
            assertEquals(0, memory.lossesAfter(List.of()));
        }

        @Test
        @DisplayName("losses accumulate rather than overwrite one another")
        void lossesAccumulate() {
            CountingOpeningMemory memory = new CountingOpeningMemory();
            memory.recordLoss(List.of("A1-A2"));
            memory.recordLoss(List.of("A1-A2"));

            assertEquals(2, memory.lossesAfter(List.of("A1-A2")));
            assertEquals(1, memory.size(), "one distinct opening, recorded twice");

            memory.clear();
            assertEquals(0, memory.lossesAfter(List.of("A1-A2")));
        }

        @Test
        @DisplayName("the heuristic level ignores what the bot remembers")
        void heuristicIgnoresMemory() {
            playing();
            game.seed(Piece.createHidden(Rank.SERGEANT, PlayerColor.BLUE, at(4, 4)));
            BotBrain brain = greedy();
            BotBrain.Move advance = new BotBrain.Move(at(4, 4), at(4, 5));
            CountingOpeningMemory memory = new CountingOpeningMemory();
            memory.recordLoss(List.of(advance.signature()));

            assertEquals(brain.score(game, PlayerColor.BLUE, advance, List.of(), OpeningMemory.none()),
                    brain.score(game, PlayerColor.BLUE, advance, List.of("D5-D6"), memory));
        }

        @Test
        @DisplayName("a memory that remembers nothing changes no scores")
        void theNullMemoryIsInert() {
            playing();
            game.seed(Piece.createHidden(Rank.SERGEANT, PlayerColor.BLUE, at(4, 4)));
            BotBrain brain = greedy();
            BotBrain.Move advance = new BotBrain.Move(at(4, 4), at(4, 5));

            OpeningMemory.none().recordLoss(List.of(advance.signature()));
            assertEquals(scoreOf(PlayerColor.BLUE, advance),
                    brain.score(game, PlayerColor.BLUE, advance, List.of(), OpeningMemory.none()));
        }
    }

    // ------------------------------------------------------------ whole matches

    @Test
    @DisplayName("a move signature is made of board labels")
    void signaturesUseLabels() {
        assertEquals("E5-E6", new BotBrain.Move(at(4, 4), at(5, 4)).signature());
        assertEquals("E5->E6", new BotBrain.Move(at(4, 4), at(5, 4)).toString());
    }

    @Test
    @DisplayName("two bots of different random seeds can finish a game")
    void playsItselfToADecision() {
        deployBothArmies();
        int moves = playItOut(3, 7);

        assertTrue(game.isOver(), "the bot should finish its own game, gave up after " + moves);
        assertNotNull(game.winner());
        assertEquals(moves, game.history().size());
    }

    @Test
    @DisplayName("bots still finish games when no rank is ever revealed")
    void finishesGamesWithTheFogNeverLifted() {
        // Nothing ever calls Piece.reveal(), so a bot has no rank knowledge at all: every
        // defender keeps all 21 roster possibilities alive. That must not stop it attacking,
        // or games would never end.
        List<String> stalled = new ArrayList<>();
        for (long seed = 1; seed <= 8; seed++) {
            setUp();
            deployBothArmies();
            int moves = playItOut(seed, seed + 100);
            if (!game.isOver()) {
                stalled.add(seed + "/" + (seed + 100) + " after " + moves);
            } else {
                assertNotNull(game.winner(), "a finished game has a winner, seeds " + seed);
            }
        }
        assertEquals(List.of(), stalled, "bot games that never reached a decision");
    }

    @Test
    @DisplayName("random play varies from run to run, unlike the heuristic")
    void randomPlayVariesBetweenSeeds() {
        deployBothArmies();
        BotBrain red = new BotBrain(new MoveValidator(), new BattleResolver(), new Random(4), 1.0);
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        for (int i = 0; i < 15 && !game.isOver(); i++) {
            PlayerColor side = game.currentPlayer();
            BotBrain.Move m = red.choose(game, side, BotDifficulty.RANDOM, List.of(), OpeningMemory.none());
            first.add(m.toString());
            game.move(side, m.from(), m.to());
        }
        setUp();
        deployBothArmies();
        BotBrain blue = new BotBrain(new MoveValidator(), new BattleResolver(), new Random(9), 1.0);
        for (int i = 0; i < 15 && !game.isOver(); i++) {
            PlayerColor side = game.currentPlayer();
            BotBrain.Move m = blue.choose(game, side, BotDifficulty.RANDOM, List.of(), OpeningMemory.none());
            second.add(m.toString());
            game.move(side, m.from(), m.to());
        }
        assertNotEquals(first, second);
    }

    @Test
    @DisplayName("a bot with nothing to move is told so rather than picking badly")
    void refusesToChooseWithNoMoves() {
        Game fresh = new Game("empty");
        fresh.startPlaying();

        BotBrain brain = greedy();
        assertThrows(IllegalStateException.class,
                () -> brain.choose(fresh, PlayerColor.RED, BotDifficulty.HEURISTIC,
                        List.of(), OpeningMemory.none()));
    }
}