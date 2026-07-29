package matchmaker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;
import server.logging.ServerLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class SeekHandler implements HttpHandler {

    private final RedisMatchQueue queue;

    public SeekHandler(RedisMatchQueue queue) {
        this.queue = queue;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, new JSONObject().put("matched", false).put("message", "POST only"));
            return;
        }

        String username;
        int rating;
        try {
            JSONObject request = new JSONObject(readBody(exchange));
            username = request.getString("username");
            rating = request.getInt("rating");
        } catch (Exception e) {
            sendJson(exchange, 400, new JSONObject().put("matched", false).put("message", "Malformed request body"));
            return;
        }

        Optional<String> opponent = queue.enqueueAndFindMatch(username, rating);
        JSONObject response = new JSONObject().put("matched", opponent.isPresent());
        opponent.ifPresent(o -> response.put("opponent", o));

        if (opponent.isPresent()) {
            ServerLog.info("Matchmaker: matched " + username + " vs " + opponent.get());
        }
        sendJson(exchange, 200, response);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sendJson(HttpExchange exchange, int status, JSONObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
