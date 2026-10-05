package com.generals.config;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

/**
 * Lifts the player token out of the STOMP {@code CONNECT} headers and attaches it as the
 * session {@link Principal}, so message handlers can tell the two players apart.
 */
public class StompAuthInterceptor implements ChannelInterceptor {

    public static final String TOKEN_HEADER = "token";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            accessor.setUser(new StompPrincipal(accessor.getFirstNativeHeader(TOKEN_HEADER)));
        }
        return message;
    }
}
