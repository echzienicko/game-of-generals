package com.generals.api;

import com.generals.api.dto.GameStateDto;
import com.generals.api.dto.Requests;
import com.generals.domain.BotDifficulty;
import com.generals.domain.Position;
import com.generals.domain.Rank;
import com.generals.service.GameBroadcaster;
import com.generals.service.GameManager;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The HTTP surface of the game.
 *
 * <p>Every call carries the player's token in {@code X-Player-Token}. The server derives
 * the side from it, so a client can never claim to be the other player.
 *
 * <p>These endpoints are enough to play a whole game without a WebSocket; the socket
 * simply pushes the same views as they change.
 */
@RestController
@RequestMapping("/api/games")
public class GameController {

    public static final String TOKEN_HEADER = "X-Player-Token";

    private final GameManager gameManager;
    private final GameBroadcaster broadcaster;

    public GameController(GameManager gameManager, GameBroadcaster broadcaster) {
        this.gameManager = gameManager;
        this.broadcaster = broadcaster;
    }

    @PostMapping
    public ResponseEntity<GameManager.JoinResult> create(@RequestBody Requests.NameRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(gameManager.create(request.name()));
    }

    @PostMapping("/{gameId}/join")
    public GameManager.JoinResult join(@PathVariable String gameId,
                                       @RequestBody Requests.NameRequest request) {
        GameManager.JoinResult result = gameManager.join(gameId, request.name());
        // Joining flips the game into the placement phase, so the creator has to be told:
        // they are sitting on the waiting-room screen and cannot know otherwise. Only the
        // joiner learns the outcome from the response body, hence the broadcast.
        broadcaster.broadcast(gameManager.require(gameId));
        return result;
    }

    /** Pairs you with a waiting opponent, or creates a fresh game if the lobby is empty. */
    @PostMapping("/matchmake")
    public ResponseEntity<?> matchmake(@RequestBody Requests.NameRequest request) {
        GameManager.MatchResult match = gameManager.matchmake(request.name());
        if (match == null) {
            return ResponseEntity.status(HttpStatus.CREATED).body(gameManager.create(request.name()));
        }
        return ResponseEntity.ok(match);
    }


    /**
     * Creates a game against the computer and returns the caller's own seat.
     *
     * <p>201 for the same reason {@link #create()} is: a game was made, not joined.
     */
    @PostMapping("/vs-bot")
    public ResponseEntity<GameManager.JoinResult> versusBot(
            @RequestBody Requests.NameRequest request,
            @RequestParam(name = "difficulty", required = false) String difficulty) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(gameManager.createAgainstBot(BotDifficulty.parse(difficulty), request.name()));
    }

    @GetMapping("/{gameId}")
    public GameStateDto state(
            @PathVariable String gameId,
            @RequestHeader(TOKEN_HEADER) String token) {
        return gameManager.state(gameId, token);
    }

    @PostMapping("/{gameId}/placement")
    public GameStateDto placement(
            @PathVariable String gameId,
            @RequestHeader(TOKEN_HEADER) String token,
            @Valid @RequestBody Requests.PlacementRequest request) {
        GameStateDto view = gameManager.submitPlacement(gameId, token, toDeployment(request));
        broadcaster.broadcast(gameManager.require(gameId, token));
        return view;
    }

    @PostMapping("/{gameId}/move")
    public GameStateDto move(
            @PathVariable String gameId,
            @RequestHeader(TOKEN_HEADER) String token,
            @Valid @RequestBody Requests.MoveRequest request) {
        GameStateDto view = gameManager.move(gameId, token,
                position(request.from()), position(request.to()));
        broadcaster.broadcast(gameManager.require(gameId, token));
        return view;
    }

    /**
     * Says something to the other player.
     *
     * <p>Answers with the sender's refreshed view and broadcasts, so the opponent sees the
     * line without asking for it. Chat rides the same per-player queue as the board, which
     * is why the broadcast is here and not inside the manager: the manager does not know
     * how views are pushed.
     */
    @PostMapping("/{gameId}/chat")
    public GameStateDto chat(@PathVariable String gameId,
                             @RequestHeader(TOKEN_HEADER) String token,
                             @RequestBody Requests.ChatRequest request) {
        GameStateDto view = gameManager.chat(gameId, token, request.text());
        broadcaster.broadcast(gameManager.require(gameId, token));
        return view;
    }

    private Map<Position, Rank> toDeployment(Requests.PlacementRequest request) {
        Map<Position, Rank> deployment = new LinkedHashMap<>();
        for (Requests.DeploymentEntry entry : request.pieces()) {
            Rank rank = parseRank(entry.rank());
            Position position = new Position(entry.row(), entry.col());
            Rank previous = deployment.put(position, rank);
            if (previous != null) {
                throw new IllegalArgumentException("two pieces at " + position.label());
            }
        }
        return deployment;
    }

    private Rank parseRank(String name) {
        try {
            return Rank.valueOf(name);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown rank: " + name);
        }
    }

    private Position position(Requests.Coordinate coordinate) {
        return new Position(coordinate.row(), coordinate.col());
    }
}
