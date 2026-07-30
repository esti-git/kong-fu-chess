package matchmaker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.nats.client.Connection;
import server.logging.ServerLog;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class MatchmakerServer {

    private final int port;
    private final RedisMatchQueue queue;
    private final Connection natsConnection;
    private final String natsSubject;
    private HttpServer httpServer;
    private MatchmakerNatsListener natsListener;

    public MatchmakerServer(int port, RedisMatchQueue queue, Connection natsConnection, String natsSubject) {
        this.port = port;
        this.queue = queue;
        this.natsConnection = natsConnection;
        this.natsSubject = natsSubject;
    }

    public void start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/healthz", MatchmakerServer::handleHealthz);
        httpServer.setExecutor(null);
        httpServer.start();
        ServerLog.info("Matchmaker listening on port " + port);

        natsListener = new MatchmakerNatsListener(natsConnection, queue, natsSubject);
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
