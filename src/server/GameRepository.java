package server;

import config.GameConfig;
import server.logging.ServerLog;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persists completed games (result + rating change + move history) once a game ends — never
 * written to mid-game, matching Server_Design.md's guidance that live game state stays in
 * memory and only the final result is worth a DB write. Shares its JDBC connection target with
 * whatever {@link PlayerRepository} the caller already has (see {@link PlayerRepository#getJdbcUrl()})
 * so tests that point PlayerRepository at an in-memory SQLite DB get the same for game history.
 */
public class GameRepository {

    private final Connection connection;

    public GameRepository() {
        this(GameConfig.DB_URL);
    }

    public GameRepository(String jdbcUrl) {
        try {
            connection = DriverManager.getConnection(jdbcUrl);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS games (" +
                        "id TEXT PRIMARY KEY, " +
                        "room_id TEXT NOT NULL, " +
                        "white_username TEXT NOT NULL, " +
                        "black_username TEXT NOT NULL, " +
                        "winner_color TEXT NOT NULL, " +
                        "white_rating_before INTEGER NOT NULL, " +
                        "white_rating_after INTEGER NOT NULL, " +
                        "black_rating_before INTEGER NOT NULL, " +
                        "black_rating_after INTEGER NOT NULL, " +
                        "started_at BIGINT NOT NULL, " +
                        "ended_at BIGINT NOT NULL, " +
                        "events_json TEXT)");
            }
        } catch (SQLException e) {
            ServerLog.error("Failed to open games database", e);
            throw new RuntimeException("Failed to open games database", e);
        }
    }

    /**
     * Best-effort: a failure here must never disrupt the game-end flow (notifying players,
     * broadcasting the final state) that callers run alongside this, so failures are logged and
     * swallowed rather than propagated.
     */
    public void recordGame(String roomId, String whiteUsername, String blackUsername, String winnerColor,
                            int whiteRatingBefore, int whiteRatingAfter, int blackRatingBefore, int blackRatingAfter,
                            long startedAt, long endedAt, String eventsJson) {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO games(id, room_id, white_username, black_username, winner_color, " +
                        "white_rating_before, white_rating_after, black_rating_before, black_rating_after, " +
                        "started_at, ended_at, events_json) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, roomId);
            insert.setString(3, whiteUsername);
            insert.setString(4, blackUsername);
            insert.setString(5, winnerColor);
            insert.setInt(6, whiteRatingBefore);
            insert.setInt(7, whiteRatingAfter);
            insert.setInt(8, blackRatingBefore);
            insert.setInt(9, blackRatingAfter);
            insert.setLong(10, startedAt);
            insert.setLong(11, endedAt);
            insert.setString(12, eventsJson);
            insert.executeUpdate();
        } catch (SQLException e) {
            ServerLog.error("Failed to record game " + roomId, e);
        }
    }

    public List<GameSummary> findHistory(String username) {
        List<GameSummary> results = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT room_id, white_username, black_username, winner_color, " +
                        "white_rating_before, white_rating_after, black_rating_before, black_rating_after, " +
                        "started_at, ended_at FROM games WHERE white_username = ? OR black_username = ? " +
                        "ORDER BY ended_at DESC")) {
            select.setString(1, username);
            select.setString(2, username);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    results.add(new GameSummary(
                            rs.getString("room_id"),
                            rs.getString("white_username"),
                            rs.getString("black_username"),
                            rs.getString("winner_color"),
                            rs.getInt("white_rating_before"),
                            rs.getInt("white_rating_after"),
                            rs.getInt("black_rating_before"),
                            rs.getInt("black_rating_after"),
                            rs.getLong("started_at"),
                            rs.getLong("ended_at")));
                }
            }
        } catch (SQLException e) {
            ServerLog.error("Failed to fetch history for " + username, e);
        }
        return results;
    }
}
