package com.generals.domain;

/**
 * The military hierarchy of a Game of the Generals army, ordered from strongest
 * (five-star general) to weakest (flag).
 *
 * <p>{@link #power} is a simple linear ordering used to compare two ordinary ranks.
 * The three exceptional ranks — {@link #SPY}, {@link #PRIVATE} and {@link #FLAG} —
 * do not follow that ordering during a battle and are special-cased by
 * {@link BattleResolver}.
 */
public enum Rank {

    FIVE_STAR_GENERAL(15, "5-Star General"),
    FOUR_STAR_GENERAL(14, "4-Star General"),
    THREE_STAR_GENERAL(13, "3-Star General"),
    TWO_STAR_GENERAL(12, "2-Star General"),
    ONE_STAR_GENERAL(11, "1-Star General"),
    COLONEL(10, "Colonel"),
    LIEUTENANT_COLONEL(9, "Lt. Colonel"),
    MAJOR(8, "Major"),
    CAPTAIN(7, "Captain"),
    FIRST_LIEUTENANT(6, "1st Lieutenant"),
    SECOND_LIEUTENANT(5, "2nd Lieutenant"),
    SERGEANT(4, "Sergeant"),
    PRIVATE(3, "Private"),
    SPY(2, "Spy"),
    FLAG(1, "Flag");

    private final int power;
    private final String displayName;

    Rank(int power, String displayName) {
        this.power = power;
        this.displayName = displayName;
    }

    /** Higher means stronger. Only meaningful for the twelve officer ranks. */
    public int power() {
        return power;
    }

    public String displayName() {
        return displayName;
    }

    /** True for the twelve ranks from five-star general down to sergeant. */
    public boolean isOfficer() {
        return power >= SERGEANT.power;
    }

    public boolean isFlag() {
        return this == FLAG;
    }

    public boolean isSpy() {
        return this == SPY;
    }

    public boolean isPrivate() {
        return this == PRIVATE;
    }

    /** The only piece that can win a game without eliminating the enemy flag. */
    public boolean isOrdinaryOfficer() {
        return isOfficer();
    }

    @Override
    public String toString() {
        return displayName;
    }
}
