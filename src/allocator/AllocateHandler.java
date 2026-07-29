package allocator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;
import server.ShardRegistry;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class AllocateHandler implements HttpHandler {

    private final ShardRegistry shardRegistry;

    public AllocateHandler(ShardRegistry shardRegistry) {
        this.shardRegistry = shardRegistry;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, new JSONObject());
            return;
        }

        Optional<ShardRegistry.ShardInfo> shard = shardRegistry.pickLeastLoaded();
        JSONObject response = new JSONObject();
        shard.ifPresent(info -> response.put("shardId", info.shardId()).put("host", info.host()));
        sendJson(exchange, 200, response);
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
