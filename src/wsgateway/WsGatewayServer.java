package wsgateway;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import protocol.StateCodec;
import server.AllocatorClient;
import server.PlayerLocation;
import server.PlayerRegistry;
import server.ShardRegistry;
import server.logging.ServerLog;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-facing front door. Holds no game state and runs no game logic. Routing is resolved
 * once per connection, from that connection's first message (always a {@code login} per the
 * client protocol): a returning player whose {@link PlayerRegistry} entry says they're already
 * {@code IN_ROOM} is routed to that room's shard (via {@link ShardRegistry}); anyone else is
 * routed to whatever the Game Allocator currently reports as least loaded. Everything after
 * that first message is relayed verbatim through {@link UpstreamProxyClient} — the gateway
 * never parses gameplay traffic, only the one message it needs to make a routing decision.
 *
 * <p>Known limitation, not yet solved: a client joining an EXISTING room by room code (not
 * their own, not a reconnect) is routed the same way as any fresh login — to the least-loaded
 * shard, not necessarily the one actually hosting that room. That requires a room-id-to-shard
 * lookup this phase doesn't add. Harmless while only one shard runs; a real gap once there are
 * several.
 */
public class WsGatewayServer extends WebSocketServer {

    private final String fallbackGameServerUrl;
    private final PlayerRegistry playerRegistry;
    private final ShardRegistry shardRegistry;
    private final AllocatorClient allocatorClient;
    private final Map<WebSocket, UpstreamProxyClient> upstreams = new ConcurrentHashMap<>();

    public WsGatewayServer(int port, String fallbackGameServerUrl, PlayerRegistry playerRegistry,
                            ShardRegistry shardRegistry, AllocatorClient allocatorClient) {
        super(new InetSocketAddress(port));
        this.fallbackGameServerUrl = fallbackGameServerUrl;
        this.playerRegistry = playerRegistry;
        this.shardRegistry = shardRegistry;
        this.allocatorClient = allocatorClient;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        // Routing needs the first message's contents, so the upstream connection is opened
        // lazily from onMessage instead of here.
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        UpstreamProxyClient upstream = upstreams.get(conn);
        if (upstream != null) {
            if (upstream.isOpen()) {
                upstream.send(message);
            }
            return;
        }
        routeAndConnect(conn, message);
    }

    private void routeAndConnect(WebSocket conn, String firstMessage) {
        String host = resolveHost(firstMessage);
        try {
            UpstreamProxyClient upstream = new UpstreamProxyClient(new URI(host), conn);
            upstream.connectBlocking();
            upstreams.put(conn, upstream);
            upstream.send(firstMessage);
        } catch (Exception e) {
            ServerLog.error("Gateway: failed to connect upstream to " + host, e);
            conn.close();
        }
    }

    private String resolveHost(String firstMessage) {
        try {
            if ("login".equals(StateCodec.peekType(firstMessage))) {
                String username = StateCodec.decodeLoginUsername(firstMessage);
                Optional<PlayerLocation> location = playerRegistry.find(username);
                if (location.isPresent() && "IN_ROOM".equals(location.get().status())) {
                    Optional<String> host = shardRegistry.findHost(location.get().shardId());
                    if (host.isPresent()) {
                        return host.get();
                    }
                }
            }
        } catch (Exception e) {
            ServerLog.warn("Gateway: failed to resolve routing from first message: " + e.getMessage());
        }

        return allocatorClient.allocate().map(AllocatorClient.Allocation::host).orElse(fallbackGameServerUrl);
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
        ServerLog.info("WS Gateway listening on port " + getPort()
                + " (dynamic routing via Registry/Allocator, fallback " + fallbackGameServerUrl + ")");
    }
}
