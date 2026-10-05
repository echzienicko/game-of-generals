package com.generals.service;

import com.generals.api.dto.GameStateDto;
import com.generals.domain.PlayerColor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Pushes game state to each player on their own user destination.
 *
 * <p>One board, two views. Sending the same payload to both would defeat the entire point
 * of hidden deployment, so every broadcast is built per {@link PlayerColor}.
 */
@Service
public class GameBroadcaster {

    public static final String DESTINATION = "/queue/game/";

    private final SimpMessagingTemplate template;

    public GameBroadcaster(SimpMessagingTemplate template) {
        this.template = template;
    }

    public void broadcast(GameSession session) {
        for (PlayerColor color : PlayerColor.values()) {
            String token = session.tokenOf(color);
            if (token != null) {
                push(token, session);
            }
        }
    }

    public void push(String token, GameSession session) {
        GameStateDto view = session.viewFor(token);
        template.convertAndSendToUser(token, DESTINATION + session.game().id(), view);
    }
}
