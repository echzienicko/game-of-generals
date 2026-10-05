package com.generals.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattleResolverTest {

    private final BattleResolver resolver = new BattleResolver();

    private Piece red(Rank rank) {
        return Piece.create(rank, PlayerColor.RED, new Position(3, 4));
    }

    private Piece blue(Rank rank) {
        return Piece.create(rank, PlayerColor.BLUE, new Position(4, 4));
    }

    private void assertAttackerWins(Piece attacker, Piece defender) {
        BattleResult result = resolver.resolve(attacker, defender);
        assertTrue(result.attackerSurvives(), attacker.rank() + " should beat " + defender.rank());
        assertFalse(result.defenderSurvives());
        assertSame(attacker, result.survivor());
    }

    private void assertDefenderWins(Piece attacker, Piece defender) {
        BattleResult result = resolver.resolve(attacker, defender);
        assertFalse(result.attackerSurvives(), attacker.rank() + " should lose to " + defender.rank());
        assertTrue(result.defenderSurvives());
        assertSame(defender, result.survivor());
    }

    private void assertBothDie(Piece attacker, Piece defender) {
        BattleResult result = resolver.resolve(attacker, defender);
        assertFalse(result.attackerSurvives());
        assertFalse(result.defenderSurvives());
        assertSame(null, result.survivor());
    }

    @Nested
    @DisplayName("officer vs officer")
    class OfficerVersusOfficer {

        @Test
        @DisplayName("higher rank wins and advances")
        void higherRankWins() {
            assertAttackerWins(red(Rank.FIVE_STAR_GENERAL), blue(Rank.FOUR_STAR_GENERAL));
            assertAttackerWins(red(Rank.COLONEL), blue(Rank.SERGEANT));
            assertDefenderWins(red(Rank.SECOND_LIEUTENANT), blue(Rank.CAPTAIN));
        }

        @Test
        @DisplayName("identical ranks eliminate each other")
        void equalRanksMutuallyDestroy() {
            assertBothDie(red(Rank.COLONEL), blue(Rank.COLONEL));
            assertBothDie(red(Rank.FIVE_STAR_GENERAL), blue(Rank.FIVE_STAR_GENERAL));
            assertBothDie(red(Rank.SERGEANT), blue(Rank.SERGEANT));
        }

        @Test
        @DisplayName("every officer pair resolves by rank order, from either side")
        void fullOfficerLadder() {
            Rank[] officers = {
                    Rank.FIVE_STAR_GENERAL, Rank.FOUR_STAR_GENERAL, Rank.THREE_STAR_GENERAL,
                    Rank.TWO_STAR_GENERAL, Rank.ONE_STAR_GENERAL, Rank.COLONEL,
                    Rank.LIEUTENANT_COLONEL, Rank.MAJOR, Rank.CAPTAIN,
                    Rank.FIRST_LIEUTENANT, Rank.SECOND_LIEUTENANT, Rank.SERGEANT
            };
            for (Rank a : officers) {
                for (Rank b : officers) {
                    if (a.power() > b.power()) {
                        // red(a) attacks blue(b): the stronger rank wins
                        assertAttackerWins(red(a), blue(b));
                        // red(b) attacks blue(a): the stronger rank defends and wins
                        assertDefenderWins(red(b), blue(a));
                    } else if (a.power() < b.power()) {
                        assertDefenderWins(red(a), blue(b));
                        assertAttackerWins(red(b), blue(a));
                    } else {
                        assertBothDie(red(a), blue(b));
                        assertBothDie(red(b), blue(a));
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("spy rules")
    class SpyRules {

        @Test
        @DisplayName("a spy defeats every officer")
        void spyBeatsOfficers() {
            for (Rank officer : new Rank[] { Rank.FIVE_STAR_GENERAL, Rank.FOUR_STAR_GENERAL,
                    Rank.THREE_STAR_GENERAL, Rank.TWO_STAR_GENERAL, Rank.ONE_STAR_GENERAL,
                    Rank.COLONEL, Rank.LIEUTENANT_COLONEL, Rank.MAJOR, Rank.CAPTAIN,
                    Rank.FIRST_LIEUTENANT, Rank.SECOND_LIEUTENANT, Rank.SERGEANT }) {
                assertAttackerWins(red(Rank.SPY), blue(officer));
            }
        }

        @Test
        @DisplayName("a spy loses to a private when attacking")
        void spyLosesToPrivateWhenAttacking() {
            assertDefenderWins(red(Rank.SPY), blue(Rank.PRIVATE));
        }

        @Test
        @DisplayName("a private defeats a spy when attacking")
        void privateBeatsSpyWhenAttacking() {
            assertAttackerWins(red(Rank.PRIVATE), blue(Rank.SPY));
        }

        @Test
        @DisplayName("two spies eliminate each other")
        void spyVersusSpy() {
            assertBothDie(red(Rank.SPY), blue(Rank.SPY));
        }
    }

    @Nested
    @DisplayName("private rules")
    class PrivateRules {

        @Test
        @DisplayName("two privates eliminate each other")
        void privateVersusPrivate() {
            assertBothDie(red(Rank.PRIVATE), blue(Rank.PRIVATE));
        }

        @Test
        @DisplayName("an officer always beats a private, attacking or defending")
        void officerBeatsPrivate() {
            for (Rank officer : new Rank[] { Rank.FIVE_STAR_GENERAL, Rank.COLONEL, Rank.SERGEANT }) {
                assertAttackerWins(red(officer), blue(Rank.PRIVATE));
                // the private attacks: the officer defends and wins
                assertDefenderWins(red(Rank.PRIVATE), blue(officer));
            }
        }
    }

    @Nested
    @DisplayName("flag rules")
    class FlagRules {

        @Test
        @DisplayName("any non-flag piece captures a flag")
        void nonFlagCapturesFlag() {
            assertAttackerWins(red(Rank.PRIVATE), blue(Rank.FLAG));
            assertAttackerWins(red(Rank.SPY), blue(Rank.FLAG));
            assertAttackerWins(red(Rank.FIVE_STAR_GENERAL), blue(Rank.FLAG));
        }

        @Test
        @DisplayName("a flag that attacks a soldier is lost")
        void flagAttackingSoldierIsLost() {
            assertDefenderWins(red(Rank.FLAG), blue(Rank.PRIVATE));
            assertDefenderWins(red(Rank.FLAG), blue(Rank.FIVE_STAR_GENERAL));
            assertDefenderWins(red(Rank.FLAG), blue(Rank.SPY));
        }

        @Test
        @DisplayName("a flag that attacks the enemy flag wins")
        void flagTakesFlag() {
            assertAttackerWins(red(Rank.FLAG), blue(Rank.FLAG));
        }
    }

    @Test
    @DisplayName("a piece may not battle its own ally")
    void alliesCannotBattle() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(red(Rank.COLONEL), red(Rank.SERGEANT)));
    }
}
