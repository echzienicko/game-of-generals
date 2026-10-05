package com.generals.api.dto;

/**
 * One side of a game, as a viewer is allowed to know it.
 *
 * <p>A name is not a hidden rank, so this is safe to send to both players: whoever is
 * sitting in a seat typed their own name in, and knowing who you are up against is the
 * whole point of a game played across a room. {@code bot} is true for the computer, which
 * has no name because nothing files a result for it.
 */
public record SeatDto(String color, String name, boolean bot, boolean you) {
}