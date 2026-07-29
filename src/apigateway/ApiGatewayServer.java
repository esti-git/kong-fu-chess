package apigateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import server.PlayerRegistry;
import server.PlayerRepository;
import server.logging.ServerLog;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class ApiGatewayServer {

    private final int port;
    private final PlayerRepository repository;
    private final PlayerRegistry playerRegistry;
    private HttpServer httpServer;

    public ApiGatewayServer(int port, PlayerRepository repository, PlayerRegistry playerRegistry) {
        this.port = port;
        this.repository = repository;
        this.playerRegistry = playerRegistry;
    }

    public void start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/healthz", ApiGatewayServer::handleHealthz);
        httpServer.createContext("/api/login", new LoginHandler(repository, playerRegistry));
        httpServer.setExecutor(null);
        httpServer.start();
        ServerLog.info("API Gateway listening on port " + port);
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    private static void handleHealthz(HttpExchange exchange) throws IOException {
        byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
