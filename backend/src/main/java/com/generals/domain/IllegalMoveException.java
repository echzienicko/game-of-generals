package com.generals.domain;

/** Thrown when a player attempts something the rules do not allow. */
public class IllegalMoveException extends RuntimeException {

    public IllegalMoveException(String message) {
        super(message);
    }
}
