package com.generals.domain;

/**
 * How hard the computer tries.
 *
 * <p>The three levels are a progression rather than three unrelated bots: the first plays
 * legal moves at random, the second scores them, and the third additionally remembers the
 * openings that lost.
 */
public enum BotDifficulty {

    /** Any legal move, chosen uniformly. Legal, but it will walk into anything. */
    RANDOM,

    /** Scores every legal move and takes the best, with a little noise so it is not robotic. */
    HEURISTIC,

    /** As {@link #HEURISTIC}, but avoids openings that have previously lost. */
    LEARNING;

    public static BotDifficulty parse(String value) {
        if (value == null || value.isBlank()) {
            return LEARNING;
        }
        try {
            return BotDifficulty.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "unknown difficulty '" + value + "', expected one of RANDOM, HEURISTIC, LEARNING");
        }
    }
}