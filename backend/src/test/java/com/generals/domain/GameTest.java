package com.generals.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameTest {

    private Game game;

    @BeforeEach
    void setUp() {
        game = new Game("test-game");
    }

    // ------------------------------------------------------------------ helpers

    private Piece red(Rank rank, int row, int col) {
        return Piece.createHidden(rank, PlayerColor.RED, new Position(row, col));
    }

    private Piece blue(Rank rank, int row, int col) {
        return Piece.createHidden(rank, PlayerColor.BLUE, new Position(row, col));
    }

    private static Position at(int row, int col) {
        return new Position(row, col);
    }

    /**
     * Starts a game mid-flight with both flags present, which is what a real board
     * always looks like — without an enemy flag the game is already over.
     */
    private void startWithFlags() {
        game.startPlaying();
        game.seed(red(Rank.FLAG, 0, 0));
        game.seed(blue(Rank.FLAG, 7, 8));
    }

    /** Starts a game with no flags, for tests that place their own. */
    private void startBare() {
        game.startPlaying();
    }

    /** A legal 21-piece deployment filling the first 21 squares of the player's own camp. */
    private Map<Position, Rank> deployment(PlayerColor color) {
        Map<Position, Rank> map = new LinkedHashMap<>();
        List<Position> zone = Board.deploymentZone(color);
        List<Rank> roster = ArmyFactory.standardRoster();
        for (int i = 0; i < ArmyFactory.ARMY_SIZE; i++) {
            map.put(zone.get(i), roster.get(i));
        }
        return map;
    }

    private void placeBothArmies() {
        game.beginPlacement();
        game.submitPlacement(PlayerColor.RED, deployment(PlayerColor.RED));
        game.submitPlacement(PlayerColor.BLUE, deployment(PlayerColor.BLUE));
    }

    // --------------------------------------------------------------- deployment

    @Nested
    @DisplayName("deployment")
    class Deployment {

        @Test
        @DisplayName("both sides must deploy 21 pieces before play begins")
        void needsBothSides() {
            game.beginPlacement();
            assertEquals(Game.Status.PLACEMENT, game.status());

            game.submitPlacement(PlayerColor.RED, deployment(PlayerColor.RED));
            assertEquals(Game.Status.PLACEMENT, game.status(), "waiting for blue");

            game.submitPlacement(PlayerColor.BLUE, deployment(PlayerColor.BLUE));
            assertEquals(Game.Status.IN_PROGRESS, game.status());
        }

        @Test
        @DisplayName("red always moves first")
        void redMovesFirst() {
            placeBothArmies();
            assertEquals(PlayerColor.RED, game.currentPlayer());
        }

        @Test
        @DisplayName("a deployment of the wrong size is rejected")
        void wrongSizeRejected() {
            game.beginPlacement();
            Map<Position, Rank> tooFew = deployment(PlayerColor.RED);
            tooFew.remove(tooFew.keySet().iterator().next());
            assertThrows(IllegalMoveException.class,
                    () -> game.submitPlacement(PlayerColor.RED, tooFew));
        }

        @Test
        @DisplayName("a deployment outside the own camp is rejected")
        void outOfCampRejected() {
            game.beginPlacement();
            Map<Position, Rank> bad = deployment(PlayerColor.RED);
            List<Position> keys = new ArrayList<>(bad.keySet());
            Rank rank = bad.remove(keys.get(0));
            bad.put(at(5, 0), rank); // inside blue's camp
            assertThrows(IllegalMoveException.class,
                    () -> game.submitPlacement(PlayerColor.RED, bad));
        }

        @Test
        @DisplayName("a deployment that is not the standard army is rejected")
        void nonStandardRosterRejected() {
            game.beginPlacement();
            Map<Position, Rank> bad = deployment(PlayerColor.RED);
            List<Position> keys = new ArrayList<>(bad.keySet());
            bad.put(keys.get(0), Rank.PRIVATE); // too many privates, no flag
            assertThrows(IllegalMoveException.class,
                    () -> game.submitPlacement(PlayerColor.RED, bad));
        }

        @Test
        @DisplayName("a side cannot deploy twice")
        void noDoubleDeployment() {
            game.beginPlacement();
            game.submitPlacement(PlayerColor.RED, deployment(PlayerColor.RED));
            assertThrows(IllegalMoveException.class,
                    () -> game.submitPlacement(PlayerColor.RED, deployment(PlayerColor.RED)));
        }

        @Test
        @DisplayName("deployed pieces stay hidden from the opponent")
        void deployedPiecesAreHidden() {
            placeBothArmies();
            assertTrue(game.board().piecesOf(PlayerColor.BLUE).stream().noneMatch(Piece::isRevealed));
        }
    }

    // ------------------------------------------------------------------- turns

    @Nested
    @DisplayName("turn order")
    class TurnOrder {

        @Test
        @DisplayName("players alternate")
        void alternates() {
            startWithFlags();
            game.seed(red(Rank.COLONEL, 4, 4));
            game.seed(blue(Rank.COLONEL, 7, 7));

            game.move(PlayerColor.RED, at(4, 4), at(3, 4));
            assertEquals(PlayerColor.BLUE, game.currentPlayer());

            game.move(PlayerColor.BLUE, at(7, 7), at(6, 7));
            assertEquals(PlayerColor.RED, game.currentPlayer());
        }

        @Test
        @DisplayName("moving out of turn is rejected")
        void outOfTurnRejected() {
            startWithFlags();
            game.seed(red(Rank.COLONEL, 4, 4));
            assertThrows(IllegalMoveException.class,
                    () -> game.move(PlayerColor.BLUE, at(4, 4), at(3, 4)));
        }

        @Test
        @DisplayName("you cannot move the opponent's piece")
        void cannotMoveEnemyPiece() {
            startWithFlags();
            game.seed(blue(Rank.COLONEL, 4, 4)); // red tries to move a blue piece
            assertThrows(IllegalMoveException.class,
                    () -> game.move(PlayerColor.RED, at(4, 4), at(3, 4)));
        }

        @Test
        @DisplayName("moving from an empty square is rejected")
        void emptySquareRejected() {
            startWithFlags();
            assertThrows(IllegalMoveException.class,
                    () -> game.move(PlayerColor.RED, at(4, 4), at(3, 4)));
        }
    }

    // ----------------------------------------------------------------- battles

    @Nested
    @DisplayName("battles")
    class Battles {

        @Test
        @DisplayName("a winning attacker advances and the defender is removed")
        void attackerAdvances() {
            startWithFlags();
            Piece colonel = red(Rank.COLONEL, 4, 4);
            game.seed(colonel);
            game.seed(blue(Rank.SERGEANT, 3, 4));

            MoveRecord record = game.move(PlayerColor.RED, at(4, 4), at(3, 4));

            assertTrue(record.wasContested());
            assertEquals(at(3, 4), colonel.position());
            assertTrue(game.board().isEmpty(at(4, 4)));
            assertSame(colonel, game.board().pieceAt(at(3, 4)).orElseThrow());
        }

        @Test
        @DisplayName("a losing attacker dies and the defender holds its square")
        void defenderHolds() {
            startWithFlags();
            Piece privateUnit = red(Rank.PRIVATE, 4, 4);
            Piece colonel = blue(Rank.COLONEL, 3, 4);
            game.seed(privateUnit);
            game.seed(colonel);

            game.move(PlayerColor.RED, at(4, 4), at(3, 4));

            assertTrue(game.board().isEmpty(at(4, 4)));
            assertEquals(at(3, 4), colonel.position());
            assertSame(colonel, game.board().pieceAt(at(3, 4)).orElseThrow());
        }

        @Test
        @DisplayName("equal ranks leave the square empty")
        void mutualDestruction() {
            startWithFlags();
            game.seed(red(Rank.MAJOR, 4, 4));
            game.seed(blue(Rank.MAJOR, 3, 4));

            game.move(PlayerColor.RED, at(4, 4), at(3, 4));

            assertTrue(game.board().isEmpty(at(4, 4)));
            assertTrue(game.board().isEmpty(at(3, 4)));
        }

        @Test
        @DisplayName("a spy beats a colonel in the field")
        void spyStrikesDownAColonel() {
            startWithFlags();
            Piece spy = red(Rank.SPY, 4, 4);
            game.seed(spy);
            game.seed(blue(Rank.COLONEL, 3, 4));

            game.move(PlayerColor.RED, at(4, 4), at(3, 4));

            assertEquals(at(3, 4), spy.position());
        }

        @Test
        @DisplayName("a spy is defeated by a private")
        void spyBeatenByPrivate() {
            startWithFlags();
            game.seed(red(Rank.SPY, 4, 4));
            Piece priv = blue(Rank.PRIVATE, 3, 4);
            game.seed(priv);

            game.move(PlayerColor.RED, at(4, 4), at(3, 4));

            assertTrue(game.board().isEmpty(at(4, 4)));
            assertSame(priv, game.board().pieceAt(at(3, 4)).orElseThrow());
        }

        @Test
        @DisplayName("a flag that attacks a soldier is lost")
        void flagAttackingIsDestroyed() {
            startBare();
            game.seed(red(Rank.FLAG, 4, 4));
            game.seed(blue(Rank.FLAG, 7, 8)); // so the game is not already won
            Piece soldier = blue(Rank.PRIVATE, 3, 4);
            game.seed(soldier);

            game.move(PlayerColor.RED, at(4, 4), at(3, 4));

            assertTrue(game.board().isEmpty(at(4, 4)));
            assertSame(soldier, game.board().pieceAt(at(3, 4)).orElseThrow());
        }

        @Test
        @DisplayName("a battle reveals nothing, not even the winner")
        void battlesRevealNothing() {
            startWithFlags();
            Piece spy = red(Rank.SPY, 4, 4);
            Piece colonel = blue(Rank.COLONEL, 3, 4);
            game.seed(spy);
            game.seed(colonel);
            assertFalse(spy.isRevealed(), "starts hidden");
            assertFalse(colonel.isRevealed(), "starts hidden");

            game.move(PlayerColor.RED, at(4, 4), at(3, 4));

            // The spy dies and the colonel survives on (3,4); neither rank is exposed.
            assertFalse(spy.isRevealed(), "the loser is not exposed");
            assertFalse(colonel.isRevealed(), "the winner is not exposed either");
        }
    }

    // ----------------------------------------------------------- win conditions

    @Nested
    @DisplayName("win conditions")
    class WinConditions {

        @Test
        @DisplayName("capturing the enemy flag wins immediately")
        void captureFlagWins() {
            startBare();
            game.seed(red(Rank.FLAG, 0, 0));
            game.seed(red(Rank.FIVE_STAR_GENERAL, 4, 4));
            game.seed(blue(Rank.FLAG, 3, 4));

            game.move(PlayerColor.RED, at(4, 4), at(3, 4));

            assertTrue(game.isOver());
            assertEquals(PlayerColor.RED, game.winner());
            assertNotNull(game.winReason());
            assertTrue(game.winReason().toLowerCase().contains("flag"));
        }

        @Test
        @DisplayName("even a private that takes the flag wins")
        void privateCanTakeTheFlag() {
            startBare();
            game.seed(red(Rank.FLAG, 0, 0));
            game.seed(red(Rank.PRIVATE, 4, 4));
            game.seed(blue(Rank.FLAG, 3, 4));

            game.move(PlayerColor.RED, at(4, 4), at(3, 4));

            assertTrue(game.isOver());
            assertEquals(PlayerColor.RED, game.winner());
        }

        @Test
        @DisplayName("a flag reaching the enemy camp is not an instant win")
        void flagEscapeNeedsOneMoreTurn() {
            startBare();
            game.seed(red(Rank.FLAG, 5, 0));
            game.seed(blue(Rank.FLAG, 7, 8));
            game.seed(blue(Rank.PRIVATE, 7, 7));

            game.move(PlayerColor.RED, at(5, 0), at(5, 1));

            assertFalse(game.isOver(), "the opponent must still get a chance to stop it");
            assertTrue(game.isFlagEscapePending());
            assertEquals(PlayerColor.BLUE, game.currentPlayer());
        }

        @Test
        @DisplayName("an escape that survives the final attack wins")
        void survivedEscapeWins() {
            startBare();
            game.seed(red(Rank.FLAG, 5, 0));
            game.seed(blue(Rank.FLAG, 7, 8));
            game.seed(blue(Rank.PRIVATE, 7, 7));

            game.move(PlayerColor.RED, at(5, 0), at(5, 1)); // flag escapes
            assertFalse(game.isOver());

            game.move(PlayerColor.BLUE, at(7, 7), at(6, 7)); // too far away to stop it

            assertTrue(game.isOver());
            assertEquals(PlayerColor.RED, game.winner());
        }

        @Test
        @DisplayName("the opponent can still stop the escape on its last move")
        void opponentStopsEscape() {
            startBare();
            game.seed(red(Rank.FLAG, 5, 0));
            game.seed(blue(Rank.FLAG, 7, 8));
            game.seed(blue(Rank.SPY, 5, 2));

            game.move(PlayerColor.RED, at(5, 0), at(5, 1)); // flag escapes
            assertFalse(game.isOver());

            game.move(PlayerColor.BLUE, at(5, 2), at(5, 1)); // spy captures the flag

            assertTrue(game.isOver());
            assertEquals(PlayerColor.BLUE, game.winner());
        }

        @Test
        @DisplayName("a flag staying in friendly territory does not escape")
        void friendlyMoveDoesNotEscape() {
            startBare();
            game.seed(red(Rank.FLAG, 0, 0));
            game.seed(blue(Rank.FLAG, 7, 8));
            game.seed(blue(Rank.PRIVATE, 7, 7));

            game.move(PlayerColor.RED, at(0, 0), at(0, 1));

            assertFalse(game.isFlagEscapePending());
            assertFalse(game.isOver());
        }

        @Test
        @DisplayName("no moves are accepted once the game is over")
        void noMovesAfterGameOver() {
            startBare();
            game.seed(red(Rank.FLAG, 0, 0));
            game.seed(red(Rank.FIVE_STAR_GENERAL, 4, 4));
            game.seed(blue(Rank.FLAG, 3, 4));
            game.seed(blue(Rank.PRIVATE, 6, 6));

            game.move(PlayerColor.RED, at(4, 4), at(3, 4));
            assertTrue(game.isOver());

            assertThrows(IllegalStateException.class,
                    () -> game.move(PlayerColor.BLUE, at(6, 6), at(5, 6)));
        }
    }

    @Nested
    @DisplayName("leaving the game")
    class Resigning {

        @Test
        @DisplayName("a resignation hands the win to the opponent, deployment included")
        void resignAwardsTheOpponent() {
            game.beginPlacement();

            game.resign(PlayerColor.RED);

            assertTrue(game.isOver());
            assertEquals(PlayerColor.BLUE, game.winner());
            assertEquals("RED left the game", game.winReason());
        }

        @Test
        @DisplayName("there is nothing to resign from before an opponent has joined")
        void resignNeedsAnOpponent() {
            assertThrows(IllegalStateException.class, () -> game.resign(PlayerColor.RED));

            assertFalse(game.isOver());
            assertNull(game.winner());
        }

        @Test
        @DisplayName("a finished game cannot be resigned a second time")
        void resignAfterTheEndIsRefused() {
            startWithFlags();
            game.resign(PlayerColor.BLUE);

            assertThrows(IllegalStateException.class, () -> game.resign(PlayerColor.RED));

            assertEquals(PlayerColor.RED, game.winner(), "the first resignation stands");
            assertEquals("BLUE left the game", game.winReason());
        }

        @Test
        @DisplayName("the flag escape pending when someone leaves is called off")
        void resignCancelsAPendingEscape() {
            startBare();
            game.seed(red(Rank.FLAG, 5, 0));
            game.seed(blue(Rank.FLAG, 7, 8));
            game.seed(blue(Rank.PRIVATE, 7, 7));

            game.move(PlayerColor.RED, at(5, 0), at(5, 1)); // flag escapes
            assertTrue(game.isFlagEscapePending());

            game.resign(PlayerColor.BLUE);

            assertFalse(game.isFlagEscapePending());
            assertEquals(PlayerColor.RED, game.winner());
        }
    }

    @Test
    @DisplayName("every turn is recorded in the move log")
    void historyIsRecorded() {
        startWithFlags();
        game.seed(red(Rank.COLONEL, 4, 4));
        game.seed(blue(Rank.PRIVATE, 7, 7));

        game.move(PlayerColor.RED, at(4, 4), at(3, 4));
        game.move(PlayerColor.BLUE, at(7, 7), at(6, 7));

        assertEquals(2, game.history().size());
        assertEquals(1, game.history().get(0).moveNumber());
        assertEquals(2, game.history().get(1).moveNumber());
    }

    @Test
    @DisplayName("moves are impossible before both sides have deployed")
    void cannotMoveBeforeDeployment() {
        game.beginPlacement();
        assertThrows(IllegalStateException.class,
                () -> game.move(PlayerColor.RED, at(0, 0), at(0, 1)));
    }
}
