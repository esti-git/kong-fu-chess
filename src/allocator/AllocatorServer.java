package allocator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.nats.client.Connection;
import server.ShardRegistry;
import server.logging.ServerLog;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class AllocatorServer {

    private final int port;
    private final ShardRegistry shardRegistry;
    private final Connection natsConnection;
    private final String natsSubject;
    private HttpServer httpServer;
    private AllocatorNatsListener natsListener;

    public AllocatorServer(int port, ShardRegistry shardRegistry, Connection natsConnection, String natsSubject) {
        this.port = port;
        this.shardRegistry = shardRegistry;
        this.natsConnection = natsConnection;
        this.natsSubject = natsSubject;
    }

    public void start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/healthz", AllocatorServer::handleHealthz);
        httpServer.setExecutor(null);
        httpServer.start();
        ServerLog.info("Allocator listening on port " + port);

        natsListener = new AllocatorNatsListener(natsConnection, shardRegistry, natsSubject);
        natsListener.start();
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (natsListener != null) {
            natsListener.stop();
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
