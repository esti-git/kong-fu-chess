package apigateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONObject;
import server.GameRepository;
import server.GameSummary;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class HistoryHandler implements HttpHandler {

    private final GameRepository gameRepository;

    public HistoryHandler(GameRepository gameRepository) {
        this.gameRepository = gameRepository;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, new JSONObject().put("games", new JSONArray()));
            return;
        }

        String username = queryParam(exchange.getRequestURI(), "username");
        if (username == null || username.isBlank()) {
            sendJson(exchange, 400, new JSONObject().put("message", "username query param required"));
            return;
        }

        List<GameSummary> games = gameRepository.findHistory(username);
        JSONArray array = new JSONArray();
        for (GameSummary game : games) {
            array.put(new JSONObject()
                    .put("roomId", game.roomId())
                    .put("whiteUsername", game.whiteUsername())
                    .put("blackUsername", game.blackUsername())
                    .put("winnerColor", game.winnerColor())
                    .put("whiteRatingBefore", game.whiteRatingBefore())
                    .put("whiteRatingAfter", game.whiteRatingAfter())
                    .put("blackRatingBefore", game.blackRatingBefore())
                    .put("blackRatingAfter", game.blackRatingAfter())
                    .put("startedAt", game.startedAt())
                    .put("endedAt", game.endedAt()));
        }
        sendJson(exchange, 200, new JSONObject().put("games", array));
    }

    private static String queryParam(URI uri, String key) {
        String query = uri.getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            if (k.equals(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
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
