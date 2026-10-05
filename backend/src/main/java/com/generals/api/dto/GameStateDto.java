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
        String currentPlayer,
        String winner,
        String winReason,
        boolean flagEscapePending,
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
