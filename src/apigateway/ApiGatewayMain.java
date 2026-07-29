package apigateway;

import config.GameConfig;
import server.PlayerRepository;
import server.RedisPlayerRegistry;

public class ApiGatewayMain {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("API_GATEWAY_PORT", "8080"));
        PlayerRepository repository = new PlayerRepository();
        RedisPlayerRegistry playerRegistry = new RedisPlayerRegistry(GameConfig.REDIS_URL);

        ApiGatewayServer server = new ApiGatewayServer(port, repository, playerRegistry);
        server.start();
    }
}
