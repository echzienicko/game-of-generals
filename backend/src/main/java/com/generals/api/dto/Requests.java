package com.generals.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;

import java.util.List;

/** Request bodies accepted over REST. */

public final class Requests {

    private Requests() {
    }

    /** A secret deployment: exactly 21 pieces, all inside the player's own camp. */
    public record PlacementRequest(
            @NotNull List<@Valid DeploymentEntry> pieces) {
    }

    public record DeploymentEntry(
            @NotNull Integer row,
            @NotNull Integer col,
            @NotBlank String rank) {
    }

    /**
     * The name a player wants to be recorded under.
     *
     * <p>Checked by hand in {@link com.generals.service.Leaderboard#requireValid} rather
     * than by a bean constraint, because the error message has to read like an
     * instruction ("tell us your name before you play") and {@code @NotBlank}'s does not.
     */
    public record NameRequest(String name) {
    }

    /** A single turn. */
    public record MoveRequest(
            @NotNull Coordinate from,
            @NotNull Coordinate to) {
    }

    /**
     * A line of chat.
     *
     * <p>No {@code @NotBlank}: the rule lives in
     * {@link com.generals.service.GameSession#requireMessage}, so the refusal reads like a
     * sentence rather than like a validation report. The author is not in here — the
     * server takes it from the seat, so it cannot be forged.
     */
    public record ChatRequest(String text) {
    }

    public record Coordinate(
            @NotNull Integer row,
            @NotNull Integer col) {
    }
}
