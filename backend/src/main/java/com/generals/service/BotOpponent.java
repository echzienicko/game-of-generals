package com.generals.service;

import com.generals.domain.BotBrain;
import com.generals.domain.BotDifficulty;
import com.generals.domain.Game;
import com.generals.domain.OpeningMemory;
import com.generals.domain.PlayerColor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Plays the computer's side of a game.
 *
 * <p>The bot never sleeps in a request thread. {@link GameManager} calls
 * {@link #onChange} after every change to a game, and if it is now the bot's turn this
 * schedules the move a short moment later and returns. The delay is the point: a reply that
 * arrived in the same response as the human's own move would be correct but would not look
 * like a game, and the client would have two moves to process at once.
 *
 * <p>Everything the bot does goes through the same {@link Game} API a human's move does, so
 * there is no second implementation of the rules to drift out of step.
 */
@Service
public class BotOpponent {

    private static final Logger log = LoggerFactory.getLogger(BotOpponent.class);

    private final GameBroadcaster broadcaster;
    private final BotBrain brain;
    private final OpeningMemory memory;
    private final TurnClock clock;
    private final ResultRecorder results;
    private final long thinkMillis;
    private final ScheduledExecutorService scheduler;

    /** The bot's own move sequence per game, so it can recognise an opening it has lost. */
    private final Map<String, List<String>> lines = new ConcurrentHashMap<>();

    public BotOpponent(GameBroadcaster broadcaster,
                       BotBrain brain,
                       OpeningMemory memory,
                       TurnClock clock,
                       ResultRecorder results,
                       @Value("${generals.bot.think-millis:550}") long thinkMillis) {
        this.broadcaster = broadcaster;
        this.brain = brain;
        this.memory = memory;
        this.clock = clock;
        this.results = results;
        this.thinkMillis = thinkMillis;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemon("generals-bot"));
    }

    /**
     * Nudges the bot if a change to {@code session} means it is its turn.
     *
     * <p>Called after every mutation, by both the REST and the socket path, so the bot is
     * driven no matter which one the human used.
     */
    public void onChange(GameSession session) {
        PlayerColor bot = session.botColor();
        if (bot == null) {
            return;
        }
        BotDifficulty difficulty = session.botFor(bot).orElse(null);
        if (difficulty == null) {
            return;
        }
        if (!isBotsTurn(session.game(), bot)) {
            return;
        }
        scheduler.schedule(() -> act(session, bot, difficulty), thinkMillis, TimeUnit.MILLISECONDS);
    }

    private boolean isBotsTurn(Game game, PlayerColor bot) {
        synchronized (game) {
            if (game.isOver()) {
                return false;
            }
            return switch (game.status()) {
                case PLACEMENT -> !game.hasPlaced(bot);
                case IN_PROGRESS -> game.currentPlayer() == bot;
                case WAITING_FOR_OPPONENT, FINISHED -> false;
            };
        }
    }

    /** The bot's turn, performed synchronously. Runs on the scheduler thread. */
    void act(GameSession session, PlayerColor bot, BotDifficulty difficulty) {
        Game game = session.game();
        List<String> line = lines.computeIfAbsent(game.id(), id -> new ArrayList<>());
        synchronized (game) {
            if (game.isOver()) {
                return;
            }
            switch (game.status()) {
                case PLACEMENT -> {
                    if (game.hasPlaced(bot)) {
                        return;
                    }
                    game.submitPlacement(bot, brain.deploy(bot));
                    log.info("game {}: computer deployed {}", game.id(), bot);
                }
                case IN_PROGRESS -> {
                    if (game.currentPlayer() != bot) {
                        return;
                    }
                    BotBrain.Move move = brain.choose(game, bot, difficulty, List.copyOf(line), memory);
                    game.move(bot, move.from(), move.to());
                    line.add(move.signature());
                    log.info("game {}: computer played {}", game.id(), move);
                }
                default -> {
                    return;
                }
            }
        }
        if (game.isOver()) {
            recordOutcome(game, bot, line);
            results.fileFinished(session);
            lines.remove(game.id());
        }
        // The turn has passed back to a person, so their clock starts now — the clock is
        // deliberately not run on the bot's own turn.
        clock.onChange(session);
        broadcaster.broadcast(session);
    }

    /**
     * Teaches the bot from a game it has just lost.
     *
     * <p>Only losses are worth remembering. A win says the opening works, and the bot is
     * already rewarded for winning the same way it is for any good move.
     */
    private void recordOutcome(Game game, PlayerColor bot, List<String> line) {
        if (game.winner() == null || game.winner() == bot) {
            return;
        }
        if (line.isEmpty()) {
            return;
        }
        memory.recordLoss(List.copyOf(line));
        log.info("game {}: recorded a losing opening of {} move(s)", game.id(), line.size());
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private static ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}