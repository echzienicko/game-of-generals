package com.generals.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over SockJS.
 *
 * <p>There is deliberately no shared {@code /topic} destination for game state. Both
 * players subscribe to their own {@code /user/queue/game/{id}} destination, so the server
 * can send each of them a differently-redacted view of the same board. A single shared
 * topic would necessarily leak the opponent's hidden pieces to whoever received it.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Bean
    public StompAuthInterceptor stompAuthInterceptor() {
        return new StompAuthInterceptor();
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(org.springframework.messaging.simp.config.MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        // /queue must be brokered too: a per-user push to /user/{token}/queue/game/{id}
        // is rewritten to /queue/game/{id} before it reaches the broker, and a broker
        // registered for /topic alone would silently drop it.
        registry.enableSimpleBroker("/queue", "/topic");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthInterceptor());
    }
}
