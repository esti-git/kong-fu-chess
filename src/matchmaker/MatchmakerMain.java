package matchmaker;

import config.GameConfig;

public class MatchmakerMain {
    public static void main(String[] args) throws Exception {
        RedisMatchQueue queue = new RedisMatchQueue(GameConfig.REDIS_URL);
        MatchmakerServer server = new MatchmakerServer(GameConfig.MATCHMAKER_PORT, queue);
        server.start();
    }
}
