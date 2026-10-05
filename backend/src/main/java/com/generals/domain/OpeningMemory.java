package com.generals.domain;

import java.util.List;
import java.util.Optional;

/**
 * What the bot remembers about lines of play that ended badly for it.
 *
 * <p>The bot records the openings that lost a game; the memory answers, for any prefix of
 * the line it is currently playing, how often a loss has followed. The bot uses that to
 * stop repeating a mistake.
 */
public interface OpeningMemory {

    /**
     * Records that the bot lost a game in which it played {@code line} — the ordered moves
     * it made from the start of the match.
     */
    void recordLoss(List<String> line);

    /**
     * How many recorded losses have a line beginning with {@code prefix}.
     *
     * @return {@code 0} when the prefix has never preceded a loss
     */
    int lossesAfter(List<String> prefix);

    /** Forgets everything. */
    void clear();

    /** An implementation that remembers nothing, for callers that have nothing to teach. */
    static OpeningMemory none() {
        return NoMemory.INSTANCE;
    }

    /** Sentinel meaning "no bot in this game", distinguishable from an empty memory. */
    Optional<BotDifficulty> NONE = Optional.empty();

    final class NoMemory implements OpeningMemory {

        static final NoMemory INSTANCE = new NoMemory();

        @Override
        public void recordLoss(List<String> line) {
        }

        @Override
        public int lossesAfter(List<String> prefix) {
            return 0;
        }

        @Override
        public void clear() {
        }
    }
}