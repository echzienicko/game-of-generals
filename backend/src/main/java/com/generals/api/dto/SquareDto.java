package com.generals.api.dto;

/** One square as a client is allowed to see it. */
public record SquareDto(
        int row,
        int col,
        String label,
        Long pieceId,
        String owner,
        String rank,
        String rankName,
        boolean revealed) {

    public static SquareDto empty(int row, int col) {
        return new SquareDto(row, col, label(row, col), null, null, null, null, false);
    }

    private static String label(int row, int col) {
        return String.valueOf((char) ('A' + col)) + (row + 1);
    }
}
