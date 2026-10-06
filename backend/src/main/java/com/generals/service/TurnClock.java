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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Runs a clock on each turn and plays a move for whoever lets it expire.
 *
 * <p>The clock is a server thing, not a client thing. A browser cannot be trusted to notice
 * that its turn ended, and a browser that is not open at all cannot notice anything — so if
 * the deadline lived in the client, a player who closed the tab would freeze the game for
 * both of them forever. Instead every change to a game calls {@link #onChange}, which either
 * arms the clock for the player who now has the move or takes it away again.
 *
 * <p>The move it plays is {@link BotDifficulty#RANDOM}: any legal move, chosen uniformly.
 * That is the honest choice. The alternative — the heuristic the computer uses — would mean
 * a player's own pieces playing better for them every time they think too long, which turns
 * a forgotten tab into an advantage instead of a punishment. A random move is legal, so the
 * game always continues, and it is marked in the log as a clock move for both players.
 *
 * <p>Like {@link BotOpponent} this never sleeps in a request thread: the expiry is scheduled
 * and the callback returns, so a player waiting out a clock is not holding a connection.
 */
@Service
public class TurnClock {

    private static final Logger log = LoggerFactory.getLogger(TurnClock.class);

    private final BotBrain brain;
    private final ResultRecorder results;
    private final GameBroadcaster broadcaster;
    private final long turnMillis;

    private final ScheduledExecutorService scheduler;

    /** The task pending for each game, so a move cancels the clock it was on. */
    private final Map<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    /**
     * @param turnSeconds how long a player has per move; zero or less turns the clock off,
     *                    which is worth having both for a deliberate untimed game and so
     *                    tests do not have to race one
     */
    public TurnClock(BotBrain brain,
                     ResultRecorder results,
                     GameBroadcaster broadcaster,
                     @Value("${generals.turn-seconds:60}") long turnSeconds) {
        this.brain = brain;
        this.results = results;
        this.broadcaster = broadcaster;
        this.turnMillis = turnSeconds * 1000L;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemon("generals-clock"));
    }

    /** Whether moves are timed at all. Sent to the client so it can hide a clock that is off. */
    public boolean isEnabled() {
        return turnMillis > 0;
    }

    /** How long a player gets per move, in seconds, or 0 when the clock is off. */
    public long turnSeconds() {
        return isEnabled() ? turnMillis / 1000L : 0L;
    }

    /**
     * Re-arms the clock for {@code session} after any change to its game.
     *
     * <p>Called wherever {@link BotOpponent#onChange} is called, and by the bot itself once
     * it has moved, because those are exactly the moments when it becomes somebody's turn.
     * Anything else — a finished game, the deployment phase, the computer's turn — clears the
     * clock instead: the computer is not timed ({@link BotOpponent} answers for it), and a
     * clock running on a game that cannot be moved is worse than none.
     */
    public void onChange(GameSession session) {
        Game game = session.game();
        PlayerColor onClock = null;
        long deadline = 0L;
        synchronized (game) {
            if (isEnabled() && game.status() == Game.Status.IN_PROGRESS && !game.isOver()) {
                PlayerColor current = game.currentPlayer();
                if (session.botFor(current).isEmpty()) {
                    onClock = current;
                    deadline = System.currentTimeMillis() + turnMillis;
                }
            }
            if (onClock == null) {
                session.clearTurnClock();
            } else {
                session.armTurnClock(deadline, turnSeconds());
            }
        }
        cancel(session.game().id());
        if (onClock == null) {
            return;
        }
        PlayerColor timed = onClock;
        long due = deadline;
        pending.put(game.id(), scheduler.schedule(
                () -> fire(session, timed, due), turnMillis, TimeUnit.MILLISECONDS));
        log.debug("game {}: {} has {}ms to move", game.id(), timed, turnMillis);
    }

    /**
     * Plays a random legal move for {@code color} because their clock ran out.
     *
     * <p>Runs on the scheduler thread. Every check is repeated here even though {@link
     * #onChange} cancels the pending task: the move that arrived a moment before the expiry
     * could already have handed the turn on, and a clock that fires anyway would move the
     * wrong player's piece.
     */
    void fire(GameSession session, PlayerColor color, long deadline) {
        Game game = session.game();
        boolean moved = false;
        synchronized (game) {
            if (!isEnabled() || game.isOver() || game.status() != Game.Status.IN_PROGRESS) {
                return;
            }
            if (game.currentPlayer() != color) {
                // the turn moved on while this task waited its turn on the scheduler
                return;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining > 0) {
                // woken early; try again when it is actually due
                pending.put(game.id(), scheduler.schedule(
                        () -> fire(session, color, deadline), remaining, TimeUnit.MILLISECONDS));
                return;
            }
            List<BotBrain.Move> legal = brain.legalMoves(game, color);
            if (legal.isEmpty()) {
                // The rules say a player always has a legal move. If that is ever untrue the
                // right answer is a quiet clock, not an exception on a scheduler thread.
                log.warn("game {}: {} has no legal move to fall back on", game.id(), color);
                session.clearTurnClock();
                return;
            }
            BotBrain.Move move = brain.choose(game, color, BotDifficulty.RANDOM, List.of(),
                    OpeningMemory.none());
            game.moveByClock(color, move.from(), move.to());
            moved = true;
            log.info("game {}: {} ran out of time, {} moved to {}", game.id(), color,
                    move.signature(), move.to().label());
            results.fileFinished(session);
            // The move handed the turn on, so the next clock is armed for the opponent (or
            // cleared, if this ended the game) before anybody is told about it.
            onChange(session);
        }
        if (moved) {
            broadcaster.broadcast(session);
        }
    }

    /** Drops any pending expiry for a game. Safe to call when there is none. */
    public void cancel(String gameId) {
        ScheduledFuture<?> task = pending.remove(gameId);
        if (task != null) {
            task.cancel(false);
        }
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