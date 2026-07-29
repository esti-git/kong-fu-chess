package server;

import org.json.JSONObject;
import server.logging.ServerLog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The shard's client for the standalone Matchmaker service. Every call is bounded by a short
 * timeout and throws on any failure — callers (see {@link ServerController#handleSeek}) are
 * expected to catch and fall back to the local in-process matcher, exactly like
 * {@link client.ApiGatewayClient} falls back to WS-only login when its gateway is unreachable.
 */
public class MatchmakerClient {

    public record SeekResult(boolean matched, String opponent) {
    }

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public MatchmakerClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public SeekResult seek(String username, int rating) throws Exception {
        String body = new JSONObject().put("username", username).put("rating", rating).toString();
        JSONObject response = post("/api/seek", body);
        return new SeekResult(response.optBoolean("matched", false), response.optString("opponent", null));
    }

    public boolean cancel(String username) {
        try {
            String body = new JSONObject().put("username", username).toString();
            JSONObject response = post("/api/cancel", body);
            return response.optBoolean("removed", false);
        } catch (Exception e) {
            ServerLog.warn("Matchmaker cancel failed for " + username + ": " + e.getMessage());
            return false;
        }
    }

    private JSONObject post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new JSONObject(response.body());
    }
}
