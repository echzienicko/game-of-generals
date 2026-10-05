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
        String description) {

    public boolean wasContested() {
        return battle != null;
    }
}
