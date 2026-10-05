package com.generals.api;

import com.generals.api.dto.LeaderboardDto;
import com.generals.service.Leaderboard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The win table.
 *
 * <p>Its own controller because it is not about a game: {@code GameController} is mapped
 * under {@code /api/games}, and nothing here can see a live game, a token or a board.
 */
@RestController
@RequestMapping("/api/leaderboard")
public class LeaderboardController {

    private static final int MAX_LIMIT = 500;

    private final Leaderboard leaderboard;

    public LeaderboardController(Leaderboard leaderboard) {
        this.leaderboard = leaderboard;
    }

    /**
     * The table, best first.
     *
     * <p>Everyone by default: the ledger is a LAN spare-room affair, not a league table
     * with an audience, so truncating it silently would just look like a shorter history.
     * {@code ?limit=} exists for a caller that wants a slice and is capped at
     * {@link #MAX_LIMIT}.
     */
    @GetMapping
    public LeaderboardDto leaderboard(
            @RequestParam(name = "limit", required = false) Integer limit) {
        int capped = limit == null ? Integer.MAX_VALUE : Math.max(1, Math.min(MAX_LIMIT, limit));
        return new LeaderboardDto(leaderboard.top(capped).stream()
                .map(e -> new LeaderboardDto.LeaderboardEntryDto(
                        e.name(), e.wins(), e.losses(), e.gamesPlayed(), e.winRatePercent(),
                        e.streak(), e.bestStreak(), e.lastWinAt()))
                .toList(), leaderboard.size());
    }
}
