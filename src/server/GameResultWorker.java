package server;

import config.GameConfig;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
import nats.NatsConnections;
import org.json.JSONObject;
import server.logging.ServerLog;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Consumer side of {@link GameResultQueue}: every shard runs one of these, all pulling from the
 * same durable JetStream consumer name, so results are shared out across whichever shards are
 * currently up rather than needing a dedicated worker process -- a reasonable simplification for
 * this project's scope (see Server_Design.md section 1's original "separate worker process"
 * framing; the important architectural property is the queue decoupling writes from game-end
 * bursts, not literally which process drains it). A message is only ack'd after both DB writes
 * succeed; a failure naks it for redelivery instead of silently losing the result.
 */
public class GameResultWorker {

    private final PlayerRepository playerRepository;
    private final GameRepository gameRepository;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "game-result-worker");
        thread.setDaemon(true);
        return thread;
    });

    public GameResultWorker(String natsUrl, PlayerRepository playerRepository, GameRepository gameRepository) {
        this.playerRepository = playerRepository;
        this.gameRepository = gameRepository;
        try {
            Connection connection = NatsConnections.connect(natsUrl);
            JetStreamManagement jsm = connection.jetStreamManagement();
            GameResultQueue.ensureStream(jsm);
            JetStream jetStream = connection.jetStream();
            PullSubscribeOptions pullOptions = PullSubscribeOptions.builder()
                    .durable(GameConfig.GAME_RESULT_CONSUMER)
                    .build();
            JetStreamSubscription subscription = jetStream.subscribe(GameConfig.GAME_RESULT_SUBJECT, pullOptions);
            executor.scheduleWithFixedDelay(() -> poll(subscription), 0, 1, TimeUnit.SECONDS);
            ServerLog.info("GameResultWorker: started, consuming " + GameConfig.GAME_RESULT_SUBJECT);
        } catch (Exception e) {
            ServerLog.warn("GameResultWorker: could not start, this shard will not process queued results: "
                    + e.getMessage());
        }
    }

    private void poll(JetStreamSubscription subscription) {
        try {
            List<Message> messages = subscription.fetch(10, Duration.ofSeconds(2));
            for (Message message : messages) {
                process(message);
            }
        } catch (Exception e) {
            ServerLog.warn("GameResultWorker: poll failed: " + e.getMessage());
        }
    }

    private void process(Message message) {
        try {
            JSONObject result = new JSONObject(new String(message.getData(), StandardCharsets.UTF_8));
            String whiteUsername = result.getString("whiteUsername");
            String blackUsername = result.getString("blackUsername");
            playerRepository.updateRating(whiteUsername, result.getInt("whiteRatingAfter"));
            playerRepository.updateRating(blackUsername, result.getInt("blackRatingAfter"));
            gameRepository.recordGame(result.getString("roomId"), whiteUsername, blackUsername,
                    result.getString("winnerColor"), result.getInt("whiteRatingBefore"), result.getInt("whiteRatingAfter"),
                    result.getInt("blackRatingBefore"), result.getInt("blackRatingAfter"),
                    result.getLong("startedAt"), result.getLong("endedAt"), result.optString("eventsJson", "[]"));
            message.ack();
        } catch (Exception e) {
            ServerLog.error("GameResultWorker: failed to process result, will redeliver", e);
            message.nak();
        }
    }
}
