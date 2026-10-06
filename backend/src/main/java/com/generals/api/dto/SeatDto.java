package com.generals.api.dto;

import com.generals.domain.BotDifficulty;

/**
 * One side of a game, as a viewer is allowed to know it.
 *
 * <p>A name is not a hidden rank, so this is safe to send to both players: whoever is
 * sitting in a seat typed their own name in, and knowing who you are up against is the
 * whole point of a game played across a room. {@code bot} is true for the computer, which
 * has no name because nothing files a result for it.
 *
 * <p>{@code difficulty} says how hard that computer plays, and only for that seat: null on
 * a human's. It is sent because the player chose it on the way in, so telling them after a
 * refresh which level they are up against is the server being helpful rather than
 * something it worked out. Nothing here comes from the board, so nothing here can be a
 * rank: the fog of war is about {@code Square.rank}, not about who is sitting opposite you.
 */
public record SeatDto(String color, String name, boolean bot, boolean you, String difficulty) {

    /** The difficulty as the wire spells it, or null for a human seat. */
    public static String difficultyOf(BotDifficulty difficulty) {
        return difficulty == null ? null : difficulty.name();
    }
}