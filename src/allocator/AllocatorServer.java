package allocator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import server.ShardRegistry;
import server.logging.ServerLog;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class AllocatorServer {

    private final int port;
    private final ShardRegistry shardRegistry;
    private HttpServer httpServer;

    public AllocatorServer(int port, ShardRegistry shardRegistry) {
        this.port = port;
        this.shardRegistry = shardRegistry;
    }

    public void start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/healthz", AllocatorServer::handleHealthz);
        httpServer.createContext("/api/allocate", new AllocateHandler(shardRegistry));
        httpServer.setExecutor(null);
        httpServer.start();
        ServerLog.info("Allocator listening on port " + port);
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
