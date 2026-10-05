package com.generals.service;

import com.generals.api.GameViewMapper;
import com.generals.api.dto.GameStateDto;
import com.generals.domain.BotDifficulty;
import com.generals.domain.Game;
import com.generals.domain.PlayerColor;
import com.generals.domain.Position;
import com.generals.domain.Rank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of live games, plus matchmaking.
 *
 * <p>State is deliberately not persisted — restarting the server clears the lobby, which
 * is acceptable for a first playable version. All mutations of a given game are
 * serialized on the {@link Game} instance so two players can never interleave a move.
 */
@Service
public class GameManager {

    private static final Logger log = LoggerFactory.getLogger(GameManager.class);

    private final Map<String, GameSession> games = new ConcurrentHashMap<>();
    private final GameViewMapper viewMapper;
    private final BotOpponent botOpponent;
    private final ResultRecorder results;

    public GameManager(GameViewMapper viewMapper, BotOpponent botOpponent, ResultRecorder results) {
        this.viewMapper = viewMapper;
        this.botOpponent = botOpponent;
        this.results = results;
    }

    /** What a client needs after creating or joining a game. */
    public record JoinResult(String gameId, String token, PlayerColor youAre) {
    }

    /** Creates a game with RED seated and returns their player token. */
    public JoinResult create(String playerName) {
        String id = shortId();
        Game game = new Game(id);
        GameSession session = new GameSession(game, viewMapper);
        games.put(id, session);
        String token = session.seatPlayer(PlayerColor.RED, playerName);
        log.info("game {} created", id);
        return new JoinResult(id, token, PlayerColor.RED);
    }

    /**
     * Creates a game against the computer: RED for the caller, BLUE for the bot.
     *
     * <p>Both sides are seated and the deployment phase is open before this returns, so the
     * caller's first view is already the placement screen and the bot can never be beaten to
     * the phase by a slower request path.
     */
    public JoinResult createAgainstBot(BotDifficulty difficulty, String playerName) {
        String id = shortId();
        Game game = new Game(id);
        GameSession session = new GameSession(game, viewMapper);
        games.put(id, session);
        String redToken = session.seatPlayer(PlayerColor.RED, playerName);
        session.seatBot(PlayerColor.BLUE, difficulty);
        game.beginPlacement();
        log.info("game {} created against the computer ({})", id, difficulty);
        botOpponent.onChange(session);
        return new JoinResult(id, redToken, PlayerColor.RED);
    }

    /** Seats the second player, starts the secret deployment phase, and returns their token. */
    public JoinResult join(String gameId, String playerName) {
        GameSession session = require(gameId);
        synchronized (session.game()) {
            if (session.isFull()) {
                throw new GameAccessException("game " + gameId + " already has two players");
            }
            String token = session.seatPlayer(PlayerColor.BLUE, playerName);
            session.game().beginPlacement();
            log.info("game {} joined by blue", gameId);
            return new JoinResult(gameId, token, PlayerColor.BLUE);
        }
    }

    public GameSession require(String gameId) {
        GameSession session = games.get(gameId);
        if (session == null) {
            throw new GameNotFoundException(gameId);
        }
        return session;
    }

    public GameSession require(String gameId, String token) {
        GameSession session = require(gameId);
        if (session.colorOf(token).isEmpty()) {
            throw new GameAccessException("this token does not belong to game " + gameId);
        }
        return session;
    }

    public PlayerColor colorOf(String gameId, String token) {
        return require(gameId, token).colorOf(token)
                .orElseThrow(() -> new GameAccessException("unknown player token"));
    }

    public GameStateDto state(String gameId, String token) {
        return require(gameId, token).viewFor(colorOf(gameId, token));
    }

    /** Submits a secret deployment and returns the player's own refreshed view. */
    public GameStateDto submitPlacement(String gameId, String token, Map<Position, Rank> deployment) {
        GameSession session = require(gameId, token);
        PlayerColor color = session.colorOf(token)
                .orElseThrow(() -> new GameAccessException("unknown player token"));
        synchronized (session.game()) {
            // A game cannot end during deployment, so there is no result to file here.
            session.game().submitPlacement(color, deployment);
        }
        GameStateDto view = session.viewFor(color);
        botOpponent.onChange(session);
        return view;
    }

    /** Plays a turn and returns the mover's refreshed view. */
    public GameStateDto move(String gameId, String token, Position from, Position to) {
        GameSession session = require(gameId, token);
        PlayerColor color = session.colorOf(token)
                .orElseThrow(() -> new GameAccessException("unknown player token"));
        synchronized (session.game()) {
            boolean wasOver = session.game().isOver();
            session.game().move(color, from, to);
            results.fileIfFinished(session, wasOver);
        }
        GameStateDto view = session.viewFor(color);
        botOpponent.onChange(session);
        return view;
    }

    /**
     * Records a line of chat and returns the sender's refreshed view.
     *
     * <p>Any phase is allowed, waiting room included: the two people who are about to place
     * their pieces are exactly the ones who want to say "where are you putting your flag?"
     * The computer has no name and so no voice, which {@code say} refuses rather than
     * filing an anonymous line on its behalf.
     */
    public GameStateDto chat(String gameId, String token, String text) {
        GameSession session = require(gameId, token);
        PlayerColor color = session.colorOf(token)
                .orElseThrow(() -> new GameAccessException("unknown player token"));
        session.say(color, text);
        return session.viewFor(color);
    }

    /**
     * Pairs a newcomer with whoever is already waiting, returning null if nobody is.
     * Both players' tokens are returned so the caller can hand each one to the right client.
     */
    public MatchResult matchmake(String playerName) {
        for (GameSession waiting : games.values()) {
            if (waiting.game().status() == Game.Status.WAITING_FOR_OPPONENT && !waiting.isFull()) {
                JoinResult blue = join(waiting.game().id(), playerName);
                return new MatchResult(waiting.game().id(),
                        waiting.tokenOf(PlayerColor.RED), PlayerColor.RED,
                        blue.token(), PlayerColor.BLUE);
            }
        }
        return null;
    }

    /** Both sides of a matched pair, so the server can hand each client its own token. */
    public record MatchResult(String gameId,
                              String redToken, PlayerColor red,
                              String blueToken, PlayerColor blue) {
    }

    private String shortId() {
        String id;
        do {
            id = UUID.randomUUID().toString().substring(0, 6);
        } while (games.containsKey(id));
        return id;
    }

    public int activeGames() {
        return games.size();
    }
}
