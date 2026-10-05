package com.generals.api.dto;

import java.util.List;

/**
 * The win table.
 *
 * @param totalPlayers rows on the ledger, which is more than {@code entries} only when a
 *                     caller asked for a slice with {@code ?limit=}; the page needs to be
 *                     able to say "everyone" and mean it
 */
public record LeaderboardDto(List<LeaderboardEntryDto> entries, int totalPlayers) {

    /**
     * @param gamesPlayed   finished games on this row, the denominator of the win rate
     * @param winRatePercent wins as a whole percentage of {@code gamesPlayed}, 0-100
     */
    public record LeaderboardEntryDto(
            String name,
            int wins,
            int losses,
            int gamesPlayed,
            int winRatePercent,
            int streak,
            int bestStreak,
            String lastWinAt) {
    }
}
