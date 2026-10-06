package com.generals.support;

import com.generals.api.GameViewMapper;
import com.generals.domain.ArmyFactory;
import com.generals.domain.BotDifficulty;
import com.generals.domain.Board;
import com.generals.domain.Game;
import com.generals.domain.PlayerColor;
import com.generals.domain.Position;
import com.generals.domain.Rank;
import com.generals.service.GameSession;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Deployments that pass the server's own placement checks, shared by the tests that need a
 * real board.
 *
 * <p>Two things about this are easy to get wrong and impossible to notice until a test
 * fails somewhere else: the camp has 27 squares and an army 21, so a deployment has to be
 * sliced to the army rather than filling the camp (fill the camp and the placement is
 * rejected, or the ranks written past the roster); and the server checks the composition, so
 * a piece named in a scenario has to displace its original rather than be added to the
 * roster, which is what {@link #army} does with the swap.
 */
public final class Armies {

    private Armies() {
    }

    public static Position at(int row, int col) {
        return new Position(row, col);
    }

    /**
     * The standard roster on the first {@link ArmyFactory#ARMY_SIZE} squares of the camp,
     * with the named pieces swapped into place.
     */
    public static Map<Position, Rank> army(PlayerColor color, Map<Position, Rank> named) {
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
    public static Map<Position, Rank> redAttacking() {
        return army(PlayerColor.RED, Map.of(
                at(2, 2), Rank.FIVE_STAR_GENERAL,
                at(2, 0), Rank.FLAG));
    }

    /** RED's flag pushed forward and a private in front of it. */
    public static Map<Position, Rank> redDefending() {
        return army(PlayerColor.RED, Map.of(
                at(2, 2), Rank.FLAG,
                at(2, 0), Rank.PRIVATE));
    }

    /** BLUE's general on the edge of its camp, three squares from RED's flag. */
    public static Map<Position, Rank> blueAttacking() {
        return army(PlayerColor.BLUE, Map.of(
                at(5, 2), Rank.FIVE_STAR_GENERAL,
                at(5, 0), Rank.FLAG));
    }

    /** BLUE's flag pushed forward and a private in front of it. */
    public static Map<Position, Rank> blueDefending() {
        return army(PlayerColor.BLUE, Map.of(
                at(5, 2), Rank.FLAG,
                at(5, 0), Rank.PRIVATE));
    }

    /**
     * A deployed game with a person on each side, waiting for RED's first move.
     *
     * <p>Straight through the domain object rather than through the manager, because a clock
     * test wants to arm and fire the clock itself and the manager would be arming it too.
     */
    public static GameSession humanGame(String id, String redName, String blueName) {
        GameSession session = new GameSession(new Game(id), new GameViewMapper());
        session.seatPlayer(PlayerColor.RED, redName);
        session.seatPlayer(PlayerColor.BLUE, blueName);
        deploy(session, redAttacking(), blueDefending());
        return session;
    }

    /** The same, with the computer on BLUE. */
    public static GameSession botGame(String id, String humanName, BotDifficulty difficulty) {
        GameSession session = new GameSession(new Game(id), new GameViewMapper());
        session.seatPlayer(PlayerColor.RED, humanName);
        session.seatBot(PlayerColor.BLUE, difficulty);
        deploy(session, redAttacking(), blueDefending());
        return session;
    }

    /** Runs the deployment phase through to RED on the clock. */
    public static void deploy(GameSession session, Map<Position, Rank> red, Map<Position, Rank> blue) {
        session.game().beginPlacement();
        session.game().submitPlacement(PlayerColor.RED, red);
        session.game().submitPlacement(PlayerColor.BLUE, blue);
    }
}