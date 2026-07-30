package matchmaker;

import config.GameConfig;
import io.nats.client.Connection;
import nats.NatsConnections;

public class MatchmakerMain {
    public static void main(String[] args) throws Exception {
        RedisMatchQueue queue = new RedisMatchQueue(GameConfig.REDIS_URL);
        Connection natsConnection = NatsConnections.connect(GameConfig.NATS_URL);
        MatchmakerServer server = new MatchmakerServer(GameConfig.MATCHMAKER_PORT, queue, natsConnection,
                GameConfig.MATCHMAKER_INBOX_SUBJECT);
        server.start();
    }
}
