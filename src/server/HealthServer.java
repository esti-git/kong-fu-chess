package server;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Liveness/readiness/metrics endpoints for services that otherwise have no HTTP surface (the
 * WebSocket servers). {@code /healthz} (liveness) always answers 200 as long as the process is
 * up. {@code /readyz} (readiness) answers 200 normally but flips to 503 once {@link #setReady} is
 * called with {@code false} -- used during a graceful shutdown/drain (see Server_Design.md
 * section 4) so Kubernetes stops routing *new* traffic to a pod that's finishing up its
 * in-flight rooms, without killing it outright the way a failed liveness probe would.
 * {@code /metrics} exposes whatever gauges the service registers via {@link #registerGauge} in
 * plain Prometheus text exposition format (see Server_Design.md section 5).
 */
public class HealthServer {

    private final HttpServer httpServer;
    private final AtomicBoolean ready = new AtomicBoolean(true);
    private final Map<String, Supplier<Number>> gauges = new ConcurrentHashMap<>();

    public HealthServer(int port) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/healthz", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        httpServer.createContext("/readyz", exchange -> {
            boolean isReady = ready.get();
            byte[] body = (isReady ? "ready" : "draining").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(isReady ? 200 : 503, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        httpServer.createContext("/metrics", exchange -> {
            StringBuilder text = new StringBuilder();
            gauges.forEach((name, supplier) -> {
                text.append("# TYPE ").append(name).append(" gauge\n");
                text.append(name).append(' ').append(supplier.get()).append('\n');
            });
            byte[] body = text.toString().getBytes(StandardCharsets.UTF_8);
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

    public void setReady(boolean isReady) {
        ready.set(isReady);
    }

    /** Registers a gauge to be reported on {@code /metrics}; re-read on every scrape. */
    public void registerGauge(String name, Supplier<Number> valueSupplier) {
        gauges.put(name, valueSupplier);
    }
}
