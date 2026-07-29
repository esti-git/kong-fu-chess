package server;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Minimal liveness endpoint for services that otherwise have no HTTP surface (the WebSocket
 * servers). Just {@code /healthz} -> 200 "ok" — no readiness distinction, no metrics; see
 * Server_Design.md section 5 for what a production build would add on top of this.
 */
public class HealthServer {

    private final HttpServer httpServer;

    public HealthServer(int port) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/healthz", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        httpServer.setExecutor(null);
    }

    public void start() {
        httpServer.start();
    }
}
