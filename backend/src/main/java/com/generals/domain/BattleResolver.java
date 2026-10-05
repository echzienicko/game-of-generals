package com.generals.domain;

/**
 * The arbiter of every contest, applying the official Game of the Generals battle rules.
 *
 * <p>The rules, in the order they are applied:
 * <ol>
 *   <li>A <b>flag</b> that attacks any non-flag piece is lost.</li>
 *   <li>A <b>flag</b> that attacks the enemy flag captures it.</li>
 *   <li>Any <b>non-flag</b> piece that attacks a flag captures it.</li>
 *   <li>A <b>spy</b> that attacks a private is lost; a spy beats every other officer.</li>
 *   <li>Two <b>spies</b> eliminate each other.</li>
 *   <li>A <b>private</b> that attacks a spy wins; two privates eliminate each other.</li>
 *   <li>Otherwise the <b>higher rank</b> wins; equal ranks eliminate each other.</li>
 * </ol>
 */
public final class BattleResolver {

    public BattleResult resolve(Piece attacker, Piece defender) {
        if (attacker.owner() == defender.owner()) {
            throw new IllegalArgumentException("a piece cannot battle its own ally");
        }
        if (attacker.isFlag()) {
            return flagAttacks(attacker, defender);
        }
        if (defender.isFlag()) {
            return BattleResult.attackerWins(attacker, defender,
                    attacker.rank().displayName() + " captures the enemy flag");
        }
        if (attacker.isSpy()) {
            return spyAttacks(attacker, defender);
        }
        if (defender.isSpy()) {
            return officerAttacksSpy(attacker, defender);
        }
        return compareRanks(attacker, defender);
    }

    private BattleResult flagAttacks(Piece attacker, Piece defender) {
        if (defender.isFlag()) {
            return BattleResult.attackerWins(attacker, defender, "flag takes flag");
        }
        return BattleResult.defenderWins(attacker, defender,
                "the flag is lost — it may not attack a soldier");
    }

    private BattleResult spyAttacks(Piece attacker, Piece defender) {
        if (defender.isSpy()) {
            return BattleResult.mutualDestruction(attacker, defender, "two spies eliminate each other");
        }
        if (defender.isPrivate()) {
            return BattleResult.defenderWins(attacker, defender, "a private defeats a spy");
        }
        return BattleResult.attackerWins(attacker, defender,
                attacker.rank().displayName() + " outranks the private");
    }

    private BattleResult officerAttacksSpy(Piece attacker, Piece defender) {
        if (attacker.isPrivate()) {
            return BattleResult.attackerWins(attacker, defender, "a private defeats a spy");
        }
        return BattleResult.defenderWins(attacker, defender, "the spy outranks " + attacker.rank().displayName());
    }

    private BattleResult compareRanks(Piece attacker, Piece defender) {
        if (attacker.rank().power() > defender.rank().power()) {
            return BattleResult.attackerWins(attacker, defender, describe(attacker, defender));
        }
        if (attacker.rank().power() < defender.rank().power()) {
            return BattleResult.defenderWins(attacker, defender, describe(defender, attacker));
        }
        return BattleResult.mutualDestruction(attacker, defender,
                attacker.rank().displayName() + " and " + defender.rank().displayName() + " eliminate each other");
    }

    private String describe(Piece winner, Piece loser) {
        return winner.rank().displayName() + " defeats " + loser.rank().displayName();
    }
}
