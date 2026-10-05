package com.generals.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoveValidatorTest {

    private Board board;
    private MoveValidator validator;

    @BeforeEach
    void setUp() {
        board = new Board();
        validator = new MoveValidator();
    }

    @Test
    @DisplayName("a piece may move one square in any of four directions")
    void singleStepAllowed() {
        Piece piece = Piece.create(Rank.COLONEL, PlayerColor.RED, new Position(4, 4));
        board.place(piece);
        assertTrue(validator.isValid(board, piece, new Position(3, 4)));
        assertTrue(validator.isValid(board, piece, new Position(5, 4)));
        assertTrue(validator.isValid(board, piece, new Position(4, 3)));
        assertTrue(validator.isValid(board, piece, new Position(4, 5)));
    }

    @Test
    @DisplayName("diagonal and long moves are rejected")
    void illegalGeometryRejected() {
        Piece piece = Piece.create(Rank.COLONEL, PlayerColor.RED, new Position(4, 4));
        board.place(piece);
        assertFalse(validator.isValid(board, piece, new Position(3, 3)), "diagonal");
        assertFalse(validator.isValid(board, piece, new Position(4, 4)), "staying put");
    }

    @Test
    @DisplayName("a piece that has support cannot skip a square")
    void twoSquaresRejectedWhenSupported() {
        Piece piece = Piece.create(Rank.COLONEL, PlayerColor.RED, new Position(4, 4));
        board.place(piece);
        // a friendly piece next to it blocks the solo double move
        board.place(Piece.create(Rank.PRIVATE, PlayerColor.RED, new Position(4, 5)));
        assertFalse(validator.isValid(board, piece, new Position(2, 4)), "two rows up with support");
        assertFalse(validator.isValid(board, piece, new Position(4, 2)), "two cols left with support");
    }

    @Test
    @DisplayName("a lone piece may advance two squares in a straight line")
    void soloDoubleMove() {
        Piece piece = Piece.create(Rank.SERGEANT, PlayerColor.RED, new Position(4, 4));
        board.place(piece);
        assertTrue(validator.isValid(board, piece, new Position(2, 4)), "two rows up");
        assertTrue(validator.isValid(board, piece, new Position(6, 4)), "two rows down");
        assertTrue(validator.isValid(board, piece, new Position(4, 2)), "two cols left");
        assertTrue(validator.isValid(board, piece, new Position(4, 6)), "two cols right");
    }

    @Test
    @DisplayName("a supported piece may not use the double move")
    void supportedPieceCannotDoubleMove() {
        Piece piece = Piece.create(Rank.SERGEANT, PlayerColor.RED, new Position(4, 4));
        board.place(piece);
        board.place(Piece.create(Rank.PRIVATE, PlayerColor.RED, new Position(4, 5)));
        assertFalse(validator.isValid(board, piece, new Position(4, 2)));
    }

    @Test
    @DisplayName("a double move may not land on an occupied square")
    void doubleMoveNeedsEmptyDestination() {
        Piece piece = Piece.create(Rank.SERGEANT, PlayerColor.RED, new Position(4, 4));
        board.place(piece);
        board.place(Piece.create(Rank.PRIVATE, PlayerColor.RED, new Position(4, 3)));
        assertFalse(validator.isValid(board, piece, new Position(4, 2)),
                "an allied piece is in the way");
    }

    @Test
    @DisplayName("the double move cannot be disabled")
    void doubleMoveCanBeTurnedOff() {
        MoveValidator strict = new MoveValidator(false);
        Piece piece = Piece.create(Rank.SERGEANT, PlayerColor.RED, new Position(4, 4));
        board.place(piece);
        assertFalse(strict.isValid(board, piece, new Position(2, 4)));
        assertTrue(strict.isValid(board, piece, new Position(3, 4)));
    }

    @Test
    @DisplayName("a piece may not move onto its own piece")
    void cannotStackAllies() {
        Piece piece = Piece.create(Rank.COLONEL, PlayerColor.RED, new Position(4, 4));
        board.place(piece);
        board.place(Piece.create(Rank.PRIVATE, PlayerColor.RED, new Position(4, 5)));
        IllegalMoveException ex = assertThrows(IllegalMoveException.class,
                () -> validator.validate(board, piece, new Position(4, 5)));
        assertTrue(ex.getMessage().contains("your own piece"));
    }

    @Test
    @DisplayName("a piece may move onto an enemy piece and force a battle")
    void canAttackEnemy() {
        Piece piece = Piece.create(Rank.COLONEL, PlayerColor.RED, new Position(4, 4));
        board.place(piece);
        board.place(Piece.create(Rank.SERGEANT, PlayerColor.BLUE, new Position(4, 5)));
        assertTrue(validator.isValid(board, piece, new Position(4, 5)));
    }
}
