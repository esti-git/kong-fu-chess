package wsgateway;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import server.logging.ServerLog;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WsGatewayServer extends WebSocketServer {

    private final String gameServerUrl;
    private final Map<WebSocket, UpstreamProxyClient> upstreams = new ConcurrentHashMap<>();

    public WsGatewayServer(int port, String gameServerUrl) {
        super(new InetSocketAddress(port));
        this.gameServerUrl = gameServerUrl;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        try {
            UpstreamProxyClient upstream = new UpstreamProxyClient(new URI(gameServerUrl), conn);
            upstream.connectBlocking();
            upstreams.put(conn, upstream);
        } catch (Exception e) {
            ServerLog.error("Gateway: failed to connect upstream to " + gameServerUrl, e);
            conn.close();
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        UpstreamProxyClient upstream = upstreams.get(conn);
        if (upstream != null && upstream.isOpen()) {
            upstream.send(message);
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        UpstreamProxyClient upstream = upstreams.remove(conn);
        if (upstream != null && upstream.isOpen()) {
            upstream.close();
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        ServerLog.error("Gateway: downstream connection error", ex);
    }

    @Override
    public void onStart() {
        ServerLog.info("WS Gateway listening on port " + getPort() + ", proxying to " + gameServerUrl);
    }
}
