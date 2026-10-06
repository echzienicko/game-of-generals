package com.generals.domain;

/**
 * One completed turn, kept for the move log and for replay/debugging.
 *
 * <p>{@code moverRevealed} records whether the moving piece was publicly known at the
 * moment it moved. The API layer uses it to redact the log, because naming a rank that
 * has never fought would leak the deployment.
 */
public record MoveRecord(
        int moveNumber,
        PlayerColor player,
        long pieceId,
        Rank rank,
        boolean moverRevealed,
        Position from,
        Position to,
        BattleResult battle,
        String description,
        /**
         * True when the server played this move because the mover's clock ran out.
         *
         * <p>The move is a perfectly ordinary move in every other respect. The flag is here
         * so the log can say so, which {@code GameViewMapper} does for both players: a piece
         * that moves by itself with no explanation reads as a bug, and the clock is a
         * decision the reader made by setting a timer at all.
         */
        boolean byClock) {

    public boolean wasContested() {
        return battle != null;
    }
}
