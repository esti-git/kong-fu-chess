package wsgateway;

import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import server.logging.ServerLog;

import java.net.URI;

/**
 * The gateway's half of a client<->shard proxy pair: a WebSocket client connected to the
 * shard on behalf of one downstream (browser/Swing) connection. Everything it receives from
 * the shard is relayed verbatim to that downstream connection — the gateway never parses the
 * game protocol.
 */
public class UpstreamProxyClient extends WebSocketClient {

    private final WebSocket downstream;

    public UpstreamProxyClient(URI shardUri, WebSocket downstream) {
        super(shardUri);
        this.downstream = downstream;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        ServerLog.info("Gateway: upstream connected to shard for " + downstream.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(String message) {
        if (downstream.isOpen()) {
            downstream.send(message);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        if (downstream.isOpen()) {
            downstream.close();
        }
    }

    @Override
    public void onError(Exception ex) {
        ServerLog.error("Gateway: upstream connection error", ex);
    }
}
