package server;

import config.GameConfig;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import nats.NatsConnections;
import org.json.JSONObject;
import server.logging.ServerLog;

import java.nio.charset.StandardCharsets;

/**
 * Decouples game-end DB writes (rating change + history record) from the moment a game actually
 * ends, per Server_Design.md section 1: game-server publishes a "result" message instead of
 * writing to Postgres directly, so a burst of games finishing at once (or Postgres being briefly
 * slow) can't block room teardown or lose a result. Backed by NATS JetStream rather than the
 * plain fire-and-forget pub/sub used for seek/cancel elsewhere, because this path specifically
 * needs at-least-once delivery -- a dropped rating update is a real correctness bug, unlike a
 * delayed matchmaking heartbeat.
 *
 * <p>Uses JetStream's in-memory storage: appropriate for the demo/dev scope this project targets
 * (Server_Design.md repeatedly frames simplifications like this as fine for "the small/demo
 * implementation") -- a production build would use file storage across replicated NATS nodes so
 * a NATS restart can't lose queued results.
 */
public class GameResultQueue {

    private final JetStream jetStream;

    public GameResultQueue(String natsUrl) {
        JetStream js = null;
        try {
            Connection connection = NatsConnections.connect(natsUrl);
            JetStreamManagement jsm = connection.jetStreamManagement();
            ensureStream(jsm);
            js = connection.jetStream();
        } catch (Exception e) {
            ServerLog.warn("GameResultQueue: JetStream unavailable, results will not be persisted: " + e.getMessage());
        }
        this.jetStream = js;
    }

    static void ensureStream(JetStreamManagement jsm) throws Exception {
        try {
            jsm.getStreamInfo(GameConfig.GAME_RESULT_STREAM);
        } catch (JetStreamApiException e) {
            jsm.addStream(StreamConfiguration.builder()
                    .name(GameConfig.GAME_RESULT_STREAM)
                    .subjects(GameConfig.GAME_RESULT_SUBJECT)
                    .storageType(StorageType.Memory)
                    .build());
        }
    }

    public void publish(JSONObject result) {
        if (jetStream == null) {
            ServerLog.warn("GameResultQueue: dropping result, JetStream not available: " + result);
            return;
        }
        try {
            jetStream.publish(GameConfig.GAME_RESULT_SUBJECT, result.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            ServerLog.error("GameResultQueue: failed to publish result " + result, e);
        }
    }
}
