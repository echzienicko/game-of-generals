package com.generals.api.dto;

/**
 * The outcome of the most recent contested square, for the battle animation.
 *
 * <p>The four rank fields are {@code null} for any side the viewer does not own: a battle
 * exposes nothing, so the opponent's ranks never appear here. The {@code description} is
 * likewise rebuilt per viewer and names no ranks. {@code attackerSurvives} and
 * {@code defenderSurvives} are public — the arbiter announces who held the square.
 */
public record BattleDto(
        long attackerId,
        String attackerOwner,
        String attackerRank,
        String attackerRankName,
        int fromRow,
        int fromCol,
        boolean attackerSurvives,
        long defenderId,
        String defenderOwner,
        String defenderRank,
        String defenderRankName,
        int toRow,
        int toCol,
        boolean defenderSurvives,
        String description) {
}
