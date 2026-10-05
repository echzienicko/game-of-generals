package com.generals.service;

/** Thrown when a player token does not belong to this game, or the game is full. */
public class GameAccessException extends RuntimeException {

    public GameAccessException(String message) {
        super(message);
    }
}
