package wsgateway;

import config.GameConfig;

public class WsGatewayMain {
    public static void main(String[] args) throws Exception {
        WsGatewayServer server = new WsGatewayServer(GameConfig.GATEWAY_PORT, GameConfig.GAME_SERVER_URL);
        server.start();
    }
}
