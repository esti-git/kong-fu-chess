package matchmaker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import server.logging.ServerLog;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class MatchmakerServer {

    private final int port;
    private final RedisMatchQueue queue;
    private HttpServer httpServer;

    public MatchmakerServer(int port, RedisMatchQueue queue) {
        this.port = port;
        this.queue = queue;
    }

    public void start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/healthz", MatchmakerServer::handleHealthz);
        httpServer.createContext("/api/seek", new SeekHandler(queue));
        httpServer.createContext("/api/cancel", new CancelHandler(queue));
        httpServer.setExecutor(null);
        httpServer.start();
        ServerLog.info("Matchmaker listening on port " + port);
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
