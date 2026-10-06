package com.generals.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.generals.domain.Board;
import com.generals.domain.Position;
import com.generals.domain.Rank;
import com.generals.service.GameManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Exercises the real STOMP-over-WebSocket path.
 *
 * <p>This exists because the per-user destination was silently broken: the broker was
 * registered for {@code /topic} only, so a push to {@code /user/{token}/queue/game/{id}}
 * was rewritten to {@code /queue/game/{id}} and then dropped on the floor. Every HTTP
 * test passed while both players' screens sat frozen, so the guarantee is pinned here.
 *
 * <p>The client is hand-rolled on the JDK WebSocket rather than
 * {@code WebSocketStompClient}: Tomcat's JSR-356 endpoint delivers messages on a pool of
 * threads, which is not safe for STOMP's partial-frame state, and Spring's
 * {@code JdkWebSocketClient} no longer exists in Spring 6.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameSocketIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_SECONDS = 10;
    private static final String NUL = "\u0000";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private GameManager gameManager;

    private final List<RawStompClient> clients = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clients.clear();
    }

    @AfterEach
    void tearDown() {
        clients.forEach(RawStompClient::close);
    }

    // ------------------------------------------------------------- stomp client

    /** Speaks just enough STOMP over a raw WebSocket to observe the pushes. */
    private static final class RawStompClient {

        private final WebSocket socket;
        private final String gameId;
        private final LinkedBlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
        private final LinkedBlockingQueue<JsonNode> errorInbox = new LinkedBlockingQueue<>();
        private final List<String> events = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean connected;

        private RawStompClient(WebSocket socket, String gameId) {
            this.socket = socket;
            this.gameId = gameId;
        }

        /** Connects, authenticates with the player token, and subscribes to its own queue. */
        static RawStompClient open(String url, String token, String gameId) throws Exception {
            RawStompClient[] holder = new RawStompClient[1];
            StringBuilder partial = new StringBuilder();

            WebSocket.Listener listener = new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                    webSocket.sendText(
                            "CONNECT\naccept-version:1.2\nheart-beat:0,0\ntoken:" + token + "\n\n" + NUL,
                            true);
                }

                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    partial.append(data);
                    if (last) {
                        String frame = partial.toString();
                        partial.setLength(0);
                        onFrame(webSocket, frame, holder[0]);
                    }
                    webSocket.request(1);
                    return null;
                }

                @Override
                public void onError(WebSocket webSocket, Throwable error) {
                    RawStompClient client = holder[0];
                    if (client != null) {
                        client.events.add("ERROR " + error);
                    }
                }
            };

            WebSocket socket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(URI.create(url), listener)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            RawStompClient client = new RawStompClient(socket, gameId);
            holder[0] = client;

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (System.nanoTime() < deadline && !client.connected) {
                Thread.sleep(20);
            }
            assertThat(client.connected)
                    .as("STOMP session connected; events=%s", client.events)
                    .isTrue();
            return client;
        }

        private static void onFrame(WebSocket webSocket, String raw, RawStompClient client) {
            if (client == null) {
                return;
            }
            // Spring terminates STOMP frames with a NUL byte
            String text = raw.endsWith(NUL) ? raw.substring(0, raw.length() - 1) : raw;
            int split = text.indexOf("\n\n");
            String head = split < 0 ? text : text.substring(0, split);
            String body = split < 0 ? "" : text.substring(split + 2);
            String command = head.split("\n")[0];

            if ("CONNECTED".equals(command)) {
                client.events.add("CONNECTED");
                client.connected = true;
                webSocket.sendText(
                        "SUBSCRIBE\nid:s0\ndestination:/user/queue/game/" + client.gameId + "\n\n" + NUL,
                        true);
                // the same subscription the browser client makes, so a rejected
                // action is observable instead of vanishing
                webSocket.sendText(
                        "SUBSCRIBE\nid:e0\ndestination:/user/queue/errors\n\n" + NUL, true);
            } else if ("MESSAGE".equals(command)) {
                boolean isError = "/user/queue/errors".equals(destinationOf(head));
                try {
                    (isError ? client.errorInbox : client.inbox).add(JSON.readTree(body));
                    client.events.add(isError ? "ERROR MESSAGE" : "MESSAGE");
                } catch (Exception e) {
                    client.events.add("PARSE FAILURE " + e);
                }
            }
        }

        private static String destinationOf(String head) {
            for (String line : head.split("\n")) {
                if (line.startsWith("destination:")) {
                    return line.substring("destination:".length()).trim();
                }
            }
            return "";
        }

        void send(String command, Map<String, String> headers, String body) {
            StringBuilder frame = new StringBuilder(command).append('\n');
            headers.forEach((key, value) -> frame.append(key).append(':').append(value).append('\n'));
            frame.append('\n').append(body).append(NUL);
            socket.sendText(frame.toString(), true);
        }

        /** Waits for the first push satisfying {@code want}, ignoring earlier ones. */
        JsonNode await(Predicate<JsonNode> want) throws InterruptedException {
            return awaitFrom(inbox, want);
        }

        /** Waits for a rejection on /user/queue/errors. */
        JsonNode awaitError() throws InterruptedException {
            return awaitFrom(errorInbox, n -> true);
        }

        private static JsonNode awaitFrom(LinkedBlockingQueue<JsonNode> source, Predicate<JsonNode> want)
                throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            List<String> statuses = new ArrayList<>();
            while (System.nanoTime() < deadline) {
                JsonNode next = source.poll(200, TimeUnit.MILLISECONDS);
                if (next == null) {
                    continue;
                }
                JsonNode status = next.get("status");
                statuses.add(status == null ? next.toString() : status.asText());
                if (want.test(next)) {
                    return next;
                }
            }
            throw new AssertionError("no matching push within " + TIMEOUT_SECONDS
                    + "s; saw " + statuses);
        }

        void close() {
            socket.abort();
        }
    }

    // ------------------------------------------------------------------ fixtures

    private record Seat(String gameId, String redToken, String blueToken) {}

    /** Creates a game and returns it with only RED seated, so a test can connect first. */
    private Seat waitingGame() {
        ResponseEntity<String> create = rest.postForEntity("/api/games", namedBody(), String.class);
        assertThat(create.getStatusCode().value()).isEqualTo(201);
        JsonNode created = read(create.getBody());
        return new Seat(created.get("gameId").asText(), created.get("token").asText(), null);
    }

    private Seat emptyGame() {
        Seat waiting = waitingGame();
        return new Seat(waiting.gameId(), waiting.redToken(), join(waiting.gameId()));
    }

    /** Seats BLUE over HTTP and returns its token. */
    private String join(String gameId) {
        ResponseEntity<String> join =
                rest.postForEntity("/api/games/" + gameId + "/join", namedBody(), String.class);
        assertThat(join.getStatusCode().is2xxSuccessful()).isTrue();
        return read(join.getBody()).get("token").asText();
    }

    /** Every seat is named now, so the HTTP fixtures have to name themselves. */
    private static HttpEntity<String> namedBody() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>("{\"name\":\"socket-test\"}", headers);
    }

    private static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + json, e);
        }
    }

    /** The standard army in roster order, so both sides deploy an identical shape. */
    private static List<Rank> roster() {
        return List.of(
                Rank.FIVE_STAR_GENERAL, Rank.FOUR_STAR_GENERAL, Rank.THREE_STAR_GENERAL,
                Rank.TWO_STAR_GENERAL, Rank.ONE_STAR_GENERAL, Rank.COLONEL,
                Rank.LIEUTENANT_COLONEL, Rank.MAJOR, Rank.CAPTAIN, Rank.FIRST_LIEUTENANT,
                Rank.SECOND_LIEUTENANT, Rank.SERGEANT,
                Rank.PRIVATE, Rank.PRIVATE, Rank.PRIVATE, Rank.PRIVATE, Rank.PRIVATE, Rank.PRIVATE,
                Rank.SPY, Rank.SPY, Rank.FLAG);
    }

    /** Fills the player's camp row by row, keeping all 21 pieces inside the three rows. */
    private void place(Seat seat, String token) {
        List<Map<String, Object>> pieces = new ArrayList<>();
        List<Rank> army = roster();
        int index = 0;
        for (int row = 0; row < Board.ROWS && index < army.size(); row++) {
            for (int col = 0; col < Board.COLS && index < army.size(); col++) {
                if (!new Position(row, col).isInOwnCamp(gameManager.colorOf(seat.gameId(), token))) {
                    continue;
                }
                pieces.add(Map.of("row", row, "col", col, "rank", army.get(index).name()));
                index++;
            }
        }
        assertThat(pieces).hasSize(21);

        ResponseEntity<String> response = rest.exchange(
                "/api/games/" + seat.gameId() + "/placement",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("pieces", pieces), jsonHeaders(token)),
                String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("placement: %s", response.getBody())
                .isTrue();
    }

    private void playBoth(Seat seat) {
        place(seat, seat.redToken());
        place(seat, seat.blueToken());
    }

    private HttpHeaders jsonHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Player-Token", token);
        return headers;
    }

    private RawStompClient connect(Seat seat, String token) throws Exception {
        RawStompClient client =
                RawStompClient.open("ws://localhost:" + port + "/ws/websocket", token, seat.gameId());
        clients.add(client);
        return client;
    }

    // -------------------------------------------------------------------- tests

    @Test
    @DisplayName("a player is pushed to their own /user/queue destination")
    void pushesReachThePerUserDestination() throws Exception {
        Seat seat = emptyGame();
        RawStompClient red = connect(seat, seat.redToken());
        RawStompClient blue = connect(seat, seat.blueToken());
        playBoth(seat);

        JsonNode redView = red.await(n -> "IN_PROGRESS".equals(n.get("status").asText()));
        JsonNode blueView = blue.await(n -> "IN_PROGRESS".equals(n.get("status").asText()));

        assertThat(redView.get("youAre").asText()).isEqualTo("RED");
        assertThat(blueView.get("youAre").asText()).isEqualTo("BLUE");
        assertThat(redView.get("currentPlayer").asText()).isEqualTo("RED");
        assertThat(redView.get("board")).hasSize(Board.ROWS * Board.COLS);
    }

    @Test
    @DisplayName("the pushed payload hides the opponent's unrevealed ranks")
    void pushedViewKeepsTheFogOfWar() throws Exception {
        Seat seat = emptyGame();
        RawStompClient red = connect(seat, seat.redToken());
        playBoth(seat);

        JsonNode push = red.await(n -> "IN_PROGRESS".equals(n.get("status").asText()));

        int ownVisible = 0;
        int enemyHidden = 0;
        for (JsonNode square : push.get("board")) {
            if (square.get("pieceId").isNull()) {
                continue;
            }
            boolean mine = "RED".equals(square.get("owner").asText());
            if (mine) {
                ownVisible++;
                assertThat(square.get("rank").isNull())
                        .as("own piece at %s must show its rank", square.get("label"))
                        .isFalse();
            } else {
                enemyHidden++;
                assertThat(square.get("rank").isNull())
                        .as("enemy piece at %s must stay hidden", square.get("label"))
                        .isTrue();
                assertThat(square.get("revealed").asBoolean()).isFalse();
            }
        }
        assertThat(ownVisible).isEqualTo(21);
        assertThat(enemyHidden).isEqualTo(21);
    }

    @Test
    @DisplayName("the pushed view carries the clock, and a fresh one after each move")
    void pushedViewCarriesTheClock() throws Exception {
        Seat seat = emptyGame();
        RawStompClient blue = connect(seat, seat.blueToken());
        playBoth(seat);

        JsonNode started = blue.await(n -> "IN_PROGRESS".equals(n.get("status").asText()));
        assertThat(started.get("turnSeconds").asInt()).isEqualTo(60);
        assertThat(started.get("turnDeadlineMillis").isNumber()).isTrue();
        long first = started.get("turnDeadlineMillis").asLong();
        assertThat(first).isGreaterThan(System.currentTimeMillis());

        rest.exchange("/api/games/" + seat.gameId() + "/move", HttpMethod.POST,
                new HttpEntity<>(
                        "{\"from\":{\"row\":2,\"col\":0},\"to\":{\"row\":3,\"col\":0}}",
                        jsonHeaders(seat.redToken())),
                String.class);

        JsonNode afterMove = blue.await(n -> n.get("turnNumber").asInt() > 0);
        assertThat(afterMove.get("youAre").asText()).isEqualTo("BLUE");
        assertThat(afterMove.get("turnDeadlineMillis").asLong())
                .as("BLUE's turn is timed from when it began, not from RED's")
                .isGreaterThan(first);
    }

    @Test
    @DisplayName("an opponent's move pushes a fresh view to the waiting player")
    void movesArePushedToTheOpponent() throws Exception {
        Seat seat = emptyGame();
        RawStompClient blue = connect(seat, seat.blueToken());
        playBoth(seat);
        blue.await(n -> "IN_PROGRESS".equals(n.get("status").asText()));

        // red's front row is row 2; row 3 is the empty centre lane
        ResponseEntity<String> move = rest.exchange(
                "/api/games/" + seat.gameId() + "/move",
                HttpMethod.POST,
                new HttpEntity<>(
                        "{\"from\":{\"row\":2,\"col\":0},\"to\":{\"row\":3,\"col\":0}}",
                        jsonHeaders(seat.redToken())),
                String.class);
        assertThat(move.getStatusCode().is2xxSuccessful())
                .as("move: %s", move.getBody())
                .isTrue();
        assertThat(read(move.getBody()).get("turnNumber").asInt()).isPositive();

        // the waiting player is pushed the new board without polling
        JsonNode pushed = blue.await(n -> n.get("turnNumber").asInt() > 0);
        assertThat(pushed.get("youAre").asText()).isEqualTo("BLUE");
        assertThat(pushed.get("status").asText()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("a rejected action comes back on /user/queue/errors, to the sender only")
    void rejectedActionsReachTheSenderOnly() throws Exception {
        Seat seat = emptyGame();
        RawStompClient red = connect(seat, seat.redToken());
        RawStompClient blue = connect(seat, seat.blueToken());
        playBoth(seat);
        red.await(n -> "IN_PROGRESS".equals(n.get("status").asText()));
        blue.await(n -> "IN_PROGRESS".equals(n.get("status").asText()));

        // BLUE tries to move during RED's turn
        String illegal = "{\"from\":{\"row\":5,\"col\":0},\"to\":{\"row\":4,\"col\":0}}";
        blue.send("SEND",
                Map.of("destination", "/app/game/" + seat.gameId() + "/move"),
                illegal);

        JsonNode error = blue.awaitError();
        String message = error.get("message").asText();
        assertThat(message).contains("not BLUE's turn");
        assertThat(error.get("timestamp").asText()).isNotBlank();

        // the same illegal move over HTTP must explain itself identically, or the
        // player sees one rule on one transport and a different one on the other
        ResponseEntity<String> overHttp = rest.exchange(
                "/api/games/" + seat.gameId() + "/move",
                HttpMethod.POST,
                new HttpEntity<>(illegal, jsonHeaders(seat.blueToken())),
                String.class);
        assertThat(overHttp.getStatusCode().value()).isEqualTo(400);
        assertThat(read(overHttp.getBody()).get("message").asText()).isEqualTo(message);

        // the innocent player must not be told about someone else's mistake
        Thread.sleep(300);
        assertThat(red.errorInbox)
                .as("errors are @SendToUser, so they reach the offender alone")
                .isEmpty();
    }

    @Test
    @DisplayName("chat sent over the socket reaches the other player's queue")
    void chatSentOverTheSocketIsPushedToTheOpponent() throws Exception {
        Seat seat = emptyGame();
        RawStompClient red = connect(seat, seat.redToken());
        RawStompClient blue = connect(seat, seat.blueToken());
        playBoth(seat);
        red.await(n -> "IN_PROGRESS".equals(n.get("status").asText()));
        blue.await(n -> "IN_PROGRESS".equals(n.get("status").asText()));

        red.send("SEND",
                Map.of("destination", "/app/game/" + seat.gameId() + "/chat"),
                "{\"text\":\"  watch  your  flag  \"}");

        JsonNode pushed = blue.await(n -> !n.get("chat").isEmpty());
        JsonNode line = pushed.get("chat").get(0);
        assertThat(line.get("text").asText()).isEqualTo("watch your flag");
        assertThat(line.get("color").asText()).isEqualTo("RED");
        assertThat(line.get("author").asText()).isEqualTo("socket-test");
        assertThat(line.get("at").asText()).isNotBlank();

        // A refused line comes back on the errors queue, like any other rejected action,
        // and is not recorded for the other player to read.
        red.send("SEND",
                Map.of("destination", "/app/game/" + seat.gameId() + "/chat"),
                "{\"text\":\"   \"}");
        assertThat(red.awaitError().get("message").asText()).contains("say something");
        Thread.sleep(300);
        assertThat(blue.inbox)
                .as("a refused line is never broadcast")
                .allSatisfy(view -> assertThat(view.get("chat").size()).isEqualTo(1));
    }

    @Test
    @DisplayName("the creator is pushed into placement when someone joins their game")
    void joiningPushesTheCreatorOutOfTheWaitingRoom() throws Exception {
        Seat waiting = waitingGame();
        // The creator connects first and is sitting on the waiting-room screen.
        RawStompClient red = connect(waiting, waiting.redToken());
        Thread.sleep(300);
        assertThat(red.inbox).as("connecting alone pushes nothing").isEmpty();

        join(waiting.gameId());

        // Only the joiner learns the outcome from the HTTP body, so without a push the
        // creator waits for an opponent who has already arrived.
        JsonNode push = red.await(n -> "PLACEMENT".equals(n.get("status").asText()));
        assertThat(push.get("youAre").asText()).isEqualTo("RED");
    }
}
