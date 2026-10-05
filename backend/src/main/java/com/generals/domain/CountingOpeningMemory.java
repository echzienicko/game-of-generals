package com.generals.domain;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * An {@link OpeningMemory} that counts, in memory, how often each of the bot's own opening
 * lines preceded a loss.
 *
 * <p>Every prefix of a losing line is recorded, not just the whole line. That makes the
 * lookup a plain {@code get}: if a two-move prefix has a count, then that two-move opening
 * has preceded a loss, and any extension of it would carry at least that risk.
 *
 * <p>State is in memory only, like the games themselves, so it starts empty on a restart.
 * The counts are kept unbounded on purpose — the set of distinct openings a bot plays is
 * small compared with the number of games, and a stale entry is harmless.
 */
@Component
public final class CountingOpeningMemory implements OpeningMemory {

    private final Map<String, Integer> losses = Collections.synchronizedMap(new HashMap<>());

    @Override
    public void recordLoss(List<String> line) {
        List<String> prefix = new java.util.ArrayList<>();
        for (String move : line) {
            prefix.add(move);
            String key = key(prefix);
            losses.merge(key, 1, Integer::sum);
        }
    }

    @Override
    public int lossesAfter(List<String> prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return 0;
        }
        Integer count = losses.get(key(prefix));
        return count == null ? 0 : count;
    }

    @Override
    public void clear() {
        losses.clear();
    }

    /** The number of distinct openings currently on record. Exposed for tests and diagnostics. */
    public int size() {
        return losses.size();
    }

    private static String key(List<String> prefix) {
        return String.join(" ", prefix);
    }
}