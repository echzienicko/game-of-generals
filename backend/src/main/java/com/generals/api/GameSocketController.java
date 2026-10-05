package com.generals.api;

import com.generals.api.dto.GameStateDto;
import com.generals.api.dto.Requests;
import com.generals.config.StompPrincipal;
import com.generals.domain.Position;
import com.generals.domain.Rank;
import com.generals.service.GameBroadcaster;
import com.generals.service.GameManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The real-time surface of the game.
 *
 * <p>A client connects to {@code /ws} with its token in the STOMP {@code token} header,
 * subscribes to {@code /user/queue/game/{gameId}} for pushes, and sends turns to
 * {@code /app/game/{gameId}/move}.
 *
 * <p>Handlers reply with the mover's own view, and every mutation also triggers a
 * broadcast so the opponent updates without polling. Illegal actions come back on
 * {@code /user/queue/errors} instead of vanishing.
 */
@Controller
public class GameSocketController {

    private static final Logger log = LoggerFactory.getLogger(GameSocketController.class);

    private final GameManager gameManager;
    private final GameBroadcaster broadcaster;

    public GameSocketController(GameManager gameManager, GameBroadcaster broadcaster) {
        this.gameManager = gameManager;
        this.broadcaster = broadcaster;
    }

    public record SocketError(String message, String timestamp) {
    }

    @MessageMapping("/game/{gameId}/state")
    public GameStateDto state(@DestinationVariable String gameId, Principal principal) {
        return gameManager.state(gameId, token(principal));
    }

    @MessageMapping("/game/{gameId}/placement")
    public GameStateDto placement(@DestinationVariable String gameId,
                                  Principal principal,
                                  @Payload Requests.PlacementRequest request) {
        String token = token(principal);
        GameStateDto view = gameManager.submitPlacement(gameId, token, toDeployment(request));
        broadcaster.broadcast(gameManager.require(gameId, token));
        return view;
    }

    @MessageMapping("/game/{gameId}/move")
    public GameStateDto move(@DestinationVariable String gameId,
                             Principal principal,
                             @Payload Requests.MoveRequest request) {
        String token = token(principal);
        GameStateDto view = gameManager.move(gameId, token,
                position(request.from()), position(request.to()));
        broadcaster.broadcast(gameManager.require(gameId, token));
        return view;
    }

    /**
     * Chat over the socket, for a client whose socket is up.
     *
     * <p>The client posts over HTTP in the normal case, because a refused line is easier to
     * read as an HTTP error than as a STOMP frame. This exists so a connected client is
     * not forced onto a second connection for a single message.
     */
    @MessageMapping("/game/{gameId}/chat")
    public GameStateDto chat(@DestinationVariable String gameId,
                             Principal principal,
                             @Payload Requests.ChatRequest request) {
        String token = token(principal);
        GameStateDto view = gameManager.chat(gameId, token, request.text());
        broadcaster.broadcast(gameManager.require(gameId, token));
        return view;
    }

    /** A rejected action is reported to the sender only, so the opponent is not spammed. */
    @MessageExceptionHandler
    @SendToUser("/queue/errors")
    public SocketError handle(RuntimeException ex) {
        log.debug("rejected socket action: {}", ex.getMessage());
        return new SocketError(
                ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
                Instant.now().toString());
    }

    private String token(Principal principal) {
        if (principal instanceof StompPrincipal stomp && stomp.token() != null) {
            return stomp.token();
        }
        throw new IllegalStateException("connect with a 'token' header first");
    }

    private Map<Position, Rank> toDeployment(Requests.PlacementRequest request) {
        Map<Position, Rank> deployment = new LinkedHashMap<>();
        for (Requests.DeploymentEntry entry : request.pieces()) {
            deployment.put(new Position(entry.row(), entry.col()), Rank.valueOf(entry.rank()));
        }
        return deployment;
    }

    private Position position(Requests.Coordinate coordinate) {
        return new Position(coordinate.row(), coordinate.col());
    }
}
