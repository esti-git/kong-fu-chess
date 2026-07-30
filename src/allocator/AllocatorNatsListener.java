package allocator;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import org.json.JSONObject;
import server.ShardRegistry;
import server.logging.ServerLog;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Replies to allocate requests over NATS instead of the HTTP handler it replaces. */
public class AllocatorNatsListener {

    private final Connection connection;
    private final ShardRegistry shardRegistry;
    private final String subject;
    private Dispatcher dispatcher;

    public AllocatorNatsListener(Connection connection, ShardRegistry shardRegistry, String subject) {
        this.connection = connection;
        this.shardRegistry = shardRegistry;
        this.subject = subject;
    }

    public void start() {
        dispatcher = connection.createDispatcher(msg -> {
            Optional<ShardRegistry.ShardInfo> shard = shardRegistry.pickLeastLoaded();
            JSONObject response = new JSONObject();
            shard.ifPresent(info -> response.put("shardId", info.shardId()).put("host", info.host()));
            connection.publish(msg.getReplyTo(), response.toString().getBytes(StandardCharsets.UTF_8));
        });
        dispatcher.subscribe(subject, "allocator");
        ServerLog.info("Allocator NATS listener subscribed on " + subject);
    }

    public void stop() {
        if (dispatcher != null) {
            connection.closeDispatcher(dispatcher);
        }
    }
}
