package com.generals.service;

import com.generals.api.GameViewMapper;
import com.generals.api.dto.ChatMessageDto;
import com.generals.api.dto.GameStateDto;
import com.generals.api.dto.SeatDto;
import com.generals.domain.BotDifficulty;
import com.generals.domain.Game;
import com.generals.domain.PlayerColor;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One match plus the two player tokens that identify its participants.
 *
 * <p>Tokens are the whole of the identity system for now: a client sends its token and
 * the server works out which side it is playing. That is enough to enforce fog of war,
 * because every view is built for a specific {@link PlayerColor}.
 *
 * <p>A seat may be held by the computer rather than a person. The bot still gets a token —
 * which is what keeps the rest of the code uniform, since views and broadcasts are all
 * addressed by token — but that token never leaves the server, and {@link #botColor()} marks
 * the seat so nothing tries to push state to a listener that does not exist.
 */
public class GameSession {

    /** How many lines of chat a game keeps before the oldest go. */
    private static final int MAX_CHAT_MESSAGES = 100;

    private final Game game;
    private final GameViewMapper viewMapper;
    private final Map<PlayerColor, String> tokens = new ConcurrentHashMap<>();
    private final Map<PlayerColor, BotDifficulty> bots = new ConcurrentHashMap<>();

    /** The name each human chose, for the leaderboard. Absent for the computer's seat. */
    private final Map<PlayerColor, String> names = new ConcurrentHashMap<>();

    /**
     * What the players have said to each other, oldest first and capped.
     *
     * <p>Chat lives and dies with the game, like everything else here: a restart empties
     * the board, so keeping lines from a board that no longer exists would only be
     * confusing. The cap exists because the log grows without bound otherwise, and a
     * hundred lines is more than anyone rereads mid-game.
     */
    private final Deque<ChatMessageDto> chat = new ArrayDeque<>();

    private final AtomicLong nextChatId = new AtomicLong();

    /**
     * Whether this game's result has already been filed with the {@link Leaderboard}.
     *
     * <p>A game can be watched for its end from more than one place — {@link GameManager}
     * sees human turns, {@link BotOpponent} sees the computer's — so the transition to
     * FINISHED is observed twice. This claim makes filing the result exactly-once a
     * property of the session rather than something each observer has to get right.
     */
    private final AtomicBoolean resultFiled = new AtomicBoolean();

    public GameSession(Game game, GameViewMapper viewMapper) {
        this.game = game;
        this.viewMapper = viewMapper;
    }

    public Game game() {
        return game;
    }

    public String newToken(PlayerColor color) {
        String token = UUID.randomUUID().toString();
        tokens.put(color, token);
        return token;
    }

    /**
     * Seats a human on {@code color} and remembers the name they will be recorded under.
     *
     * <p>A name is not optional here: every seat a person holds is named, so the ledger has
     * no anonymous human rows and the client cannot end up playing a game whose win would
     * silently not count.
     */
    public String seatPlayer(PlayerColor color, String name) {
        String tidied = Leaderboard.requireValid(name);
        names.put(color, tidied);
        return newToken(color);
    }

    /** Seats the computer on {@code color}. Returns the token it will play under. */
    public String seatBot(PlayerColor color, BotDifficulty difficulty) {
        String token = newToken(color);
        bots.put(color, difficulty);
        return token;
    }

    /** The name recorded against a seat, or null when the computer is playing it. */
    public String nameOf(PlayerColor color) {
        return names.get(color);
    }

    // ------------------------------------------------------------------ chat

    /** Long enough for a sentence, short enough that nobody can write an essay in the box. */
    public static final int MAX_CHAT_LENGTH = 200;

    /**
     * Records something a player said and returns the stored message.
     *
     * <p>The author is read from the seat rather than taken from the caller, so a client
     * cannot put words in somebody else's mouth, and only a seated human may speak at all.
     *
     * @throws IllegalArgumentException if the text is blank, too long, or holds characters
     *                                  that have no business in a chat box
     */
    public ChatMessageDto say(PlayerColor color, String text) {
        String said = requireMessage(text);
        String author = nameOf(color);
        if (author == null) {
            throw new IllegalArgumentException("the computer does not chat");
        }
        // Synchronised on the game like every other mutation, so a line lands in the same
        // order the players' moves do rather than interleaving with them.
        synchronized (game) {
            ChatMessageDto message = new ChatMessageDto(
                    nextChatId.incrementAndGet(), color.name(), author, said, Instant.now().toString());
            chat.addLast(message);
            while (chat.size() > MAX_CHAT_MESSAGES) {
                chat.removeFirst();
            }
            return message;
        }
    }

    /** The conversation so far, oldest first. Safe to hand to a client as-is. */
    public List<ChatMessageDto> chat() {
        synchronized (game) {
            return List.copyOf(chat);
        }
    }

    /**
     * Tidies a line of chat, or refuses it.
     *
     * <p>Whitespace inside a message is collapsed rather than kept: a pasted paragraph or a
     * newline would otherwise be a way to make the panel unreadable for the other player,
     * and a chat box that scrolls off its own screen is nobody's idea of banter.
     */
    static String requireMessage(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("say something, or leave the box alone");
        }
        String tidied = text.strip().replaceAll("\\s+", " ");
        if (tidied.length() > MAX_CHAT_LENGTH) {
            throw new IllegalArgumentException("keep it under " + MAX_CHAT_LENGTH + " characters");
        }
        if (tidied.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("that message has characters we cannot show");
        }
        return tidied;
    }

    /** Both seats as the viewer may see them, RED first. */
    public List<SeatDto> seatsFor(PlayerColor viewer) {
        List<SeatDto> seats = new ArrayList<>(PlayerColor.values().length);
        for (PlayerColor color : PlayerColor.values()) {
            seats.add(new SeatDto(
                    color.name(), nameOf(color), botFor(color).isPresent(), color == viewer));
        }
        return seats;
    }

    public Optional<BotDifficulty> botFor(PlayerColor color) {
        return Optional.ofNullable(bots.get(color));
    }

    /** The side the computer is playing, or null when both seats are human. */
    public PlayerColor botColor() {
        return bots.keySet().stream().findFirst().orElse(null);
    }

    /** Whether the opponent of {@code viewer} is the computer. */
    public boolean isAgainstBot(PlayerColor viewer) {
        PlayerColor bot = botColor();
        return bot != null && bot != viewer;
    }

    public Optional<PlayerColor> colorOf(String token) {
        if (token == null) {
            return Optional.empty();
        }
        return tokens.entrySet().stream()
                .filter(entry -> entry.getValue().equals(token))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    public boolean isFull() {
        return tokens.size() >= 2;
    }

    /**
     * Returns true exactly once for the lifetime of this game — the first caller to file
     * its result with the leaderboard wins the claim, everyone else is told no.
     */
    public boolean claimResultFiling() {
        return resultFiled.compareAndSet(false, true);
    }

    /** Whether the result has already been filed. For tests and assertions. */
    public boolean resultFiled() {
        return resultFiled.get();
    }

    public boolean hasJoined(PlayerColor color) {
        return tokens.containsKey(color);
    }

    public String tokenOf(PlayerColor color) {
        return tokens.get(color);
    }

    /** The sanitized state for one player. */
    public GameStateDto viewFor(PlayerColor color) {
        synchronized (game) {
            // `this` rather than a pile of parameters: the view now needs the seat names
            // and the chat log as well as the board, and all of it lives here. That is not
            // a wiring cycle — the mapper is a plain component that was handed this session
            // by whoever holds one, not injected with one.
            return viewMapper.toDto(game, this, color);
        }
    }

    public GameStateDto viewFor(String token) {
        return viewFor(colorOf(token).orElseThrow(
                () -> new GameAccessException("unknown player token")));
    }
}
