package com.generals.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the standard 21-piece army for one side.
 *
 * <pre>
 *   1x five-star general   1x four-star general  1x three-star general
 *   1x two-star general    1x one-star general   1x colonel
 *   1x lieutenant colonel  1x major              1x captain
 *   1x first lieutenant    1x second lieutenant  1x sergeant
 *   6x private             2x spy                1x flag
 * </pre>
 */
public final class ArmyFactory {

    public static final int ARMY_SIZE = 21;

    private ArmyFactory() {
    }

    /** The full roster of ranks, strongest first, with duplicates for the 6 privates. */
    public static List<Rank> standardRoster() {
        List<Rank> roster = new ArrayList<>(ARMY_SIZE);
        roster.add(Rank.FIVE_STAR_GENERAL);
        roster.add(Rank.FOUR_STAR_GENERAL);
        roster.add(Rank.THREE_STAR_GENERAL);
        roster.add(Rank.TWO_STAR_GENERAL);
        roster.add(Rank.ONE_STAR_GENERAL);
        roster.add(Rank.COLONEL);
        roster.add(Rank.LIEUTENANT_COLONEL);
        roster.add(Rank.MAJOR);
        roster.add(Rank.CAPTAIN);
        roster.add(Rank.FIRST_LIEUTENANT);
        roster.add(Rank.SECOND_LIEUTENANT);
        roster.add(Rank.SERGEANT);
        roster.add(Rank.SPY);
        roster.add(Rank.SPY);
        roster.add(Rank.FLAG);
        for (int i = 0; i < 6; i++) {
            roster.add(Rank.PRIVATE);
        }
        return roster;
    }

    public static List<Piece> newArmy(PlayerColor owner) {
        List<Piece> army = new ArrayList<>(ARMY_SIZE);
        for (Rank rank : standardRoster()) {
            // position is a placeholder until the player finishes deploying
            army.add(Piece.createHidden(rank, owner, new Position(0, 0)));
        }
        return army;
    }
}
