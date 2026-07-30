package wsgateway;

import config.GameConfig;
import server.AllocatorClient;
import server.PlayerRegistry;
import server.RedisPlayerRegistry;
import server.RoomLocationRegistry;
import server.ShardRegistry;

public class WsGatewayMain {
    public static void main(String[] args) throws Exception {
        PlayerRegistry playerRegistry = new RedisPlayerRegistry(GameConfig.REDIS_URL);
        ShardRegistry shardRegistry = new ShardRegistry(GameConfig.REDIS_URL);
        RoomLocationRegistry roomLocationRegistry = new RoomLocationRegistry(GameConfig.REDIS_URL);
        AllocatorClient allocatorClient = new AllocatorClient(GameConfig.NATS_URL);
        WsGatewayServer server = new WsGatewayServer(GameConfig.GATEWAY_PORT, GameConfig.GAME_SERVER_URL,
                playerRegistry, shardRegistry, roomLocationRegistry, allocatorClient);
        server.start();
    }
}
