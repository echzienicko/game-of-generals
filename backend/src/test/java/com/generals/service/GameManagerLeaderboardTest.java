package com.generals.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.generals.api.GameViewMapper;
import com.generals.domain.BotBrain;
import com.generals.domain.ArmyFactory;
import com.generals.domain.BotDifficulty;
import com.generals.domain.Board;
import com.generals.domain.Game;
import com.generals.domain.OpeningMemory;
import com.generals.domain.PlayerColor;
import com.generals.domain.Position;
import com.generals.domain.Rank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The leaderboard as seen from the seat that actually plays the game.
 *
 * <p>These play whole games rather than poking a finished one, because the part worth
 * testing is the wiring: a real win arriving through {@link GameManager#move}, and the
 * exactly-once guarantee that keeps it from being counted twice by the two observers that
 * both see a game end.
 */
class GameManagerLeaderboardTest {

    private Leaderboard board;
    private ResultRecorder recorder;
    private GameManager games;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        board = new Leaderboard(new ObjectMapper(), dir.resolve("leaderboard.json").toString());
        recorder = new ResultRecorder(board);
        // no bot game is started through the manager here, so the broadcaster is never used
        BotOpponent bot = new BotOpponent(null, new BotBrain(), OpeningMemory.none(), recorder, 10);
        games = new GameManager(new GameViewMapper(), bot, recorder);
    }

    // ------------------------------------------------------------------ helpers

    private static Position at(int row, int col) {
        return new Position(row, col);
    }

    /**
     * A legal deployment: the standard roster on the first {@link ArmyFactory#ARMY_SIZE}
     * squares of the camp, with the named pieces swapped into place.
     *
     * <p>Two things to get right here. The camp has 27 squares and an army 21, so the
     * deployment has to be sliced to the army rather than filling the camp. And the server
     * checks the composition, so the named pieces have to displace their originals rather
     * than be added to the roster.
     */
    private static Map<Position, Rank> army(PlayerColor color, Map<Position, Rank> named) {
        Map<Position, Rank> map = new LinkedHashMap<>();
        List<Position> zone = Board.deploymentZone(color);
        List<Rank> roster = ArmyFactory.standardRoster();
        for (int i = 0; i < ArmyFactory.ARMY_SIZE; i++) {
            map.put(zone.get(i), roster.get(i));
        }
        for (Map.Entry<Position, Rank> entry : named.entrySet()) {
            Rank displaced = map.get(entry.getKey());
            assertNotNull(displaced, entry.getKey() + " is not in the deployment zone");
            if (displaced != entry.getValue()) {
                Position swap = firstHolding(map, entry.getValue());
                map.put(swap, displaced);
            }
            map.put(entry.getKey(), entry.getValue());
        }
        assertEquals(ArmyFactory.ARMY_SIZE, map.size(), "an army, not a camp");
        return map;
    }

    private static Position firstHolding(Map<Position, Rank> army, Rank rank) {
        return army.entrySet().stream()
                .filter(e -> e.getValue() == rank)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + rank + " to swap with"));
    }

    /** RED's general on the edge of its camp, three squares from BLUE's flag. */
    private static Map<Position, Rank> redAttacking() {
        return army(PlayerColor.RED, Map.of(
                at(2, 2), Rank.FIVE_STAR_GENERAL,
                at(2, 0), Rank.FLAG));
    }

    private static Map<Position, Rank> redDefending() {
        return army(PlayerColor.RED, Map.of(
                at(2, 2), Rank.FLAG,
                at(2, 0), Rank.PRIVATE));
    }

    private static Map<Position, Rank> blueDefending() {
        return army(PlayerColor.BLUE, Map.of(
                at(5, 2), Rank.FLAG,
                at(5, 0), Rank.PRIVATE));
    }

    private static Map<Position, Rank> blueAttacking() {
        return army(PlayerColor.BLUE, Map.of(
                at(5, 2), Rank.FIVE_STAR_GENERAL,
                at(5, 0), Rank.FLAG));
    }

    /** RED's three-move capture; BLUE walks a private back and forth to lose a tempo. */
    private static void redTakesTheFlag(Game game) {
        game.move(PlayerColor.RED, at(2, 2), at(3, 2));
        game.move(PlayerColor.BLUE, at(5, 0), at(4, 0));
        game.move(PlayerColor.RED, at(3, 2), at(4, 2));
        game.move(PlayerColor.BLUE, at(4, 0), at(5, 0));
        game.move(PlayerColor.RED, at(4, 2), at(5, 2));
    }

    /** The same capture the other way round; RED moves first, so RED shuffles first. */
    private static void blueTakesTheFlag(Game game) {
        game.move(PlayerColor.RED, at(2, 0), at(3, 0));
        game.move(PlayerColor.BLUE, at(5, 2), at(4, 2));
        game.move(PlayerColor.RED, at(3, 0), at(2, 0));
        game.move(PlayerColor.BLUE, at(4, 2), at(3, 2));
        game.move(PlayerColor.RED, at(2, 0), at(3, 0));
        game.move(PlayerColor.BLUE, at(3, 2), at(2, 2));
    }

    /**
     * The same capture, played the way it happens for real: through the manager, one move at
     * a time, so the result is filed by the human-side path rather than by hand.
     */
    private void redWinsOverHttp(GameManager.JoinResult red, GameManager.JoinResult blue) {
        games.move(red.gameId(), red.token(), at(2, 2), at(3, 2));
        games.move(blue.gameId(), blue.token(), at(5, 0), at(4, 0));
        games.move(red.gameId(), red.token(), at(3, 2), at(4, 2));
        games.move(blue.gameId(), blue.token(), at(4, 0), at(5, 0));
        games.move(red.gameId(), red.token(), at(4, 2), at(5, 2));
    }

    /** Deploys both sides and plays out a RED win, over HTTP. */
    private GameManager.JoinResult wonByRed(String redName, String blueName) {
        GameManager.JoinResult red = games.create(redName);
        GameManager.JoinResult blue = games.join(red.gameId(), blueName);
        games.submitPlacement(red.gameId(), red.token(), redAttacking());
        games.submitPlacement(blue.gameId(), blue.token(), blueDefending());
        redWinsOverHttp(red, blue);
        return red;
    }

    /** A session with a human on RED and the computer on BLUE, deployed and ready. */
    private GameSession botSession(String gameId, String humanName) {
        GameSession session = new GameSession(new Game(gameId), new GameViewMapper());
        session.seatPlayer(PlayerColor.RED, humanName);
        session.seatBot(PlayerColor.BLUE, BotDifficulty.HEURISTIC);
        session.game().beginPlacement();
        session.game().submitPlacement(PlayerColor.RED, redAttacking());
        session.game().submitPlacement(PlayerColor.BLUE, blueDefending());
        return session;
    }

    private Leaderboard.Entry row(String name) {
        return board.top(100).stream()
                .filter(e -> e.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + name + " in " + board.top(100)));
    }

    // ------------------------------------------------------------------ seats

    @Test
    @DisplayName("both seats remember the names they were given")
    void seatsAreNamed() {
        GameManager.JoinResult red = games.create("  Nick  ");
        GameManager.JoinResult blue = games.join(red.gameId(), "Alex");

        GameSession session = games.require(red.gameId());
        assertEquals("Nick", session.nameOf(PlayerColor.RED), "tidied on the way in");
        assertEquals("Alex", session.nameOf(PlayerColor.BLUE));
        assertEquals(red.token(), session.tokenOf(PlayerColor.RED));
        assertEquals(blue.token(), session.tokenOf(PlayerColor.BLUE));
    }

    @Test
    @DisplayName("the computer's seat has no name, which is what keeps it off the table")
    void botSeatHasNoName() {
        GameManager.JoinResult red = games.createAgainstBot(BotDifficulty.HEURISTIC, "Nick");

        GameSession session = games.require(red.gameId());
        assertEquals("Nick", session.nameOf(PlayerColor.RED));
        assertNull(session.nameOf(PlayerColor.BLUE));
        assertEquals(PlayerColor.BLUE, session.botColor());
    }

    @Test
    @DisplayName("matchmake names both sides of the pair it makes")
    void matchmakeNamesBothSides() {
        games.create("Nick");
        GameManager.MatchResult match = games.matchmake("Alex");

        assertEquals("Nick", games.require(match.gameId()).nameOf(PlayerColor.RED));
        assertEquals("Alex", games.require(match.gameId()).nameOf(PlayerColor.BLUE));
    }

    @Test
    @DisplayName("no game can be seated without a name, so no win can be lost silently")
    void nameIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> games.create("  "));
        assertThrows(IllegalArgumentException.class, () -> games.create(null));
        assertThrows(IllegalArgumentException.class,
                () -> games.create("x".repeat(Leaderboard.MAX_NAME_LENGTH + 1)));
        assertEquals(0, board.size());
    }

    @Test
    @DisplayName("a name is required to join a game too")
    void nameIsRequiredToJoin() {
        GameManager.JoinResult red = games.create("Nick");
        assertThrows(IllegalArgumentException.class, () -> games.join(red.gameId(), ""));
        assertThrows(IllegalArgumentException.class, () -> games.matchmake("  "));
    }

    // ------------------------------------------------------------------ filing

    @Test
    @DisplayName("a game won over HTTP is on the record, with the loser's loss beside it")
    void winIsRecorded() {
        GameManager.JoinResult red = wonByRed("Nick", "Alex");

        GameSession session = games.require(red.gameId());
        assertTrue(session.game().isOver());
        assertEquals(PlayerColor.RED, session.game().winner());
        assertTrue(session.resultFiled());
        assertEquals(1, row("Nick").wins());
        assertEquals(0, row("Nick").losses());
        assertEquals(1, row("Nick").streak());
        assertEquals(1, row("Alex").losses());
        assertEquals(0, row("Alex").wins());
        assertEquals(2, board.size());
    }

    @Test
    @DisplayName("an unfinished game records nothing")
    void unfinishedGameRecordsNothing() {
        GameManager.JoinResult red = games.create("Nick");
        GameManager.JoinResult blue = games.join(red.gameId(), "Alex");
        games.submitPlacement(red.gameId(), red.token(), redAttacking());
        games.submitPlacement(blue.gameId(), blue.token(), blueDefending());

        games.move(red.gameId(), red.token(), at(2, 2), at(3, 2));

        assertFalse(games.require(red.gameId()).game().isOver());
        assertFalse(games.require(red.gameId()).resultFiled());
        assertEquals(0, board.size());
    }

    @Test
    @DisplayName("the engine refuses a move after the end, so a second win is not possible")
    void moveAfterTheEndIsRefused() {
        GameManager.JoinResult red = games.create("Nick");
        GameManager.JoinResult blue = games.join(red.gameId(), "Alex");
        games.submitPlacement(red.gameId(), red.token(), redAttacking());
        games.submitPlacement(blue.gameId(), blue.token(), blueDefending());
        redWinsOverHttp(red, blue);
        assertEquals(1, row("Nick").wins());

        assertThrows(RuntimeException.class,
                () -> games.move(red.gameId(), red.token(), at(2, 0), at(2, 1)));

        assertEquals(1, row("Nick").wins());
        assertEquals(2, board.size());
    }

    @Test
    @DisplayName("an observer that arrives after the end is turned away")
    void lateObserverDoesNotDoubleCount() {
        GameManager.JoinResult red = games.create("Nick");
        GameManager.JoinResult blue = games.join(red.gameId(), "Alex");
        games.submitPlacement(red.gameId(), red.token(), redAttacking());
        games.submitPlacement(blue.gameId(), blue.token(), blueDefending());
        redWinsOverHttp(red, blue);
        assertEquals(1, row("Nick").wins());

        // the computer's turn arriving late, and any later push that re-reads the end
        recorder.fileFinished(games.require(red.gameId()));
        recorder.fileFinished(games.require(red.gameId()));
        recorder.fileIfFinished(games.require(red.gameId()), false);

        assertEquals(1, row("Nick").wins());
        assertEquals(2, board.size());
    }

    @Test
    @DisplayName("a game the observer already knew to be over is not scored")
    void priorStatusOfOverSuppressesFiling() {
        GameManager.JoinResult red = games.create("Nick");
        GameManager.JoinResult blue = games.join(red.gameId(), "Alex");
        games.submitPlacement(red.gameId(), red.token(), redAttacking());
        games.submitPlacement(blue.gameId(), blue.token(), blueDefending());
        GameSession session = games.require(red.gameId());
        redTakesTheFlag(session.game());

        // a second session with a game that ended before the mutation that led here
        GameSession late = new GameSession(new Game("late"), new GameViewMapper());
        late.seatPlayer(PlayerColor.RED, "Nick");
        late.game().beginPlacement();
        late.game().submitPlacement(PlayerColor.RED, redAttacking());
        late.game().submitPlacement(PlayerColor.BLUE, blueDefending());
        redTakesTheFlag(late.game());

        recorder.fileIfFinished(late, true);

        assertFalse(late.resultFiled());
        assertEquals(0, board.size());
    }

    @Test
    @DisplayName("winning against the computer counts for the human and files no computer row")
    void beatingTheBotCountsForTheHuman() {
        GameSession session = botSession("bot-win", "Nick");

        redTakesTheFlag(session.game());
        recorder.fileFinished(session);

        assertEquals(1, board.size());
        assertEquals(1, row("Nick").wins());
        assertNull(session.nameOf(PlayerColor.BLUE), "the computer is not a player to name");
    }

    @Test
    @DisplayName("losing to the computer is still a loss on the record")
    void losingToTheBotCountsAsALoss() {
        GameSession session = new GameSession(new Game("bot-loss"), new GameViewMapper());
        session.seatPlayer(PlayerColor.RED, "Nick");
        session.seatBot(PlayerColor.BLUE, BotDifficulty.HEURISTIC);
        session.game().beginPlacement();
        session.game().submitPlacement(PlayerColor.RED, redDefending());
        session.game().submitPlacement(PlayerColor.BLUE, blueAttacking());

        blueTakesTheFlag(session.game());
        recorder.fileFinished(session);

        assertEquals(1, board.size());
        assertEquals(1, row("Nick").losses());
        assertEquals(0, row("Nick").wins());
        assertEquals(0, row("Nick").streak());
    }
}
