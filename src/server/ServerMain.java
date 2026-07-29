package server;

import config.GameConfig;

public class ServerMain {

    public static void main(String[] args) throws Exception {
        PlayerRepository repository = new PlayerRepository();
        GameServer server = new GameServer(GameConfig.GAME_SERVER_PORT, repository);
        server.start();
        server.runGameLoop();
    }
}
