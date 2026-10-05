package com.generals.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * An 8x9 grid holding at most one piece per square.
 *
 * <p>Row 0 is RED's back rank and row 7 is BLUE's. Each player deploys inside their own
 * three rows ({@code 0..2} for RED, {@code 5..7} for BLUE) and advances towards the
 * other end of the board.
 */
public final class Board {

    public static final int ROWS = Position.ROWS;
    public static final int COLS = Position.COLS;

    private final Piece[][] grid = new Piece[ROWS][COLS];

    /** The 27 squares a player may deploy into, in a stable order. */
    public static List<Position> deploymentZone(PlayerColor color) {
        List<Position> zone = new ArrayList<>(27);
        int from = color == PlayerColor.RED ? 0 : 5;
        int to = color == PlayerColor.RED ? 2 : 7;
        for (int row = from; row <= to; row++) {
            for (int col = 0; col < COLS; col++) {
                zone.add(new Position(row, col));
            }
        }
        return zone;
    }

    public Optional<Piece> pieceAt(Position position) {
        Piece piece = grid[position.row()][position.col()];
        return Optional.ofNullable(piece);
    }

    public Piece pieceAtOrThrow(Position position) {
        return pieceAt(position).orElseThrow(
                () -> new IllegalStateException("no piece at " + position.label()));
    }

    public boolean isEmpty(Position position) {
        return grid[position.row()][position.col()] == null;
    }

    /** Places a piece, replacing whatever was there. Only valid during setup or via battle resolution. */
    public void place(Piece piece) {
        grid[piece.position().row()][piece.position().col()] = piece;
    }

    public void removeAt(Position position) {
        grid[position.row()][position.col()] = null;
    }

    public List<Piece> piecesOf(PlayerColor color) {
        List<Piece> result = new ArrayList<>();
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                Piece piece = grid[row][col];
                if (piece != null && piece.owner() == color) {
                    result.add(piece);
                }
            }
        }
        return result;
    }

    public List<Piece> allPieces() {
        List<Piece> result = new ArrayList<>();
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                if (grid[row][col] != null) {
                    result.add(grid[row][col]);
                }
            }
        }
        return result;
    }

    public int countPieces(PlayerColor color) {
        return piecesOf(color).size();
    }

    public Optional<Piece> findFlag(PlayerColor color) {
        return piecesOf(color).stream().filter(Piece::isFlag).findFirst();
    }

    /**
     * The "solo move" rule: a piece with no friendly neighbour in any of the four
     * adjacent squares may advance two squares in a straight line.
     */
    public boolean isAloneOnBoard(Piece piece) {
        for (Position neighbour : piece.position().orthogonalNeighbours()) {
            Piece adjacent = grid[neighbour.row()][neighbour.col()];
            if (adjacent != null && adjacent.isOwnedBy(piece.owner())) {
                return false;
            }
        }
        return true;
    }

    /** Every square the given piece could legally reach in one turn, ignoring battles. */
    public List<Position> possibleTargets(Piece piece, boolean soloDoubleMoveEnabled) {
        List<Position> targets = new ArrayList<>();
        for (Position neighbour : piece.position().orthogonalNeighbours()) {
            targets.add(neighbour);
        }
        if (soloDoubleMoveEnabled && isAloneOnBoard(piece)) {
            for (Position neighbour : piece.position().orthogonalNeighbours()) {
                for (Position far : neighbour.orthogonalNeighbours()) {
                    // straight line only, and not straight back to where we came from
                    if (piece.position().isTwoStepsInStraightLineTo(far)) {
                        targets.add(far);
                    }
                }
            }
        }
        return Collections.unmodifiableList(targets);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                Piece piece = grid[row][col];
                sb.append(piece == null ? "." : (piece.owner() == PlayerColor.RED ? "r" : "b"));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
