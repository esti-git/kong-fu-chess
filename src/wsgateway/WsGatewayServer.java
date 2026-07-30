package wsgateway;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import protocol.StateCodec;
import config.GameConfig;
import server.AllocatorClient;
import server.HealthServer;
import server.PlayerLocation;
import server.PlayerRegistry;
import server.RoomLocationRegistry;
import server.RoomRegistry;
import server.RoomSummary;
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
 * never parses gameplay traffic, except {@code joinRoom}, which it peeks at to catch a client
 * joining an EXISTING room hosted on a *different* shard than the one it happened to land on
 * (fresh login or spectate-by-code, not a reconnect): the gateway looks the room up in
 * {@link RoomLocationRegistry} and, if it lives elsewhere, silently migrates the connection to
 * the correct shard (replaying the original login) before forwarding the joinRoom itself.
 */
public class WsGatewayServer extends WebSocketServer {

    private final String fallbackGameServerUrl;
    private final PlayerRegistry playerRegistry;
    private final ShardRegistry shardRegistry;
    private final RoomLocationRegistry roomLocationRegistry;
    private final AllocatorClient allocatorClient;
    private final Map<WebSocket, Route> routes = new ConcurrentHashMap<>();

    private record Resolution(String host, String shardId) {
    }

    private static final class Route {
        final UpstreamProxyClient upstream;
        final String shardId;
        final String loginMessage;

        Route(UpstreamProxyClient upstream, String shardId, String loginMessage) {
            this.upstream = upstream;
            this.shardId = shardId;
            this.loginMessage = loginMessage;
        }
    }

    public WsGatewayServer(int port, String fallbackGameServerUrl, PlayerRegistry playerRegistry,
                            ShardRegistry shardRegistry, RoomLocationRegistry roomLocationRegistry,
                            AllocatorClient allocatorClient) {
        super(new InetSocketAddress(port));
        this.fallbackGameServerUrl = fallbackGameServerUrl;
        this.playerRegistry = playerRegistry;
        this.shardRegistry = shardRegistry;
        this.roomLocationRegistry = roomLocationRegistry;
        this.allocatorClient = allocatorClient;

        try {
            new HealthServer(GameConfig.GATEWAY_HEALTH_PORT).start();
        } catch (Exception e) {
            ServerLog.warn("Failed to start health endpoint on " + GameConfig.GATEWAY_HEALTH_PORT + ": " + e.getMessage());
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        // Routing needs the first message's contents, so the upstream connection is opened
        // lazily from onMessage instead of here.
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        Route route = routes.get(conn);
        if (route == null) {
            routeAndConnect(conn, message);
            return;
        }

        if ("joinRoom".equals(StateCodec.peekType(message))) {
            Route migrated = migrateIfHostedElsewhere(conn, route, message);
            if (migrated != null) {
                route = migrated;
            }
        }

        if (route.upstream.isOpen()) {
            route.upstream.send(message);
        }
    }

    private void routeAndConnect(WebSocket conn, String firstMessage) {
        String username = peekUsername(firstMessage);
        Resolution resolution = resolveHost(username);
        ServerLog.info("Gateway: routing " + (username != null ? username : "<unknown>") + " -> " + resolution.host());
        try {
            UpstreamProxyClient upstream = new UpstreamProxyClient(new URI(resolution.host()), conn);
            upstream.connectBlocking();
            routes.put(conn, new Route(upstream, resolution.shardId(), firstMessage));
            upstream.send(firstMessage);
        } catch (Exception e) {
            ServerLog.error("Gateway: failed to connect upstream to " + resolution.host(), e);
            conn.close();
        }
    }

    /**
     * If the joinRoom's target room is tracked in the Registry under a different shard than
     * this connection is currently on, transparently switches the upstream to that shard
     * (replaying the original login there) and returns the new route. Returns null when no
     * migration was needed (room not found, already on the right shard, or target unreachable
     * -- in which case the joinRoom is just forwarded as-is and the shard itself reports the
     * error).
     */
    private Route migrateIfHostedElsewhere(WebSocket conn, Route route, String joinRoomMessage) {
        String roomId;
        try {
            roomId = RoomRegistry.normalizeRoomId(StateCodec.decodeJoinRoomId(joinRoomMessage));
        } catch (Exception e) {
            return null;
        }

        Optional<RoomSummary> located = roomLocationRegistry.find(roomId);
        if (located.isEmpty()) {
            return null;
        }
        String targetShardId = located.get().shardId();
        if (targetShardId == null || targetShardId.equals(route.shardId)) {
            return null;
        }
        Optional<String> targetHost = shardRegistry.findHost(targetShardId);
        if (targetHost.isEmpty()) {
            return null;
        }

        try {
            if (route.upstream.isOpen()) {
                route.upstream.closeForMigration();
            }
            UpstreamProxyClient newUpstream = new UpstreamProxyClient(new URI(targetHost.get()), conn);
            newUpstream.connectBlocking();
            newUpstream.send(route.loginMessage);
            Route newRoute = new Route(newUpstream, targetShardId, route.loginMessage);
            routes.put(conn, newRoute);
            ServerLog.info("Gateway: migrated " + conn.getRemoteSocketAddress() + " to shard " + targetShardId
                    + " for room " + roomId);
            return newRoute;
        } catch (Exception e) {
            ServerLog.error("Gateway: failed to migrate to shard " + targetShardId + " for room " + roomId, e);
            return null;
        }
    }

    private String peekUsername(String firstMessage) {
        try {
            if ("login".equals(StateCodec.peekType(firstMessage))) {
                return StateCodec.decodeLoginUsername(firstMessage);
            }
        } catch (Exception e) {
            ServerLog.warn("Gateway: failed to read username from first message: " + e.getMessage());
        }
        return null;
    }

    private Resolution resolveHost(String username) {
        if (username != null) {
            Optional<PlayerLocation> location = playerRegistry.find(username);
            if (location.isPresent() && "IN_ROOM".equals(location.get().status())) {
                String shardId = location.get().shardId();
                Optional<String> host = shardRegistry.findHost(shardId);
                if (host.isPresent()) {
                    return new Resolution(host.get(), shardId);
                }
            }
        }

        return allocatorClient.allocate()
                .map(a -> new Resolution(a.host(), a.shardId()))
                .orElse(new Resolution(fallbackGameServerUrl, null));
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Route route = routes.remove(conn);
        if (route != null && route.upstream.isOpen()) {
            route.upstream.close();
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        ServerLog.error("Gateway: downstream connection error", ex);
    }

    @Override
    public void onStart() {
        ServerLog.info("WS Gateway listening on port " + getPort() + ", health on " + GameConfig.GATEWAY_HEALTH_PORT
                + " (dynamic routing via Registry/Allocator, fallback " + fallbackGameServerUrl + ")");
    }
}
