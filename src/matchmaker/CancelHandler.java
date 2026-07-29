package matchmaker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class CancelHandler implements HttpHandler {

    private final RedisMatchQueue queue;

    public CancelHandler(RedisMatchQueue queue) {
        this.queue = queue;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, new JSONObject().put("removed", false));
            return;
        }

        String username;
        try {
            JSONObject request = new JSONObject(readBody(exchange));
            username = request.getString("username");
        } catch (Exception e) {
            sendJson(exchange, 400, new JSONObject().put("removed", false));
            return;
        }

        boolean removed = queue.cancel(username);
        sendJson(exchange, 200, new JSONObject().put("removed", removed));
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
