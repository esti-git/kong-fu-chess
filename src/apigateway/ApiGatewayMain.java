package apigateway;

import config.GameConfig;
import server.GameRepository;
import server.PlayerRepository;
import server.RedisPlayerRegistry;
import server.RoomLocationRegistry;

public class ApiGatewayMain {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("API_GATEWAY_PORT", "8080"));
        PlayerRepository repository = new PlayerRepository();
        RedisPlayerRegistry playerRegistry = new RedisPlayerRegistry(GameConfig.REDIS_URL);
        GameRepository gameRepository = new GameRepository(repository.getJdbcUrl());
        RoomLocationRegistry roomLocationRegistry = new RoomLocationRegistry(GameConfig.REDIS_URL);

        ApiGatewayServer server = new ApiGatewayServer(port, repository, playerRegistry, gameRepository, roomLocationRegistry);
        server.start();
    }
}
