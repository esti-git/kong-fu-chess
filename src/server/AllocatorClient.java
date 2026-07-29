package server;

import config.GameConfig;
import org.json.JSONObject;
import server.logging.ServerLog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class AllocatorClient {

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public AllocatorClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String allocateOrDefault() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/allocate"))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String shardId = new JSONObject(response.body()).optString("shardId", "");
            if (!shardId.isBlank()) {
                return shardId;
            }
        } catch (Exception e) {
            ServerLog.warn("Allocator unreachable, defaulting to local shard: " + e.getMessage());
        }
        return GameConfig.SHARD_ID;
    }
}
