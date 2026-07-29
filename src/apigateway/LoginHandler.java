package apigateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;
import protocol.LoginResult;
import server.PlayerLocation;
import server.PlayerRegistry;
import server.PlayerRepository;
import server.logging.ServerLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class LoginHandler implements HttpHandler {

    private final PlayerRepository repository;
    private final PlayerRegistry playerRegistry;

    public LoginHandler(PlayerRepository repository, PlayerRegistry playerRegistry) {
        this.repository = repository;
        this.playerRegistry = playerRegistry;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, new JSONObject().put("success", false).put("message", "POST only"));
            return;
        }

        String username;
        String password;
        try {
            JSONObject request = new JSONObject(readBody(exchange));
            username = request.optString("username", "");
            password = request.optString("password", "");
        } catch (Exception e) {
            sendJson(exchange, 400, new JSONObject().put("success", false).put("message", "Malformed request body"));
            return;
        }

        if (username.isBlank()) {
            sendJson(exchange, 400, new JSONObject().put("success", false).put("message", "Username required"));
            return;
        }

        LoginResult result = repository.loginOrRegister(username, password);
        if (!result.success) {
            ServerLog.warn("API Gateway login failed for " + username + ": " + result.message);
            sendJson(exchange, 200, new JSONObject()
                    .put("success", false)
                    .put("message", result.message));
            return;
        }

        JSONObject response = new JSONObject()
                .put("success", true)
                .put("rating", result.rating);

        Optional<PlayerLocation> location = playerRegistry.find(username);
        if (location.isPresent() && "IN_ROOM".equals(location.get().status())) {
            response.put("shardId", location.get().shardId());
            response.put("roomId", location.get().roomId());
        } else {
            playerRegistry.markOnline(username);
        }

        ServerLog.info("API Gateway login: " + username + " (rating " + result.rating + ")");
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
