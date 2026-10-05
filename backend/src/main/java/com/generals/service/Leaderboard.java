package com.generals.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wins per player name, kept across restarts.
 *
 * <p>A name is the whole of a player's identity here, so it is also the ledger's key: the
 * same name winning again tops up the same row rather than starting a new one. Keys are
 * compared case-insensitively with whitespace collapsed, so "Nick", "nick" and "Nick "
 * are one player; the casing first seen is the one shown.
 *
 * <p>Unlike games (see {@link GameManager}) this survives a restart, because a score that
 * empties on every redeploy is not worth keeping. Persistence is deliberately forgiving:
 * an unreadable or corrupt file is logged and replaced by an empty ledger rather than
 * taking the whole server down with it.
 */
@Component
public class Leaderboard {

    private static final Logger log = LoggerFactory.getLogger(Leaderboard.class);

    /** Long enough for "Sergeant-Major O'Neill", short enough for the leaderboard table. */
    public static final int MAX_NAME_LENGTH = 24;

    private final ObjectWriter writer;
    private final ObjectReader reader;
    private final Path file;
    private final Map<String, Entry> byKey = new ConcurrentHashMap<>();

    public Leaderboard(ObjectMapper mapper,
                       @Value("${generals.leaderboard.path:data/leaderboard.json}") String path) {
        // Lenient on the way in, strict on the way out: a ledger written by a later version
        // must still be readable, since refusing it would throw away every score in it.
        this.reader = mapper.readerFor(new TypeReference<List<Entry>>() {
        }).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.writer = mapper.writerWithDefaultPrettyPrinter();
        this.file = Path.of(path).toAbsolutePath();
        load();
    }

    /** One player. A record, so a row is replaced wholesale rather than mutated in place. */
    public record Entry(String name, int wins, int losses, int streak, int bestStreak, String lastWinAt) {

        /**
         * Games this player has a result recorded for.
         *
         * <p>Not games they were ever seated in: only finished games are filed, and the
         * computer's own results are not, so this is the number the win rate is taken over.
         */
        public int gamesPlayed() {
            return wins + losses;
        }

        /**
         * Wins as a whole percentage of games played.
         *
         * <p>Rounded, because "67%" is the truth and "66.66666%" is a spreadsheet. Zero for a
         * row with no games: a hand-edited ledger can hold one, and it must not divide by zero.
         */
        public int winRatePercent() {
            int played = gamesPlayed();
            return played == 0 ? 0 : (int) Math.round(wins * 100.0 / played);
        }
    }

    /**
     * The key a name is stored under, or a blank string if it cannot be a name at all.
     *
     * <p>Callers that accept a name from a client should use {@link #requireValid} instead;
     * this is for keys already known to be good.
     */
    static String key(String name) {
        return name == null ? "" : name.strip().replaceAll("\\s+", " ").toLowerCase();
    }

    /**
     * Checks a player-chosen name, returning it trimmed and tidied.
     *
     * @throws IllegalArgumentException if it is empty, too long, or holds control characters
     */
    public static String requireValid(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tell us your name before you play");
        }
        String tidied = name.strip().replaceAll("\\s+", " ");
        if (tidied.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "that name is longer than " + MAX_NAME_LENGTH + " characters");
        }
        if (tidied.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("that name has characters we cannot show");
        }
        return tidied;
    }

    /**
     * Files the result of a finished game.
     *
     * <p>Either name may be null, blank or the computer: a seat with no name simply has
     * no row, so a win against the bot still counts for the human and the bot's own wins
     * do not pollute the table.
     *
     * <p>Call this at most once per game — {@code GameSession} owns that guarantee, since
     * a double-counted win is the one bug a leaderboard cannot hide.
     */
    public void recordResult(String winnerName, String loserName) {
        String winnerKey = key(winnerName);
        String loserKey = key(loserName);
        // An anonymous seat is the common case for the computer, so each side is skipped on
        // its own rather than the whole result being dropped: losing to the bot is a loss,
        // and it has to be filed as one.
        boolean hasWinner = !winnerKey.isEmpty();
        boolean hasLoser = !loserKey.isEmpty() && !loserKey.equals(winnerKey);
        if (!hasWinner && !hasLoser) {
            log.info("neither seat has a name, nothing recorded");
            return;
        }
        Instant now = Instant.now();

        synchronized (this) {
            if (hasWinner) {
                Entry winner = byKey.get(winnerKey);
                int streak = (winner == null ? 0 : winner.streak()) + 1;
                byKey.put(winnerKey, new Entry(
                        winner == null ? requireValid(winnerName) : winner.name(),
                        (winner == null ? 0 : winner.wins()) + 1,
                        winner == null ? 0 : winner.losses(),
                        streak,
                        Math.max(streak, winner == null ? 0 : winner.bestStreak()),
                        now.toString()));
            }
            if (hasLoser) {
                Entry loser = byKey.get(loserKey);
                byKey.put(loserKey, new Entry(
                        loser == null ? requireValid(loserName) : loser.name(),
                        loser == null ? 0 : loser.wins(),
                        (loser == null ? 0 : loser.losses()) + 1,
                        0,
                        loser == null ? 0 : loser.bestStreak(),
                        loser == null ? null : loser.lastWinAt()));
            }
            persist();
        }
    }

    /** The table, best first. Ties break on best streak, then alphabetically. */
    public List<Entry> top(int limit) {
        List<Entry> all = new ArrayList<>(byKey.values());
        all.sort(Comparator.comparingInt(Entry::wins).reversed()
                .thenComparing(Comparator.comparingInt(Entry::bestStreak).reversed())
                .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return all.size() > limit ? List.copyOf(all.subList(0, limit)) : List.copyOf(all);
    }

    public int size() {
        return byKey.size();
    }

    /** Test seam: forget everything without touching the file. */
    void reset() {
        synchronized (this) {
            byKey.clear();
        }
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            List<Entry> entries = reader.readValue(file.toFile());
            for (Entry entry : entries) {
                String k = key(entry.name());
                if (!k.isEmpty()) {
                    byKey.put(k, entry);
                }
            }
            log.info("leaderboard: loaded {} players from {}", byKey.size(), file);
        } catch (IOException | RuntimeException ex) {
            log.warn("leaderboard at {} is unreadable, starting empty", file, ex);
        }
    }

    private void persist() {
        Path parent = file.getParent();
        Path temp = null;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            temp = Files.createTempFile(parent, "leaderboard", ".tmp");
            writer.writeValue(temp.toFile(), top(Integer.MAX_VALUE));
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException ex) {
            log.warn("could not persist the leaderboard to {}", file, ex);
        } finally {
            deleteQuietly(temp);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // the move consumed it; nothing to do
        }
    }
}
