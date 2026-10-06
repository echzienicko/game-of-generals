package com.generals.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.generals.api.GameViewMapper;
import com.generals.domain.BattleResolver;
import com.generals.domain.BotBrain;
import com.generals.domain.BotDifficulty;
import com.generals.domain.Board;
import com.generals.domain.Game;
import com.generals.domain.MoveRecord;
import com.generals.domain.MoveValidator;
import com.generals.domain.Piece;
import com.generals.domain.PlayerColor;
import com.generals.domain.Rank;
import com.generals.support.Armies;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;

import static com.generals.support.Armies.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The clock on a turn, and the move the server makes when it runs out.
 *
 * <p>The clock is a server thing on purpose: a browser cannot be trusted to notice its turn
 * ended, and a browser that is not open cannot notice anything, so a deadline in the client
 * would freeze the game for both players the moment one of them closed the tab. These tests
 * wait on real expiry with a one-second clock rather than pretending to, because what is
 * worth knowing is that the expiry task actually moves a piece, and that a move arriving
 * stops it.
 *
 * <p>Nothing here sleeps on a guess where it can help it: the expiry runs on its own thread,
 * so the tests poll for the thing they are waiting on. The one deliberate wait is in
 * {@link #aMoveInTimeIsNotReplaced}, where the assertion is about what did <em>not</em>
 * happen.
 */
class TurnClockTest {

    /** How long a poll waits before giving up on the clock having moved. */
    private static final long PATIENCE = 3_000L;

    private Leaderboard board;
    private ResultRecorder recorder;
    private GameBroadcaster broadcaster;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        board = new Leaderboard(new ObjectMapper(), dir.resolve("leaderboard.json").toString());
        recorder = new ResultRecorder(board);
        broadcaster = mock(GameBroadcaster.class);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A clock of {@code seconds} per move, shut down by the test that made it.
     *
     * <p>The brain picks uniformly among the legal moves, which is the point of the feature:
     * a timed-out move is a real move, not a good one. So a test that cares <em>which</em>
     * move comes out hands in its own {@link Random} rather than hoping.
     */
    private TurnClock clockFor(long seconds) {
        return clockFor(seconds, new Random());
    }

    private TurnClock clockFor(long seconds, Random random) {
        return new TurnClock(new BotBrain(new MoveValidator(), new BattleResolver(), random),
                recorder, broadcaster, seconds);
    }

    /**
     * Puts the player to move on {@code clock}, having first noted the moves they could make.
     *
     * <p>The list is taken before the clock is armed, because a piece that has moved is a
     * piece the move it made is no longer among: a test asserting the clock stayed inside the
     * rules has to compare against the board as it stood when the clock started.
     */
    private List<BotBrain.Move> arm(TurnClock clock, GameSession session) {
        List<BotBrain.Move> legal =
                new BotBrain().legalMoves(session.game(), session.game().currentPlayer());
        clock.onChange(session);
        return legal;
    }

    private void await(BooleanSupplier condition, String what) {
        long giveUpAt = System.currentTimeMillis() + PATIENCE;
        while (System.currentTimeMillis() < giveUpAt) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(20);
        }
        assertTrue(condition.getAsBoolean(), "timed out waiting for " + what);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private Leaderboard.Entry row(String name) {
        return board.top(100).stream()
                .filter(e -> e.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + name));
    }

    /**
     * A board where the only move worth discussing is taking the flag.
     *
     * <p>A real deployment cannot be won in one move — RED's camp is three rows from BLUE's —
     * so "a timed-out move can end the game" has to be staged. The board is emptied and rebuilt
     * through the public domain API, which is the only way to reach a position the deployment
     * phase would never produce.
     *
     * <p>RED's general stands at (3,1) with BLUE's flag directly above it at (2,1). Nothing
     * else on the board matters: {@link #NthMoveRandom} picks the general's capture out of the
     * legal moves by index, so the test is about the clock rather than about which move a
     * uniform random happens to land on.
     */
    private static GameSession flagOneMoveAway(String id, String redName, String blueName) {
        GameSession session = Armies.humanGame(id, redName, blueName);
        Board board = session.game().board();
        for (Piece piece : board.allPieces()) {
            board.removeAt(piece.position());
        }
        board.place(Piece.create(Rank.FIVE_STAR_GENERAL, PlayerColor.RED, at(3, 1)));
        board.place(Piece.create(Rank.FLAG, PlayerColor.BLUE, at(2, 1)));
        board.place(Piece.create(Rank.FLAG, PlayerColor.RED, at(0, 0)));
        return session;
    }

    /** Where the general's capture sits among the legal moves, so a test can ask for it. */
    private static int indexOfTheFlagCapture(GameSession session) {
        BotBrain.Move capture = new BotBrain.Move(at(3, 1), at(2, 1));
        List<BotBrain.Move> legal = new BotBrain().legalMoves(session.game(), PlayerColor.RED);
        int index = legal.indexOf(capture);
        assertTrue(index >= 0, "the general can take the flag, so it is a legal move");
        return index;
    }

    /**
     * A clock that always plays one particular legal move.
     *
     * <p>The timed-out move is a uniform choice by design, so a test that needs a <em>specific</em>
     * move takes it by index rather than arranging a board where it is the only one — a board
     * with a single legal move in it is not a position this game can reach, and pretending
     * otherwise would test the fixture instead of the clock.
     */
    private static final class NthMoveRandom extends Random {

        private final int index;

        private NthMoveRandom(int index) {
            this.index = index;
        }

        @Override
        public int nextInt(int bound) {
            return index % bound;
        }
    }

    // ------------------------------------------------------------------ the clock

    @Test
    @DisplayName("a clock that runs out plays a legal move for whoever let it expire")
    void expiryPlaysAMove() {
        TurnClock clock = clockFor(1);
        try {
            GameSession session = Armies.humanGame("clock-expiry", "ClockRed", "ClockBlue");
            List<BotBrain.Move> legal = arm(clock, session);

            await(() -> !session.game().history().isEmpty(), "the clock to move");

            MoveRecord played = session.game().history().get(0);
            assertTrue(played.byClock(), "the move should be marked as the clock's");
            assertEquals(PlayerColor.RED, played.player(), "RED moved first and let it expire");
            assertTrue(legal.contains(new BotBrain.Move(played.from(), played.to())),
                    "the clock played " + played.from().label() + " -> " + played.to().label()
                            + ", which is not one of the " + legal.size() + " legal moves");
            assertEquals(PlayerColor.BLUE, session.game().currentPlayer(),
                    "the move handed the turn on, so BLUE is next");
            verify(broadcaster).broadcast(session);
        } finally {
            clock.shutdown();
        }
    }

    @Test
    @DisplayName("the log tells both players that the move was made by the clock")
    void expiryIsInTheLogForBothPlayers() {
        TurnClock clock = clockFor(1);
        try {
            GameSession session = Armies.humanGame("clock-log", "ClockRed", "ClockBlue");
            arm(clock, session);

            await(() -> !session.game().history().isEmpty(), "the clock to move");

            String asRed = String.join("\n", session.viewFor(PlayerColor.RED).log());
            String asBlue = String.join("\n", session.viewFor(PlayerColor.BLUE).log());
            assertTrue(asRed.contains("ran out of time"), "RED's log said: " + asRed);
            assertTrue(asBlue.contains("ran out of time"), "BLUE's log said: " + asBlue);
        } finally {
            clock.shutdown();
        }
    }

    @Test
    @DisplayName("the move you make in time is not taken back by a clock")
    void aMoveInTimeIsNotReplaced() {
        TurnClock clock = clockFor(1);
        try {
            GameSession session = Armies.humanGame("clock-in-time", "QuickRed", "WaitingBlue");
            arm(clock, session);
            session.game().move(PlayerColor.RED, at(2, 2), at(3, 2));
            arm(clock, session);

            await(() -> session.game().history().size() >= 2, "BLUE's clock to move");
            sleep(250);

            List<MoveRecord> history = session.game().history();
            assertEquals(2, history.size(), "RED's own move and one clock move, nothing else");
            assertFalse(history.get(0).byClock(), "RED moved in time, so that move is not a clock's");
            assertEquals(PlayerColor.BLUE, history.get(1).player());
            assertTrue(history.get(1).byClock(), "BLUE never moved, so the clock moved for them");
        } finally {
            clock.shutdown();
        }
    }

    @Test
    @DisplayName("an expiry that wakes up after the turn has moved on leaves the game alone")
    void staleExpiryIsTurnedAway() {
        TurnClock clock = clockFor(1);
        try {
            GameSession session = Armies.humanGame("clock-stale", "QuickRed", "WaitingBlue");
            arm(clock, session);
            session.game().move(PlayerColor.RED, at(2, 2), at(3, 2));
            arm(clock, session);

            clock.fire(session, PlayerColor.RED, System.currentTimeMillis() - 1);

            assertEquals(1, session.game().history().size(),
                    "RED's old task ran with a deadline already past; it must not move RED again");
            assertEquals(PlayerColor.BLUE, session.game().currentPlayer());
        } finally {
            clock.shutdown();
        }
    }

    @Test
    @DisplayName("with the clock off, nothing moves itself and no deadline is sent")
    void nothingMovesItselfWhenTheClockIsOff() {
        TurnClock off = clockFor(0);
        try {
            GameSession session = Armies.humanGame("clock-off", "Unhurried", "UnhurriedTwo");
            List<BotBrain.Move> legal = arm(off, session);

            assertFalse(off.isEnabled());
            assertFalse(session.isClockRunning());
            assertNull(session.viewFor(PlayerColor.RED).turnDeadlineMillis());
            assertEquals(0, session.viewFor(PlayerColor.RED).turnSeconds());
            assertTrue(legal.size() > 1, "there were moves to play, so there is something to spoil");

            sleep(300);
            assertTrue(session.game().history().isEmpty(), "an untimed game waits for a person");
        } finally {
            off.shutdown();
        }
    }

    @Test
    @DisplayName("the computer is never put on a clock")
    void theBotIsNotTimed() {
        TurnClock clock = clockFor(1);
        try {
            GameSession session = Armies.botGame("clock-bot", "Human", BotDifficulty.RANDOM);
            clock.onChange(session);

            assertEquals(PlayerColor.RED, session.game().currentPlayer());
            assertNotNull(session.viewFor(PlayerColor.RED).turnDeadlineMillis(),
                    "the person on the move is timed");

            session.game().move(PlayerColor.RED, at(2, 2), at(3, 2));
            clock.onChange(session);

            assertEquals(PlayerColor.BLUE, session.game().currentPlayer());
            assertFalse(session.isClockRunning());
            assertNull(session.viewFor(PlayerColor.RED).turnDeadlineMillis(),
                    "a countdown to nothing while the computer thinks is worse than none");
        } finally {
            clock.shutdown();
        }
    }

    @Test
    @DisplayName("the view carries the deadline and the length of the turn")
    void theViewCarriesTheClock() {
        TurnClock clock = clockFor(60);
        try {
            GameSession session = Armies.humanGame("clock-view", "ClockRed", "ClockBlue");
            long before = System.currentTimeMillis();
            clock.onChange(session);
            long after = System.currentTimeMillis();

            assertEquals(60, session.viewFor(PlayerColor.RED).turnSeconds());
            Long deadline = session.viewFor(PlayerColor.RED).turnDeadlineMillis();
            assertNotNull(deadline);
            assertTrue(deadline >= before + 60_000, "a deadline of " + deadline + " for " + before);
            assertTrue(deadline <= after + 60_000, "a deadline of " + deadline + " for " + after);
        } finally {
            clock.shutdown();
        }
    }

    @Test
    @DisplayName("no clock is sent before the game is live, or after it is over")
    void noClockOutsidePlay() {
        // a minute per move, so the clock armed mid-test cannot expire during it
        TurnClock clock = clockFor(60);
        try {
            GameSession session = new GameSession(new Game("clock-phases"), new GameViewMapper());
            session.seatPlayer(PlayerColor.RED, "Early");
            session.seatPlayer(PlayerColor.BLUE, "Later");
            clock.onChange(session);
            assertNull(session.viewFor(PlayerColor.RED).turnDeadlineMillis(),
                    "nobody has the move until both sides have deployed");

            Armies.deploy(session, Armies.redAttacking(), Armies.blueDefending());
            clock.onChange(session);
            assertNotNull(session.viewFor(PlayerColor.RED).turnDeadlineMillis());

            session.game().move(PlayerColor.RED, at(2, 2), at(3, 2));
            session.game().move(PlayerColor.BLUE, at(5, 0), at(4, 0));
            session.game().move(PlayerColor.RED, at(3, 2), at(4, 2));
            session.game().move(PlayerColor.BLUE, at(4, 0), at(5, 0));
            session.game().move(PlayerColor.RED, at(4, 2), at(5, 2));
            assertTrue(session.game().isOver(), "RED took the flag");
            // whoever noticed the end files it; the clock then finds nothing to time
            recorder.fileFinished(session);
            assertTrue(session.resultFiled());

            clock.onChange(session);
            assertNull(session.viewFor(PlayerColor.RED).turnDeadlineMillis(), "the game is over");
        } finally {
            clock.shutdown();
        }
    }

    // ------------------------------------------------------------------ the ledger

    @Test
    @DisplayName("a timed-out move that ends the game is scored, like any other win")
    void aClockWinIsRecorded() {
        GameSession session = flagOneMoveAway("clock-win", "TimedRed", "WaitingBlue");
        TurnClock clock = clockFor(1, new NthMoveRandom(indexOfTheFlagCapture(session)));
        try {
            clock.onChange(session);

            await(() -> session.game().isOver(), "the clock to take the flag");

            assertEquals(PlayerColor.RED, session.game().winner());
            assertTrue(session.resultFiled(), "the clock is a third observer of the end, and files it");
            assertEquals(1, row("TimedRed").wins());
            assertEquals(1, row("WaitingBlue").losses());
        } finally {
            clock.shutdown();
        }
    }

    @Test
    @DisplayName("a late expiry cannot score the same game a second time")
    void aLateExpiryIsNotScoredTwice() {
        GameSession session = flagOneMoveAway("clock-twice", "TimedRed", "WaitingBlue");
        TurnClock clock = clockFor(1, new NthMoveRandom(indexOfTheFlagCapture(session)));
        try {
            clock.onChange(session);
            await(() -> session.game().isOver(), "the clock to take the flag");

            clock.fire(session, PlayerColor.RED, System.currentTimeMillis() - 1);

            assertEquals(1, row("TimedRed").wins(), "one win, however many expiries arrive");
            assertEquals(1, row("WaitingBlue").losses());
        } finally {
            clock.shutdown();
        }
    }
}