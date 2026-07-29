package allocator;

import config.GameConfig;
import server.ShardRegistry;

public class AllocatorMain {
    public static void main(String[] args) throws Exception {
        ShardRegistry shardRegistry = new ShardRegistry(GameConfig.REDIS_URL);
        AllocatorServer server = new AllocatorServer(GameConfig.ALLOCATOR_PORT, shardRegistry);
        server.start();
    }
}
