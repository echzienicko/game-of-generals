package com.generals.domain;

/**
 * A square on the board, using zero-based row/column coordinates.
 *
 * <p>The board is 8 rows by 9 columns. Rows {@code 0..2} are RED's deployment zone and
 * rows {@code 5..7} are BLUE's; rows {@code 3} and {@code 4} begin empty.
 */
public record Position(int row, int col) implements Comparable<Position> {

    public static final int ROWS = 8;
    public static final int COLS = 9;

    public Position {
        if (row < 0 || row >= ROWS) {
            throw new IllegalArgumentException("row out of bounds: " + row + " (expected 0.." + (ROWS - 1) + ")");
        }
        if (col < 0 || col >= COLS) {
            throw new IllegalArgumentException("col out of bounds: " + col + " (expected 0.." + (COLS - 1) + ")");
        }
    }

    public static boolean isOnBoard(int row, int col) {
        return row >= 0 && row < ROWS && col >= 0 && col < COLS;
    }

    public static Position of(int row, int col) {
        return new Position(row, col);
    }

    /** True when the square belongs to the player's own camp (their back three rows). */
    public boolean isInOwnCamp(PlayerColor color) {
        return color == PlayerColor.RED ? row <= 2 : row >= 5;
    }

    /** True when the square is in the enemy camp — reaching it with a flag is an escape. */
    public boolean isInEnemyCamp(PlayerColor color) {
        return color == PlayerColor.RED ? row >= 5 : row <= 2;
    }

    /** True when moving one square in a straight orthogonal line. */
    public boolean isOrthogonallyAdjacentTo(Position other) {
        int dr = Math.abs(this.row - other.row);
        int dc = Math.abs(this.col - other.col);
        return dr + dc == 1;
    }

    /** True when exactly two squares apart in a straight line (the solo "double move"). */
    public boolean isTwoStepsInStraightLineTo(Position other) {
        int dr = Math.abs(this.row - other.row);
        int dc = Math.abs(this.col - other.col);
        return (dr == 2 && dc == 0) || (dr == 0 && dc == 2);
    }

    /** The four orthogonally neighbouring squares that are still on the board. */
    public java.util.List<Position> orthogonalNeighbours() {
        java.util.List<Position> result = new java.util.ArrayList<>(4);
        int[][] deltas = { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } };
        for (int[] d : deltas) {
            int r = row + d[0];
            int c = col + d[1];
            if (isOnBoard(r, c)) {
                result.add(new Position(r, c));
            }
        }
        return result;
    }

    /** Stable ordering so positions can be sorted or used in tree maps. */
    @Override
    public int compareTo(Position o) {
        int byRow = Integer.compare(row, o.row);
        return byRow != 0 ? byRow : Integer.compare(col, o.col);
    }

    /** Human readable label such as {@code E5}, where columns are letters A..I. */
    public String label() {
        return String.valueOf((char) ('A' + col)) + (row + 1);
    }

    @Override
    public String toString() {
        return label();
    }
}
