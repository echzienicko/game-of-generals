package com.generals.domain;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A single piece on the board.
 *
 * <p>A piece keeps its identity ({@link #id}) for the whole match. Its {@link #revealed}
 * flag drives the game's fog of war: an enemy piece stays hidden from the opposing
 * client until it takes part in a battle, at which point the server reveals it.
 */
public final class Piece {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final long id;
    private final Rank rank;
    private final PlayerColor owner;

    private Position position;
    private boolean revealed;

    private Piece(Rank rank, PlayerColor owner, Position position, boolean revealed) {
        this.id = SEQUENCE.incrementAndGet();
        this.rank = Objects.requireNonNull(rank, "rank");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.position = Objects.requireNonNull(position, "position");
        this.revealed = revealed;
    }

    /** Creates a face-up piece; used for the owning player's own army. */
    public static Piece create(Rank rank, PlayerColor owner, Position position) {
        return new Piece(rank, owner, position, true);
    }

    /** Creates a face-down piece during the secret deployment phase. */
    public static Piece createHidden(Rank rank, PlayerColor owner, Position position) {
        return new Piece(rank, owner, position, false);
    }

    public long id() {
        return id;
    }

    public Rank rank() {
        return rank;
    }

    public PlayerColor owner() {
        return owner;
    }

    public Position position() {
        return position;
    }

    public boolean isRevealed() {
        return revealed;
    }

    public boolean isOwnedBy(PlayerColor color) {
        return owner == color;
    }

    public boolean isFlag() {
        return rank.isFlag();
    }

    public boolean isSpy() {
        return rank.isSpy();
    }

    public boolean isPrivate() {
        return rank.isPrivate();
    }

    public void moveTo(Position target) {
        this.position = Objects.requireNonNull(target, "target");
    }

    /**
     * Makes this piece's identity public knowledge.
     *
     * <p>Battles deliberately do <b>not</b> call this: under the official rules an
     * eliminated piece, and the piece that beat it, both stay face-down to the opponent
     * until the game ends. Nothing in the engine reveals a piece today, so this exists
     * for the one thing the rules do allow — a player exposing their own flag.
     */
    public void reveal() {
        this.revealed = true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Piece other && id == other.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public String toString() {
        return owner + " " + rank + "@" + position + (revealed ? "" : " (hidden)");
    }
}
