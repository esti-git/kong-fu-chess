package server;

import config.GameConfig;
import io.nats.client.Connection;
import io.nats.client.Message;
import nats.NatsConnections;
import org.json.JSONObject;
import server.logging.ServerLog;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

public class AllocatorClient {

    public record Allocation(String shardId, String host) {
    }

    private final String subject;
    private Connection connection;

    public AllocatorClient(String natsUrl) {
        this.subject = GameConfig.ALLOCATOR_INBOX_SUBJECT;
        try {
            this.connection = NatsConnections.connect(natsUrl);
        } catch (Exception e) {
            ServerLog.warn("Allocator NATS connection failed: " + e.getMessage());
            this.connection = null;
        }
    }

    /** Asks the Allocator which shard should host a new room, including how to reach it. */
    public Optional<Allocation> allocate() {
        if (connection == null) {
            return Optional.empty();
        }
        try {
            Message reply = connection.request(subject, "{}".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(2));
            if (reply == null) {
                ServerLog.warn("Allocator unreachable: no reply within timeout");
                return Optional.empty();
            }
            JSONObject json = new JSONObject(new String(reply.getData(), StandardCharsets.UTF_8));
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
