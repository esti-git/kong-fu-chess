package allocator;

import config.GameConfig;
import io.nats.client.Connection;
import nats.NatsConnections;
import server.ShardRegistry;

public class AllocatorMain {
    public static void main(String[] args) throws Exception {
        ShardRegistry shardRegistry = new ShardRegistry(GameConfig.REDIS_URL);
        Connection natsConnection = NatsConnections.connect(GameConfig.NATS_URL);
        AllocatorServer server = new AllocatorServer(GameConfig.ALLOCATOR_PORT, shardRegistry, natsConnection,
                GameConfig.ALLOCATOR_INBOX_SUBJECT);
        server.start();
    }
}
