package server;

import config.GameConfig;
import protocol.LoginResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class PlayerRepository {

    private static final int STARTING_RATING = GameConfig.STARTING_RATING;

    private final Connection connection;

    public PlayerRepository() {
        this(GameConfig.DB_URL);
    }

    public PlayerRepository(String jdbcUrl) {
        try {
            connection = DriverManager.getConnection(jdbcUrl);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS players (" +
                        "username TEXT PRIMARY KEY, " +
                        "password TEXT NOT NULL, " +
                        "rating INTEGER NOT NULL DEFAULT " + STARTING_RATING + ")");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open players database", e);
        }
    }

    public LoginResult loginOrRegister(String username, String rawPassword) {
        String hash = PasswordHasher.sha256(rawPassword);
        try {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT password, rating FROM players WHERE username = ?")) {
                select.setString(1, username);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        String storedHash = rs.getString("password");
                        if (!storedHash.equals(hash)) {
                            return new LoginResult(false, 0, "Incorrect password for '" + username + "'.", false);
                        }
                        return new LoginResult(true, rs.getInt("rating"), null, false);
                    }
                }
            }

            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO players(username, password, rating) VALUES(?, ?, ?)")) {
                insert.setString(1, username);
                insert.setString(2, hash);
                insert.setInt(3, STARTING_RATING);
                insert.executeUpdate();
            }
            return new LoginResult(true, STARTING_RATING, null, false);
        } catch (SQLException e) {
            return new LoginResult(false, 0, "Login failed: " + e.getMessage(), false);
        }
    }

    public void updateRating(String username, int newRating) {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE players SET rating = ? WHERE username = ?")) {
            update.setInt(1, newRating);
            update.setString(2, username);
            update.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update rating for " + username, e);
        }
    }

}
