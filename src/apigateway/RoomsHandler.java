package apigateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONObject;
import server.RoomLocationRegistry;
import server.RoomSummary;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class RoomsHandler implements HttpHandler {

    private final RoomLocationRegistry roomLocationRegistry;

    public RoomsHandler(RoomLocationRegistry roomLocationRegistry) {
        this.roomLocationRegistry = roomLocationRegistry;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, new JSONObject().put("rooms", new JSONArray()));
            return;
        }

        List<RoomSummary> rooms = roomLocationRegistry.findAll();
        JSONArray array = new JSONArray();
        for (RoomSummary room : rooms) {
            array.put(new JSONObject()
                    .put("roomId", room.roomId())
                    .put("shardId", room.shardId())
                    .put("whiteUsername", room.whiteUsername())
                    .put("blackUsername", room.blackUsername()));
        }
        sendJson(exchange, 200, new JSONObject().put("rooms", array));
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
