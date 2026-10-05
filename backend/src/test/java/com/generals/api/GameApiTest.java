package com.generals.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.generals.domain.ArmyFactory;
import com.generals.domain.Rank;
import com.generals.service.Leaderboard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests over HTTP, focused on the two things that are easy to get wrong:
 * that the two players stay isolated, and that hidden enemy ranks never leave the server.
 */
@SpringBootTest
@AutoConfigureMockMvc
// a ledger of its own, under target/, so the tests never touch a real player's score
@TestPropertySource(properties = "generals.leaderboard.path=target/test-leaderboard.json")
class GameApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private Leaderboard leaderboard;

    private static final String TOKEN = GameController.TOKEN_HEADER;

    private record Seat(String gameId, String token, String name) {
    }

    // ------------------------------------------------------------------ helpers

    /** The body every seat-creating endpoint now insists on. */
    private static String nameJson(String name) {
        return "{\"name\":\"" + name + "\"}";
    }

    private static int nameCounter = 0;

    private Seat createGame() throws Exception {
        return createGameAs("Red" + (++nameCounter));
    }

    private Seat createGameAs(String name) throws Exception {
        String body = mvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nameJson(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        return new Seat(node.get("gameId").asText(), node.get("token").asText(), name);
    }

    private Seat joinGame(String gameId) throws Exception {
        return joinGameAs(gameId, "Blue" + (++nameCounter));
    }

    private Seat joinGameAs(String gameId, String name) throws Exception {
        String body = mvc.perform(post("/api/games/{id}/join", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nameJson(name)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Seat(gameId, json.readTree(body).get("token").asText(), name);
    }

    /** A legal 21-piece deployment: the first 21 squares of the player's own camp. */
    private String placementJson(String color) throws Exception {
        boolean red = color.equals("RED");
        int from = red ? 0 : 5;
        // the twelve officers, then the two spies, the flag and the six privates
        List<Rank> ordered = new ArrayList<>(List.of(Rank.values()).stream()
                .filter(rank -> rank.isOfficer())
                .toList());
        ordered.add(Rank.SPY);
        ordered.add(Rank.SPY);
        ordered.add(Rank.FLAG);
        for (int i = 0; i < 6; i++) {
            ordered.add(Rank.PRIVATE);
        }
        assertEquals(ArmyFactory.ARMY_SIZE, ordered.size());

        List<Map<String, Object>> pieces = new ArrayList<>();
        int index = 0;
        for (int row = from; row < from + 3 && index < ArmyFactory.ARMY_SIZE; row++) {
            for (int col = 0; col < 9 && index < ArmyFactory.ARMY_SIZE; col++) {
                pieces.add(Map.of("row", row, "col", col, "rank", ordered.get(index++).name()));
            }
        }
        assertEquals(ArmyFactory.ARMY_SIZE, pieces.size());
        return json.writeValueAsString(Map.of("pieces", pieces));
    }

    private void place(Seat seat, String color) throws Exception {
        mvc.perform(post("/api/games/{id}/placement", seat.gameId())
                        .header(TOKEN, seat.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placementJson(color)))
                .andExpect(status().isOk());
    }

    /** Deploys both armies and asserts the match is now live. */
    private void placeBoth(Seat red, Seat blue) throws Exception {
        place(red, "RED");
        place(blue, "BLUE");
        mvc.perform(get("/api/games/{id}", red.gameId()).header(TOKEN, red.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    private JsonNode state(Seat seat) throws Exception {
        MvcResult result = mvc.perform(get("/api/games/{id}", seat.gameId())
                        .header(TOKEN, seat.token()))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void move(Seat seat, int fromRow, int fromCol, int toRow, int toCol) throws Exception {
        mvc.perform(post("/api/games/{id}/move", seat.gameId())
                        .header(TOKEN, seat.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "from", Map.of("row", fromRow, "col", fromCol),
                                "to", Map.of("row", toRow, "col", toCol)))))
                .andExpect(status().isOk());
    }

    private JsonNode squareAt(JsonNode state, int row, int col) {
        for (JsonNode square : state.get("board")) {
            if (square.get("row").asInt() == row && square.get("col").asInt() == col) {
                return square;
            }
        }
        throw new AssertionError("no square at " + row + "," + col);
    }

    // -------------------------------------------------------------------- tests

    @Test
    @DisplayName("a game can be created, joined and played to completion")
    void fullGameFlow() throws Exception {
        Seat red = createGame();
        assertEquals(8, state(red).get("rows").asInt());
        assertEquals(9, state(red).get("cols").asInt());
        assertEquals("WAITING_FOR_OPPONENT", state(red).get("status").asText());

        Seat blue = joinGame(red.gameId());
        assertEquals("PLACEMENT", state(red).get("status").asText());

        placeBoth(red, blue);
        assertEquals("RED", state(red).get("currentPlayer").asText());

        // red walks a private out of the camp
        move(red, 2, 0, 3, 0);
        assertEquals("BLUE", state(red).get("currentPlayer").asText());
        assertEquals(1, state(red).get("turnNumber").asInt());
    }

    @Test
    @DisplayName("enemy ranks are never sent to the opponent before a battle")
    void hiddenEnemyRanksAreRedacted() throws Exception {
        Seat red = createGame();
        Seat blue = joinGame(red.gameId());
        placeBoth(red, blue);

        JsonNode redView = state(red);
        JsonNode blueView = state(blue);

        // blue's camp is rows 5..7
        for (int row = 5; row <= 7; row++) {
            for (int col = 0; col < 9; col++) {
                JsonNode square = squareAt(redView, row, col);
                if (square.get("pieceId").isNull()) {
                    continue;
                }
                assertEquals("BLUE", square.get("owner").asText());
                assertTrue(square.get("rank").isNull(),
                        "an unrevealed enemy piece must not carry its rank at " + row + "," + col);
                assertTrue(square.get("rankName").isNull(),
                        "an unrevealed enemy piece must not carry its label at " + row + "," + col);
                assertFalse(square.get("revealed").asBoolean());
            }
        }

        // but blue can always see its own pieces
        JsonNode ownPiece = squareAt(blueView, 5, 0);
        assertNotNull(ownPiece.get("rank").asText());
        assertTrue(ownPiece.get("revealed").asBoolean());

        // and red can always see its own
        assertNotNull(squareAt(redView, 0, 0).get("rank").asText());
    }

    @Test
    @DisplayName("a battle exposes neither side's rank to the opponent")
    void battleRedactsRanks() throws Exception {
        Seat red = createGame();
        Seat blue = joinGame(red.gameId());
        placeBoth(red, blue);

        // The deployment helper fills the camp in rank order, so for RED the square
        // (2,0) holds a Private, and for BLUE the square (5,0) holds the 5-Star General.
        // Walk the private up the board and let it charge into the general.
        move(red, 2, 0, 3, 0);
        move(blue, 5, 8, 4, 8);
        move(red, 3, 0, 4, 0);
        move(blue, 4, 8, 3, 8);
        move(red, 4, 0, 5, 0); // private attacks the 5-star and is destroyed

        JsonNode redView = state(red);

        // The general won the fight and still shows face-down: who holds a square is
        // public, what it is is not.
        JsonNode contested = squareAt(redView, 5, 0);
        assertEquals("BLUE", contested.get("owner").asText());
        assertFalse(contested.get("revealed").asBoolean(), "fighting does not expose a piece");
        assertTrue(contested.get("rank").isNull(), "the winner's rank stays hidden");
        assertTrue(contested.get("rankName").isNull(), "and so does its name");

        // the private that charged is gone
        assertTrue(squareAt(redView, 4, 0).get("pieceId").isNull(), "the loser is removed");

        // everything else in blue's camp is still hidden from red
        for (int row = 5; row <= 7; row++) {
            for (int col = 0; col < 9; col++) {
                if (row == 5 && col == 0) {
                    continue; // the square that fought
                }
                JsonNode square = squareAt(redView, row, col);
                if (square.get("pieceId").isNull()) {
                    continue;
                }
                assertEquals("BLUE", square.get("owner").asText());
                assertTrue(square.get("rank").isNull(),
                        "untouched enemy pieces must stay hidden at " + row + "," + col);
            }
        }

        // The battle itself is reported so the client can animate it. Red sees the outcome
        // and the rank of its own piece, but the defender's rank is redacted and the
        // description names no ranks at all.
        JsonNode battle = redView.get("lastBattle");
        assertNotNull(battle);
        assertFalse(battle.get("attackerSurvives").asBoolean());
        assertTrue(battle.get("defenderSurvives").asBoolean());
        assertEquals("PRIVATE", battle.get("attackerRank").asText(), "red knows its own piece");
        assertTrue(battle.get("defenderRank").isNull(), "but not the piece that beat it");
        assertTrue(battle.get("defenderRankName").isNull());
        String description = battle.get("description").asText();
        assertFalse(description.contains("5-Star"), "the description must not name ranks: " + description);
        assertFalse(description.contains("Private"), "the description must not name ranks: " + description);
    }

    @Test
    @DisplayName("the move log does not name ranks that never fought")
    void moveLogIsRedacted() throws Exception {
        Seat red = createGame();
        Seat blue = joinGame(red.gameId());
        placeBoth(red, blue);

        move(red, 2, 0, 3, 0);
        move(blue, 5, 8, 4, 8);

        // blue walks a piece; red must not learn which rank it was
        String lastLineForRed = state(red).get("log").get(1).asText();
        assertTrue(lastLineForRed.contains("BLUE"), lastLineForRed);
        assertTrue(lastLineForRed.contains("hidden piece"), lastLineForRed);

        // blue itself sees its own piece named
        String lastLineForBlue = state(blue).get("log").get(1).asText();
        assertFalse(lastLineForBlue.contains("hidden piece"), lastLineForBlue);
    }

    @Test
    @DisplayName("a token from another game is rejected")
    void foreignTokenRejected() throws Exception {
        Seat gameA = createGame();
        Seat gameB = createGame();

        mvc.perform(get("/api/games/{id}", gameA.gameId()).header(TOKEN, gameB.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a missing token is rejected")
    void missingTokenRejected() throws Exception {
        Seat red = createGame();
        mvc.perform(get("/api/games/{id}", red.gameId()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an unknown game id returns 404")
    void unknownGameReturns404() throws Exception {
        Seat red = createGame();
        mvc.perform(get("/api/games/{id}", "nope00").header(TOKEN, red.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("moving out of turn is rejected with 400")
    void outOfTurnRejected() throws Exception {
        Seat red = createGame();
        Seat blue = joinGame(red.gameId());
        placeBoth(red, blue);

        mvc.perform(post("/api/games/{id}/move", red.gameId())
                        .header(TOKEN, blue.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "from", Map.of("row", 5, "col", 0),
                                "to", Map.of("row", 4, "col", 0)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a deployment with the wrong number of pieces is rejected")
    void badDeploymentRejected() throws Exception {
        Seat red = createGame();
        joinGame(red.gameId());

        mvc.perform(post("/api/games/{id}/placement", red.gameId())
                        .header(TOKEN, red.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("pieces",
                                List.of(Map.of("row", 0, "col", 0, "rank", "FLAG"))))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a third player cannot join a full game")
    void cannotJoinFullGame() throws Exception {
        Seat red = createGame();
        joinGame(red.gameId());
        mvc.perform(post("/api/games/{id}/join", red.gameId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nameJson("Third" + (++nameCounter))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a live game reports no winner yet")
    void noWinnerBeforeGameEnds() throws Exception {
        Seat red = createGame();
        Seat blue = joinGame(red.gameId());
        placeBoth(red, blue);
        assertEquals("IN_PROGRESS", state(red).get("status").asText());
        assertTrue(state(red).get("winner").isNull());
        assertTrue(state(red).get("winReason").isNull());
    }

    // ------------------------------------------------------------------ names

    @Test
    @DisplayName("a game cannot be created without a name")
    void createRequiresAName() throws Exception {
        mvc.perform(post("/api/games"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("tell us your name before you play"));
        mvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nameJson("x".repeat(Leaderboard.MAX_NAME_LENGTH + 1))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a game cannot be joined, matched or played against the bot without a name")
    void everySeatNeedsAName() throws Exception {
        Seat red = createGame();
        mvc.perform(post("/api/games/{id}/join", red.gameId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/games/matchmake"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/games/vs-bot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a named game against the bot comes with the bot already seated")
    void versusBotAcceptsAName() throws Exception {
        mvc.perform(post("/api/games/vs-bot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nameJson("Nick")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.youAre").value("RED"))
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    // ------------------------------------------------------------------- chat

    private JsonNode say(Seat seat, String text, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(post("/api/games/{id}/chat", seat.gameId())
                        .header(TOKEN, seat.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("text", text))))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return result.getResponse().getContentAsString().isBlank()
                ? null
                : json.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("both players see what the other one said")
    void chatIsShared() throws Exception {
        Seat red = createGameAs("ChattyRed" + (++nameCounter));
        Seat blue = joinGameAs(red.gameId(), "ChattyBlue" + nameCounter);

        JsonNode reply = say(red, "  good luck  ", 200);
        assertEquals("good luck", reply.get("chat").get(0).get("text").asText(),
                "the message is tidied on the way in");
        assertEquals("ChattyRed" + nameCounter, reply.get("chat").get(0).get("author").asText());

        // blue sees it, and can answer
        assertEquals(1, state(blue).get("chat").size());
        say(blue, "you too", 200);
        assertEquals(2, state(red).get("chat").size());
        assertEquals("you too", state(red).get("chat").get(1).get("text").asText());
        assertEquals("BLUE", state(red).get("chat").get(1).get("color").asText());
    }

    @Test
    @DisplayName("a message is filed under the seat's name, whatever the sender claims")
    void chatAuthorComesFromTheSeat() throws Exception {
        Seat red = createGame();
        joinGame(red.gameId());

        MvcResult result = mvc.perform(post("/api/games/{id}/chat", red.gameId())
                        .header(TOKEN, red.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello\",\"author\":\"Someone Else\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode chat = json.readTree(result.getResponse().getContentAsString()).get("chat");
        assertEquals(red.name(), chat.get(0).get("author").asText(),
                "the client does not get to say who spoke");
    }

    @Test
    @DisplayName("chat can be had before anyone deploys")
    void chatWorksInTheWaitingRoom() throws Exception {
        Seat red = createGame();
        say(red, "still there?", 200);
        assertEquals("still there?", state(red).get("chat").get(0).get("text").asText());
    }

    @Test
    @DisplayName("empty, endless and unreadable chat is refused with a readable reason")
    void chatIsValidated() throws Exception {
        Seat red = createGame();
        joinGame(red.gameId());

        say(red, "   ", 400);
        say(red, "x".repeat(201), 400);

        JsonNode reply = say(red, "fine", 200);
        assertEquals(1, reply.get("chat").size(), "refused lines are not recorded");
    }

    @Test
    @DisplayName("only a player of this game can talk into it")
    void chatNeedsAPlayerToken() throws Exception {
        Seat red = createGame();
        joinGame(red.gameId());

        mvc.perform(post("/api/games/{id}/chat", red.gameId())
                        .header(TOKEN, "not-a-real-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello?\"}"))
                .andExpect(status().isForbidden());
        assertEquals(0, state(red).get("chat").size());
    }

    @Test
    @DisplayName("the game keeps the last hundred lines and drops the rest")
    void chatIsCapped() throws Exception {
        Seat red = createGame();
        joinGame(red.gameId());

        for (int i = 0; i < 105; i++) {
            say(red, "line " + i, 200);
        }
        JsonNode chat = state(red).get("chat");
        assertEquals(100, chat.size());
        assertEquals("line 5", chat.get(0).get("text").asText(), "the oldest lines go first");
        assertEquals("line 104", chat.get(99).get("text").asText());
    }

    // ------------------------------------------------------------------- seats

    @Test
    @DisplayName("both seats are named, and each player is told which is theirs")
    void seatsCarryNames() throws Exception {
        Seat red = createGameAs("SeatRed" + (++nameCounter));
        Seat blue = joinGameAs(red.gameId(), "SeatBlue" + nameCounter);

        JsonNode redSeats = state(red).get("seats");
        assertEquals(2, redSeats.size());
        assertEquals("RED", redSeats.get(0).get("color").asText());
        assertTrue(redSeats.get(0).get("you").asBoolean());
        assertEquals(red.name(), redSeats.get(0).get("name").asText());
        assertEquals(blue.name(), redSeats.get(1).get("name").asText());
        assertFalse(redSeats.get(1).get("you").asBoolean());

        JsonNode blueSeats = state(blue).get("seats");
        assertFalse(blueSeats.get(0).get("you").asBoolean());
        assertTrue(blueSeats.get(1).get("you").asBoolean());
    }

    @Test
    @DisplayName("the computer's seat is marked as such and has no name")
    void botSeatHasNoName() throws Exception {
        MvcResult result = mvc.perform(post("/api/games/vs-bot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nameJson("Solo" + (++nameCounter))))
                .andExpect(status().isCreated())
                .andReturn();
        String gameId = json.readTree(result.getResponse().getContentAsString()).get("gameId").asText();
        String token = json.readTree(result.getResponse().getContentAsString()).get("token").asText();

        MvcResult view = mvc.perform(get("/api/games/{id}", gameId).header(TOKEN, token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode seats = json.readTree(view.getResponse().getContentAsString()).get("seats");
        assertTrue(seats.get(1).get("bot").asBoolean());
        assertTrue(seats.get(1).get("name").isNull(), "nothing is filed for the computer");
        assertFalse(seats.get(0).get("bot").asBoolean());
    }

    // ------------------------------------------------------------- leaderboard

    /**
     * A player's wins as the endpoint reports them.
     *
     * <p>The ledger is a real file, so a second run of this class finds the first run's
     * scores already there. Everything below is therefore stated relative to what is
     * already on the table rather than as an absolute count.
     */
    private int winsOnLeaderboard(String name) throws Exception {
        String body = mvc.perform(get("/api/leaderboard").param("limit", "500"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode entry : json.readTree(body).get("entries")) {
            if (entry.get("name").asText().equalsIgnoreCase(name)) {
                return entry.get("wins").asInt();
            }
        }
        return 0;
    }

    @Test
    @DisplayName("the leaderboard reports what has been recorded, for both players")
    void leaderboardListsWins() throws Exception {
        String name = "ApiTester" + (++nameCounter);
        String opponent = "ApiTesterOpponent" + nameCounter;
        int before = winsOnLeaderboard(name);
        int theirLossesBefore = lossesOnLeaderboard(opponent);

        leaderboard.recordResult(name, opponent);

        assertEquals(before + 1, winsOnLeaderboard(name));
        assertEquals(theirLossesBefore + 1, lossesOnLeaderboard(opponent));
        assertTrue(winsOnLeaderboard(name) >= 1);
    }

    private int lossesOnLeaderboard(String name) throws Exception {
        String body = mvc.perform(get("/api/leaderboard").param("limit", "500"))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode entry : json.readTree(body).get("entries")) {
            if (entry.get("name").asText().equalsIgnoreCase(name)) {
                return entry.get("losses").asInt();
            }
        }
        return 0;
    }

    @Test
    @DisplayName("each row reports its games played and its win rate")
    void leaderboardReportsWinRate() throws Exception {
        String name = "RateTester" + (++nameCounter);
        // The ledger under target/ is a real file, so this row may already carry a history
        // from an earlier run: everything is stated as a change, not as an absolute.
        JsonNode before = entryFor(boardBody(), name);
        int wins = before == null ? 0 : before.get("wins").asInt();
        int losses = before == null ? 0 : before.get("losses").asInt();

        leaderboard.recordResult(name, "RateOpponent");
        leaderboard.recordResult(name, "RateOpponent");
        leaderboard.recordResult("RateOpponent", name);

        JsonNode mine = entryFor(boardBody(), name);
        assertNotNull(mine, "a row of its own once it has won something: " + name);
        assertEquals(wins + 2, mine.get("wins").asInt());
        assertEquals(losses + 1, mine.get("losses").asInt());
        assertEquals(wins + losses + 3, mine.get("gamesPlayed").asInt(),
                "games played is the denominator the rate is taken over");
        assertEquals(round(wins + 2, wins + losses + 3), mine.get("winRatePercent").asInt(),
                "the rate the client shows must be the one the server computed");
    }

    /** Win rate as the server computes it: a whole percentage of games played, rounded. */
    private static int round(int wins, int played) {
        return (int) Math.round(wins * 100.0 / played);
    }

    private String boardBody() throws Exception {
        return mvc.perform(get("/api/leaderboard").param("limit", "500"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("the whole table is served, not a page of it")
    void leaderboardServesEveryone() throws Exception {
        for (int i = 0; i < 60; i++) {
            leaderboard.recordResult("Everyone" + (++nameCounter), "EveryoneOpponent");
        }
        String body = mvc.perform(get("/api/leaderboard"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<JsonNode> entries = new ArrayList<>();
        json.readTree(body).get("entries").forEach(entries::add);

        assertTrue(entries.size() > 50,
                "no limit means every player, not the first 50: got " + entries.size());
        assertEquals(entries.size(), json.readTree(body).get("totalPlayers").asInt());
        assertTrue(entries.stream().anyMatch(e -> e.get("name").asText().startsWith("Everyone")));
    }

    @Test
    @DisplayName("a limited request says how many players it left out")
    void leaderboardSaysWhatItLeftOut() throws Exception {
        for (int i = 0; i < 4; i++) {
            leaderboard.recordResult("Sliced" + (++nameCounter), "SlicedOpponent");
        }
        String body = mvc.perform(get("/api/leaderboard").param("limit", "2"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(2, json.readTree(body).get("entries").size());
        assertTrue(json.readTree(body).get("totalPlayers").asInt() > 2,
                "the count is the whole ledger, not the slice");
    }

    /** The leaderboard row for one name, or null if it has never finished a game. */
    private JsonNode entryFor(String body, String name) throws Exception {
        for (JsonNode entry : json.readTree(body).get("entries")) {
            if (entry.get("name").asText().equalsIgnoreCase(name)) {
                return entry;
            }
        }
        return null;
    }

    @Test
    @DisplayName("the leaderboard is best first, and the list is capped")
    void leaderboardOrdersAndCaps() throws Exception {
        for (int i = 0; i < 4; i++) {
            leaderboard.recordResult("Cap" + (++nameCounter), "CapOpponent");
        }
        String body = mvc.perform(get("/api/leaderboard").param("limit", "500"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<JsonNode> entries = new java.util.ArrayList<>();
        json.readTree(body).get("entries").forEach(entries::add);
        assertTrue(entries.size() >= 4);
        for (int i = 1; i < entries.size(); i++) {
            assertTrue(entries.get(i - 1).get("wins").asInt() >= entries.get(i).get("wins").asInt(),
                    "wins must not increase down the table");
        }

        mvc.perform(get("/api/leaderboard").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2));
        mvc.perform(get("/api/leaderboard").param("limit", "-5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1));
    }

    @Test
    @DisplayName("the leaderboard path is served by the single-page app like the others")
    void leaderboardPathServesTheApp() throws Exception {
        mvc.perform(get("/leaderboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").doesNotExist());
    }

    @Test
    @DisplayName("metadata describes the army and the board")
    void metaEndpoint() throws Exception {
        mvc.perform(get("/api/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows").value(8))
                .andExpect(jsonPath("$.cols").value(9))
                .andExpect(jsonPath("$.armySize").value(21))
                .andExpect(jsonPath("$.roster.PRIVATE").value(6))
                .andExpect(jsonPath("$.roster.SPY").value(2))
                .andExpect(jsonPath("$.roster.FLAG").value(1))
                .andExpect(jsonPath("$.ranks.length()").value(15));
    }
}
