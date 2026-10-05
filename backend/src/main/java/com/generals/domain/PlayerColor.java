package com.generals.domain;

/** The two sides of a match. {@link #RED} always moves first. */
public enum PlayerColor {

    RED,
    BLUE;

    public PlayerColor opponent() {
        return this == RED ? BLUE : RED;
    }
}
