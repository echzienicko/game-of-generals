package com.generals.service;

/** Thrown when a game id does not exist (or has expired). */
public class GameNotFoundException extends RuntimeException {

    public GameNotFoundException(String gameId) {
        super("no game with id " + gameId);
    }
}
