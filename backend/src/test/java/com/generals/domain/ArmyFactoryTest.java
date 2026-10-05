package com.generals.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArmyFactoryTest {

    @Test
    @DisplayName("an army is exactly 21 pieces")
    void armySize() {
        assertEquals(21, ArmyFactory.standardRoster().size());
        assertEquals(21, ArmyFactory.newArmy(PlayerColor.RED).size());
    }

    @Test
    @DisplayName("the roster matches the official composition")
    void rosterComposition() {
        Map<Rank, Long> counts = ArmyFactory.standardRoster().stream()
                .collect(Collectors.groupingBy(r -> r, Collectors.counting()));

        assertEquals(1L, counts.get(Rank.FIVE_STAR_GENERAL));
        assertEquals(1L, counts.get(Rank.FOUR_STAR_GENERAL));
        assertEquals(1L, counts.get(Rank.THREE_STAR_GENERAL));
        assertEquals(1L, counts.get(Rank.TWO_STAR_GENERAL));
        assertEquals(1L, counts.get(Rank.ONE_STAR_GENERAL));
        assertEquals(1L, counts.get(Rank.COLONEL));
        assertEquals(1L, counts.get(Rank.LIEUTENANT_COLONEL));
        assertEquals(1L, counts.get(Rank.MAJOR));
        assertEquals(1L, counts.get(Rank.CAPTAIN));
        assertEquals(1L, counts.get(Rank.FIRST_LIEUTENANT));
        assertEquals(1L, counts.get(Rank.SECOND_LIEUTENANT));
        assertEquals(1L, counts.get(Rank.SERGEANT));
        assertEquals(6L, counts.get(Rank.PRIVATE));
        assertEquals(2L, counts.get(Rank.SPY));
        assertEquals(1L, counts.get(Rank.FLAG));
    }

    @Test
    @DisplayName("all 15 distinct ranks appear in the army")
    void everyRankIsRepresented() {
        assertEquals(Rank.values().length, ArmyFactory.standardRoster().stream().distinct().count());
    }

    @Test
    @DisplayName("newly created pieces start face-down")
    void newArmyIsHidden() {
        assertTrue(ArmyFactory.newArmy(PlayerColor.BLUE).stream().noneMatch(Piece::isRevealed));
    }
}
