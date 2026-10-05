package com.generals.domain;

/**
 * The outcome of a single contest between an attacking piece and a defending piece.
 *
 * <p>When the attacker survives it takes the contested square, otherwise the defender
 * keeps its square.
 */
public record BattleResult(
        Piece attacker,
        Piece defender,
        boolean attackerSurvives,
        boolean defenderSurvives,
        String description) {

    public static BattleResult attackerWins(Piece attacker, Piece defender, String description) {
        return new BattleResult(attacker, defender, true, false, description);
    }

    public static BattleResult defenderWins(Piece attacker, Piece defender, String description) {
        return new BattleResult(attacker, defender, false, true, description);
    }

    public static BattleResult mutualDestruction(Piece attacker, Piece defender, String description) {
        return new BattleResult(attacker, defender, false, false, description);
    }

    /** The single piece left standing, or {@code null} when both were destroyed. */
    public Piece survivor() {
        if (attackerSurvives) {
            return attacker;
        }
        return defenderSurvives ? defender : null;
    }
}
