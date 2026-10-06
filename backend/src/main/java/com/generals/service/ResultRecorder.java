package com.generals.service;

import com.generals.domain.PlayerColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Puts a finished game's result into the {@link Leaderboard}, once.
 *
 * <p>This exists as its own component because a game is watched for its end from four
 * places: {@link GameManager} sees the human's turns (and {@code GameManager.resign}, which
 * ends a game without a move at all), {@link BotOpponent} sees the
 * computer's (and the computer mutates {@link com.generals.domain.Game} directly rather
 * than through {@code GameManager.move}), and {@link TurnClock} sees whichever turn ran
 * out of time. All of them call in here, and {@link GameSession#claimResultFiling()}
 * decides which of them actually scores it — so exactly-once is one piece of logic
 * instead of four agreeing by convention. A fifth watcher would not be a fifth copy of
 * that logic; it would be a fifth caller, and that is the whole point of this class.
 *
 * <p>Callers are expected to hold the game's monitor when they invoke this, since the
 * check reads the status and the claim reads a flag.
 */
@Component
public class ResultRecorder {

    private static final Logger log = LoggerFactory.getLogger(ResultRecorder.class);

    private final Leaderboard leaderboard;

    public ResultRecorder(Leaderboard leaderboard) {
        this.leaderboard = leaderboard;
    }

    /**
     * Files the result if {@code session}'s game is finished and has not been filed yet.
     *
     * @param wasOver the status before the mutation that led here, so a finished game
     *                asked to move again cannot score a second time
     */
    public void fileIfFinished(GameSession session, boolean wasOver) {
        if (wasOver || !session.game().isOver() || !session.claimResultFiling()) {
            return;
        }
        PlayerColor winner = session.game().winner();
        if (winner == null) {
            return;
        }
        leaderboard.recordResult(session.nameOf(winner), session.nameOf(winner.opponent()));
        log.info("game {}: recorded a win for {}", session.game().id(), session.nameOf(winner));
    }

    /** Files the result of a game that has already finished, ignoring the prior status. */
    public void fileFinished(GameSession session) {
        fileIfFinished(session, false);
    }
}
