package com.generals.domain;

/**
 * Validates that a piece may move to a given square.
 *
 * <p>A move is one square orthogonally. The extra rule of the game is the "solo move":
 * a piece with no friendly piece in any adjacent square may advance two squares in a
 * straight line to an empty square.
 */
public final class MoveValidator {

    private final boolean soloDoubleMoveEnabled;

    public MoveValidator() {
        this(true);
    }

    public MoveValidator(boolean soloDoubleMoveEnabled) {
        this.soloDoubleMoveEnabled = soloDoubleMoveEnabled;
    }

    public void validate(Board board, Piece mover, Position target) {
        if (mover.position().equals(target)) {
            throw new IllegalMoveException("a piece must actually move");
        }

        boolean oneStep = mover.position().isOrthogonallyAdjacentTo(target);
        boolean soloTwoStep = false;
        if (!oneStep && soloDoubleMoveEnabled
                && mover.position().isTwoStepsInStraightLineTo(target)) {
            if (board.pieceAt(target).isPresent()) {
                throw new IllegalMoveException("a double move must land on an empty square");
            }
            if (!board.isAloneOnBoard(mover)) {
                throw new IllegalMoveException(
                        "only a piece with no friendly neighbour may move two squares");
            }
            soloTwoStep = true;
        }

        if (!oneStep && !soloTwoStep) {
            throw new IllegalMoveException("a piece moves one square up, down, left or right");
        }

        Piece occupant = board.pieceAt(target).orElse(null);
        if (occupant != null && occupant.isOwnedBy(mover.owner())) {
            throw new IllegalMoveException("cannot move onto your own piece at " + target.label());
        }
    }

    public boolean isValid(Board board, Piece mover, Position target) {
        try {
            validate(board, mover, target);
            return true;
        } catch (IllegalMoveException ex) {
            return false;
        }
    }

    /** Whether the solo double move is in play for these rules. */
    public boolean soloDoubleMoveEnabled() {
        return soloDoubleMoveEnabled;
    }
}
