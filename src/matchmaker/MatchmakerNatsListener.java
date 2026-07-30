package matchmaker;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import org.json.JSONObject;
import server.logging.ServerLog;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Replies to seek/cancel requests over NATS instead of the HTTP handlers they replace. */
public class MatchmakerNatsListener {

    private final Connection connection;
    private final RedisMatchQueue queue;
    private final String subject;
    private Dispatcher dispatcher;

    public MatchmakerNatsListener(Connection connection, RedisMatchQueue queue, String subject) {
        this.connection = connection;
        this.queue = queue;
        this.subject = subject;
    }

    public void start() {
        dispatcher = connection.createDispatcher(msg -> {
            JSONObject response;
            try {
                JSONObject request = new JSONObject(new String(msg.getData(), StandardCharsets.UTF_8));
                response = switch (request.getString("action")) {
                    case "seek" -> handleSeek(request);
                    case "cancel" -> handleCancel(request);
                    default -> new JSONObject().put("error", "unknown action");
                };
            } catch (Exception e) {
                response = new JSONObject().put("error", "malformed request");
            }
            connection.publish(msg.getReplyTo(), response.toString().getBytes(StandardCharsets.UTF_8));
        });
        dispatcher.subscribe(subject, "matchmaker");
        ServerLog.info("Matchmaker NATS listener subscribed on " + subject);
    }

    private JSONObject handleSeek(JSONObject request) {
        String username = request.getString("username");
        int rating = request.getInt("rating");
        Optional<String> opponent = queue.enqueueAndFindMatch(username, rating);
        JSONObject response = new JSONObject().put("matched", opponent.isPresent());
        opponent.ifPresent(o -> response.put("opponent", o));
        if (opponent.isPresent()) {
            ServerLog.info("Matchmaker: matched " + username + " vs " + opponent.get());
        }
        return response;
    }

    private JSONObject handleCancel(JSONObject request) {
        boolean removed = queue.cancel(request.getString("username"));
        return new JSONObject().put("removed", removed);
    }

    public void stop() {
        if (dispatcher != null) {
            connection.closeDispatcher(dispatcher);
        }
    }
}
