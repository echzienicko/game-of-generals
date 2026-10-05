package com.generals.config;

import java.security.Principal;

/** The STOMP session's identity: the player's game token. */
public record StompPrincipal(String token) implements Principal {

    @Override
    public String getName() {
        return token == null ? "anonymous" : token;
    }
}
