package com.generals.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionAndBoardTest {

    @Test
    @DisplayName("positions outside the 8x9 board are rejected")
    void rejectsOutOfBounds() {
        assertThrows(IllegalArgumentException.class, () -> new Position(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Position(0, -1));
        assertThrows(IllegalArgumentException.class, () -> new Position(8, 0));
        assertThrows(IllegalArgumentException.class, () -> new Position(0, 9));
    }

    @Test
    @DisplayName("the board is 8 rows by 9 columns")
    void boardDimensions() {
        assertEquals(8, Position.ROWS);
        assertEquals(9, Position.COLS);
        assertEquals(72, Position.ROWS * Position.COLS);
    }

    @Test
    @DisplayName("red camps in rows 0-2, blue in rows 5-7")
    void campsAreCorrect() {
        assertTrue(new Position(0, 0).isInOwnCamp(PlayerColor.RED));
        assertTrue(new Position(2, 8).isInOwnCamp(PlayerColor.RED));
        assertFalse(new Position(3, 4).isInOwnCamp(PlayerColor.RED));

        assertTrue(new Position(5, 0).isInOwnCamp(PlayerColor.BLUE));
        assertTrue(new Position(7, 8).isInOwnCamp(PlayerColor.BLUE));
        assertFalse(new Position(4, 4).isInOwnCamp(PlayerColor.BLUE));
    }

    @Test
    @DisplayName("the enemy camp is the far end of the board")
    void enemyCampIsTheFarEnd() {
        assertTrue(new Position(7, 4).isInEnemyCamp(PlayerColor.RED));
        assertTrue(new Position(0, 4).isInEnemyCamp(PlayerColor.BLUE));
        assertFalse(new Position(3, 4).isInEnemyCamp(PlayerColor.RED));
    }

    @Test
    @DisplayName("a deployment zone holds 27 squares")
    void deploymentZoneSize() {
        assertEquals(27, Board.deploymentZone(PlayerColor.RED).size());
        assertEquals(27, Board.deploymentZone(PlayerColor.BLUE).size());
        assertTrue(Board.deploymentZone(PlayerColor.RED)
                .stream().allMatch(p -> p.isInOwnCamp(PlayerColor.RED)));
    }

    @Test
    @DisplayName("orthogonal adjacency is symmetric and diagonal-free")
    void adjacency() {
        assertTrue(new Position(3, 4).isOrthogonallyAdjacentTo(new Position(3, 5)));
        assertTrue(new Position(3, 4).isOrthogonallyAdjacentTo(new Position(4, 4)));
        assertFalse(new Position(3, 4).isOrthogonallyAdjacentTo(new Position(4, 5)));
        assertFalse(new Position(3, 4).isOrthogonallyAdjacentTo(new Position(3, 4)));
    }

    @Test
    @DisplayName("only the real neighbours are returned at the edges")
    void neighboursRespectEdges() {
        assertEquals(2, new Position(0, 0).orthogonalNeighbours().size(), "corner");
        assertEquals(3, new Position(0, 4).orthogonalNeighbours().size(), "edge");
        assertEquals(4, new Position(4, 4).orthogonalNeighbours().size(), "interior");
    }

    @Test
    @DisplayName("a piece with a friendly neighbour is not alone")
    void aloneDetection() {
        Board board = new Board();
        Piece scout = Piece.create(Rank.SERGEANT, PlayerColor.RED, new Position(3, 4));
        board.place(scout);
        assertTrue(board.isAloneOnBoard(scout));

        board.place(Piece.create(Rank.PRIVATE, PlayerColor.RED, new Position(3, 5)));
        assertFalse(board.isAloneOnBoard(scout));
    }

    @Test
    @DisplayName("an enemy neighbour does not count as support")
    void enemyDoesNotGiveSupport() {
        Board board = new Board();
        Piece scout = Piece.create(Rank.SERGEANT, PlayerColor.RED, new Position(3, 4));
        board.place(scout);
        board.place(Piece.create(Rank.PRIVATE, PlayerColor.BLUE, new Position(3, 5)));
        assertTrue(board.isAloneOnBoard(scout));
    }

    @Test
    @DisplayName("squares are labelled by letter and number")
    void labels() {
        assertEquals("A1", new Position(0, 0).label());
        assertEquals("I1", new Position(0, 8).label());
        assertEquals("E4", new Position(3, 4).label());
    }
}
