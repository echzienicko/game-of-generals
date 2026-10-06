package com.generals.api.dto;

import java.util.List;

/**
 * Everything one player is permitted to know about a game.
 *
 * <p>This is the only shape the server ever sends. Enemy pieces that have never fought
 * appear with a {@code null} rank, so the client cannot accidentally reveal them.
 */
public record GameStateDto(
        String gameId,
        String status,
        String youAre,
        boolean opponentIsBot,
        /**
         * Whether the player reading this view has sent their army in.
         *
         * <p>Per-viewer, and about the viewer's own deployment rather than the opponent's:
         * it is what turns the deploy button into a settled, confirmed state, and it is the
         * only thing that survives a refresh mid-deployment. The opponent's side is not
         * answered here — the board already shows their camp filling up, which is all a
         * player is ever told about it.
         */
        boolean youPlaced,
        String currentPlayer,
        String winner,
        String winReason,
        boolean flagEscapePending,
        /**
         * When the move on the clock falls due, in epoch milliseconds, or null when no clock
         * is running.
         *
         * <p>The same for both players, since both are watching the same turn. Sent as an
         * absolute moment rather than a number of seconds so a clock does not drift: the
         * client subtracts from its own clock and is corrected by every push.
         */
        Long turnDeadlineMillis,
        /** How many seconds a move is given; 0 when the clock is switched off. */
        long turnSeconds,
        int turnNumber,
        int rows,
        int cols,
        int yourPiecesRemaining,
        int opponentPiecesRemaining,
        List<SquareDto> board,
        BattleDto lastBattle,
        List<String> log,
        /** Both seats with the names they were created under, in RED, BLUE order. */
        List<SeatDto> seats,
        /**
         * What the two players have said to each other, oldest first.
         *
         * <p>Same for both viewers, so this is not a per-player redaction like the board:
         * a message was written to be read.
         */
        List<ChatMessageDto> chat) {
}
