package com.generals.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaderboardTest {

    private static Leaderboard fresh(Path dir) {
        return new Leaderboard(new ObjectMapper(), dir.resolve("leaderboard.json").toString());
    }

    private static Leaderboard.Entry row(Leaderboard board, String name) {
        return board.top(1000).stream()
                .filter(e -> e.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + name + " in " + board.top(1000)));
    }

    // ------------------------------------------------------------------ names

    @Test
    @DisplayName("a name is tidied rather than rejected for ordinary sloppiness")
    void nameIsTidied() {
        assertEquals("Nick", Leaderboard.requireValid("  Nick  "));
        assertEquals("Major General", Leaderboard.requireValid("Major   General"));
        assertEquals("a b", Leaderboard.requireValid(" a\t b "));
    }

    @Test
    @DisplayName("a blank name is refused with a message worth reading")
    void blankNameRefused() {
        assertEquals("tell us your name before you play",
                assertThrows(IllegalArgumentException.class,
                        () -> Leaderboard.requireValid("   ")).getMessage());
        assertThrows(IllegalArgumentException.class, () -> Leaderboard.requireValid(null));
    }

    @Test
    @DisplayName("a name longer than the column is refused")
    void overlongNameRefused() {
        String tooLong = "x".repeat(Leaderboard.MAX_NAME_LENGTH + 1);
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> Leaderboard.requireValid(tooLong));
        assertTrue(ex.getMessage().contains(String.valueOf(Leaderboard.MAX_NAME_LENGTH)));
        assertEquals(tooLong.substring(1), Leaderboard.requireValid(tooLong.substring(1)));
    }

    @Test
    @DisplayName("a name typed across two lines is one name, not a broken one")
    void lineBreaksAreTidiedAway() {
        // a break inside a name becomes a space; only the ends are stripped
        assertEquals("Ni ck", Leaderboard.requireValid("Ni\nck"));
        assertEquals("Nick", Leaderboard.requireValid("Nick\r\n"));
        assertEquals("Major General", Leaderboard.requireValid("Major\t\n  General"));
        assertEquals("Nick", Leaderboard.requireValid("\u000B" + "Nick" + "\u000C"));
    }

    @Test
    @DisplayName("control characters that are not whitespace are refused, since a name is shown")
    void controlCharactersRefused() {
        // built from char values so the source carries no invisible bytes of its own;
        // String.valueOf, because "x" + (char) 7 would append the integer 7 instead
        assertThrows(IllegalArgumentException.class,
                () -> Leaderboard.requireValid("Nick" + String.valueOf((char) 7)));
        assertThrows(IllegalArgumentException.class,
                () -> Leaderboard.requireValid(String.valueOf((char) 0) + "Nick"));
        assertThrows(IllegalArgumentException.class,
                () -> Leaderboard.requireValid("Nick" + String.valueOf((char) 27) + "[31m"));
    }

    @Test
    @DisplayName("the key is stable however the name was typed")
    void keyIsStable() {
        assertEquals("nick", Leaderboard.key(" Nick  "));
        assertEquals("nick", Leaderboard.key("NICK"));
        assertEquals("", Leaderboard.key(null));
        assertEquals("", Leaderboard.key("   "));
    }

    // ------------------------------------------------------------------ tallying

    @Test
    @DisplayName("a win and a loss both land on the right rows")
    void winAndLoss(@TempDir Path dir) {
        Leaderboard board = fresh(dir);
        board.recordResult("Nick", "Alex");

        assertEquals(2, board.size());
        assertEquals(1, row(board, "Nick").wins());
        assertEquals(0, row(board, "Nick").losses());
        assertEquals(1, row(board, "Nick").streak());
        assertEquals(1, row(board, "Alex").losses());
        assertEquals(0, row(board, "Alex").wins());
        assertEquals(0, row(board, "Alex").streak());
    }

    @Test
    @DisplayName("the same name again tops up the same row rather than adding another")
    void sameNameTopsUp(@TempDir Path dir) {
        Leaderboard board = fresh(dir);
        board.recordResult("Nick", "Alex");
        board.recordResult("Nick", "Alex");

        assertEquals(2, board.size());
        assertEquals(2, row(board, "Nick").wins());
        assertEquals(2, row(board, "Nick").streak());
        assertEquals(2, row(board, "Nick").bestStreak());
        assertEquals(2, row(board, "Alex").losses());
    }

    @Test
    @DisplayName("casing and stray spaces do not make a second player out of one person")
    void keyIsCaseInsensitiveAndWhitespaceCollapsed(@TempDir Path dir) {
        Leaderboard board = fresh(dir);
        board.recordResult("Nick", "Alex");
        board.recordResult("  NICK ", "ALEX");

        assertEquals(2, board.size());
        assertEquals(2, row(board, "Nick").wins());
        assertEquals(2, row(board, "Alex").losses());
        assertEquals("Nick", row(board, "Nick").name());
        assertEquals("Alex", row(board, "Alex").name());
    }

    @Test
    @DisplayName("a streak resets on a loss but the best one is remembered")
    void streaks(@TempDir Path dir) {
        Leaderboard board = fresh(dir);
        board.recordResult("Nick", "Alex");
        board.recordResult("Nick", "Alex");
        board.recordResult("Nick", "Alex");
        board.recordResult("Alex", "Nick");
        board.recordResult("Nick", "Alex");

        assertEquals(4, row(board, "Nick").wins());
        assertEquals(1, row(board, "Nick").losses());
        assertEquals(1, row(board, "Nick").streak());
        assertEquals(3, row(board, "Nick").bestStreak());
        assertEquals(1, row(board, "Alex").wins());
        assertEquals(0, row(board, "Alex").streak());
        assertEquals(1, row(board, "Alex").bestStreak());
    }

    @Test
    @DisplayName("the computer has no name, so neither its wins nor its losses make a row")
    void botSeatIsAnonymous(@TempDir Path dir) {
        Leaderboard board = fresh(dir);

        board.recordResult("Nick", null);
        assertEquals(1, board.size());
        assertEquals(1, row(board, "Nick").wins());

        board.recordResult(null, "Nick");
        assertEquals(1, board.size());
        assertEquals(1, row(board, "Nick").losses());
    }

    @Test
    @DisplayName("beating yourself does not score a win and a loss in the same breath")
    void selfPlayIsNotDoubleCounted(@TempDir Path dir) {
        Leaderboard board = fresh(dir);
        board.recordResult("Nick", "nick");

        assertEquals(1, board.size());
        assertEquals(1, row(board, "Nick").wins());
        assertEquals(0, row(board, "Nick").losses());
    }

    @Test
    @DisplayName("a win stamps the time; a loss leaves the loser's last win alone")
    void lastWinRecorded(@TempDir Path dir) {
        Leaderboard board = fresh(dir);

        board.recordResult("Alex", "Nick");
        assertNull(row(board, "Nick").lastWinAt(), "Nick has not won yet");
        String alexStamp = row(board, "Alex").lastWinAt();
        assertNotNull(alexStamp);

        board.recordResult("Nick", "Alex");
        assertNotNull(row(board, "Nick").lastWinAt());
        assertEquals(alexStamp, row(board, "Alex").lastWinAt());
    }

    @Test
    @DisplayName("the table is best first, ties broken on best streak then name")
    void ordering(@TempDir Path dir) {
        Leaderboard board = fresh(dir);
        board.recordResult("Ada", "Zed");
        board.recordResult("Ada", "Zed");
        board.recordResult("Bo", "Zed");
        board.recordResult("Cy", "Zed");
        board.recordResult("Bo", "Zed");

        assertEquals(List.of("Ada", "Bo", "Cy", "Zed"),
                board.top(10).stream().map(Leaderboard.Entry::name).toList());
    }

    @Test
    @DisplayName("win rate is the share of games played, as a whole percentage")
    void winRate(@TempDir Path dir) {
        Leaderboard board = fresh(dir);
        for (int i = 0; i < 7; i++) {
            board.recordResult("Ada", "Zed");
        }
        for (int i = 0; i < 2; i++) {
            board.recordResult("Zed", "Ada");
        }

        Leaderboard.Entry ada = row(board, "Ada");
        assertEquals(9, ada.gamesPlayed());
        assertEquals(7, ada.wins());
        assertEquals(2, ada.losses());
        assertEquals(78, ada.winRatePercent(), "7/9 rounds up to 78");
        // Zed is the mirror image of Ada, and must not read as a different number of games
        assertEquals(9, row(board, "Zed").gamesPlayed());
        assertEquals(22, row(board, "Zed").winRatePercent(), "2/9 rounds down to 22");
    }

    @Test
    @DisplayName("a rate is a percentage of games played, never of wins")
    void winRateIsNotOfWins(@TempDir Path dir) {
        Leaderboard board = fresh(dir);
        board.recordResult("Ada", "Zed");
        assertEquals(100, row(board, "Ada").winRatePercent(), "one win, one game");

        for (int i = 0; i < 6; i++) {
            board.recordResult("Zed", "Ada");
        }
        assertEquals(14, row(board, "Ada").winRatePercent(), "1 of 7, not 1 of 1");
        assertEquals(86, row(board, "Zed").winRatePercent(), "6 of 7");
    }

    @Test
    @DisplayName("a row with no games reports no rate rather than dividing by zero")
    void winRateWithoutGames() {
        assertEquals(0, new Leaderboard.Entry("Nobody", 0, 0, 0, 0, null).winRatePercent());
        assertEquals(0, new Leaderboard.Entry("Nobody", 0, 0, 0, 0, null).gamesPlayed());
    }

    @Test
    @DisplayName("top() caps the rows without disturbing the order")
    void topIsCapped(@TempDir Path dir) {
        Leaderboard board = fresh(dir);
        board.recordResult("Ada", "Zed");
        board.recordResult("Bo", "Zed");
        board.recordResult("Cy", "Zed");

        assertEquals(List.of("Ada", "Bo"), board.top(2).stream()
                .map(Leaderboard.Entry::name).toList());
        assertEquals(4, board.top(50).size());
        assertEquals(4, board.top(Integer.MAX_VALUE).size());
    }

    // ------------------------------------------------------------------ storage

    @Test
    @DisplayName("a score survives the process that earned it")
    void survivesRestart(@TempDir Path dir) {
        Path file = dir.resolve("nested").resolve("leaderboard.json");

        Leaderboard first = new Leaderboard(new ObjectMapper(), file.toString());
        first.recordResult("Nick", "Alex");
        assertTrue(Files.isRegularFile(file), "the ledger should be on disk, not just in memory");

        Leaderboard second = new Leaderboard(new ObjectMapper(), file.toString());
        assertEquals(2, second.size());
        assertEquals(1, row(second, "Nick").wins());
        assertEquals(1, row(second, "Alex").losses());
        assertEquals(1, row(second, "Nick").bestStreak());
    }

    @Test
    @DisplayName("a rewritten ledger is whole, never half a file")
    void persistedFileIsNotTruncated(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("leaderboard.json");
        Leaderboard board = new Leaderboard(new ObjectMapper(), file.toString());
        for (int i = 0; i < 25; i++) {
            board.recordResult("Player" + i, "Nick");
        }

        List<Leaderboard.Entry> reread = new ObjectMapper()
                .readValue(file.toFile(), new TypeReference<List<Leaderboard.Entry>>() {
                });
        assertEquals(26, reread.size());
        assertEquals(25, row(board, "Nick").losses());
    }

    @Test
    @DisplayName("a corrupt ledger is replaced by an empty one rather than stopping the server")
    void corruptFileDoesNotStopBoot(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("leaderboard.json");
        Files.writeString(file, "{ this is not the ledger");

        Leaderboard board = new Leaderboard(new ObjectMapper(), file.toString());
        assertEquals(0, board.size());

        board.recordResult("Nick", "Alex");
        assertEquals(1, row(board, "Nick").wins());
        assertEquals(2, new Leaderboard(new ObjectMapper(), file.toString()).size());
    }

    @Test
    @DisplayName("a ledger written by another version still loads what it can")
    void tolerantOfUnknownFields(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("leaderboard.json");
        Files.writeString(file, """
                [{"name":"Nick","wins":4,"losses":1,"streak":2,"bestStreak":3,\
                "lastWinAt":"2026-01-01T00:00:00Z","someFutureField":true}]""");

        Leaderboard board = new Leaderboard(new ObjectMapper(), file.toString());
        assertEquals(1, board.size(), "the unreadable field must not cost the whole row");
        assertEquals(4, row(board, "Nick").wins());
        assertEquals(2, row(board, "Nick").streak());
        assertEquals(3, row(board, "Nick").bestStreak());
    }

    @Test
    @DisplayName("a row with no usable name is dropped on load")
    void dropsUnusableRowsOnLoad(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("leaderboard.json");
        Files.writeString(file, """
                [{"name":"  ","wins":9,"losses":0,"streak":9,"bestStreak":9},\
                {"name":"Nick","wins":1,"losses":0,"streak":1,"bestStreak":1}]""");

        Leaderboard board = new Leaderboard(new ObjectMapper(), file.toString());
        assertEquals(1, board.size());
        assertEquals(1, row(board, "Nick").wins());
    }

    @Test
    @DisplayName("reset() clears the memory without touching the file")
    void resetLeavesTheFile(@TempDir Path dir) {
        Path file = dir.resolve("leaderboard.json");
        Leaderboard board = new Leaderboard(new ObjectMapper(), file.toString());
        board.recordResult("Nick", "Alex");

        board.reset();
        assertEquals(0, board.size());
        assertEquals(2, new Leaderboard(new ObjectMapper(), file.toString()).size());
    }

    @Test
    @DisplayName("a player who has only ever lost still appears")
    void loserRowExistsEvenWithNoWin(@TempDir Path dir) {
        Leaderboard board = fresh(dir);
        board.recordResult("Nick", "Alex");
        assertTrue(board.top(10).stream().anyMatch(e -> e.name().equals("Alex")));
    }
}
