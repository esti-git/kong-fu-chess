package server;

import config.GameConfig;
import org.json.JSONObject;
import server.logging.ServerLog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

public class AllocatorClient {

    public record Allocation(String shardId, String host) {
    }

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public AllocatorClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** Asks the Allocator which shard should host a new room, including how to reach it. */
    public Optional<Allocation> allocate() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/allocate"))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JSONObject json = new JSONObject(response.body());
            String shardId = json.optString("shardId", "");
            String host = json.optString("host", "");
            if (!shardId.isBlank() && !host.isBlank()) {
                return Optional.of(new Allocation(shardId, host));
            }
        } catch (Exception e) {
            ServerLog.warn("Allocator unreachable: " + e.getMessage());
        }
        return Optional.empty();
    }

    /** Convenience for callers that only need the shard id and can fall back to their own. */
    public String allocateOrDefault() {
        return allocate().map(Allocation::shardId).orElse(GameConfig.SHARD_ID);
    }
}
