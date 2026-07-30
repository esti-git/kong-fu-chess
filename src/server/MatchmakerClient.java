package server;

import config.GameConfig;
import io.nats.client.Connection;
import io.nats.client.Message;
import nats.NatsConnections;
import org.json.JSONObject;
import server.logging.ServerLog;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * The shard's client for the standalone Matchmaker service. Every call is bounded by a short
 * timeout and throws on any failure — callers (see {@link ServerController#handleSeek}) are
 * expected to catch and fall back to the local in-process matcher, exactly like
 * {@link client.ApiGatewayClient} falls back to WS-only login when its gateway is unreachable.
 */
public class MatchmakerClient {

    public record SeekResult(boolean matched, String opponent) {
    }

    private final String subject;
    private Connection connection;

    public MatchmakerClient(String natsUrl) {
        this.subject = GameConfig.MATCHMAKER_INBOX_SUBJECT;
        try {
            this.connection = NatsConnections.connect(natsUrl);
        } catch (Exception e) {
            ServerLog.warn("Matchmaker NATS connection failed: " + e.getMessage());
            this.connection = null;
        }
    }

    public SeekResult seek(String username, int rating) throws Exception {
        String body = new JSONObject().put("action", "seek").put("username", username).put("rating", rating)
                .toString();
        JSONObject response = request(body);
        return new SeekResult(response.optBoolean("matched", false), response.optString("opponent", null));
    }

    public boolean cancel(String username) {
        try {
            String body = new JSONObject().put("action", "cancel").put("username", username).toString();
            JSONObject response = request(body);
            return response.optBoolean("removed", false);
        } catch (Exception e) {
            ServerLog.warn("Matchmaker cancel failed for " + username + ": " + e.getMessage());
            return false;
        }
    }

    private JSONObject request(String body) throws Exception {
        if (connection == null) {
            throw new TimeoutException("Matchmaker NATS connection unavailable");
        }
        Message reply = connection.request(subject, body.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(2));
        if (reply == null) {
            throw new TimeoutException("Matchmaker did not reply in time");
        }
        return new JSONObject(new String(reply.getData(), StandardCharsets.UTF_8));
    }
}
