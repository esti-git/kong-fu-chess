package server;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import server.logging.ServerLog;
import config.GameConfig;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class GameServer extends WebSocketServer {

    private static final int TICK_MS = GameConfig.TICK_MS;

    private volatile boolean running = true;

    private final RoomRegistry roomRegistry;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "matchmaking-timers");
        thread.setDaemon(true);
        return thread;
    });
    private final ServerController controller;

    public GameServer(int port, PlayerRepository repository) {
        super(new InetSocketAddress(port));
        GameRepository gameRepository = new GameRepository(repository.getJdbcUrl());
        RoomLocationRegistry roomLocationRegistry = new RoomLocationRegistry(GameConfig.REDIS_URL);
        GameResultQueue resultQueue = new GameResultQueue(GameConfig.NATS_URL);
        this.roomRegistry = new RoomRegistry(resultQueue, roomLocationRegistry);
        new GameResultWorker(GameConfig.NATS_URL, repository, gameRepository);
        PlayerRegistry playerRegistry = new RedisPlayerRegistry(GameConfig.REDIS_URL);
        ShardRegistry shardRegistry = new ShardRegistry(GameConfig.REDIS_URL);
        Matchmaker matchmaker = new Matchmaker(scheduler);
        MatchmakerClient matchmakerClient = new MatchmakerClient(GameConfig.NATS_URL);
        AllocatorClient allocatorClient = new AllocatorClient(GameConfig.NATS_URL);
        MatchService matchService = new MatchService(matchmaker, roomRegistry, repository, scheduler, playerRegistry, allocatorClient);
        this.controller = new ServerController(repository, new SessionRegistry(), matchmaker, roomRegistry,
                matchService, scheduler, playerRegistry, matchmakerClient, allocatorClient);

        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(
                () -> shardRegistry.registerHeartbeat(GameConfig.SHARD_ID, GameConfig.GAME_SERVER_HOST, roomRegistry.allRooms().size()),
                0, GameConfig.SHARD_HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        HealthServer healthServer = null;
        try {
            healthServer = new HealthServer(GameConfig.GAME_SERVER_HEALTH_PORT);
            healthServer.registerGauge("kfc_game_server_active_rooms", () -> roomRegistry.allRooms().size());
            healthServer.start();
        } catch (Exception e) {
            ServerLog.warn("Failed to start health endpoint on " + GameConfig.GAME_SERVER_HEALTH_PORT + ": " + e.getMessage());
        }

        registerDrainingShutdownHook(healthServer, shardRegistry, heartbeat);
    }

    /**
     * On SIGTERM (Kubernetes scale-down/rolling update), stop taking on *new* rooms immediately
     * -- flip readiness so the Service/Allocator route around this pod, and deregister from
     * ShardRegistry so it's never picked as "least loaded" again -- but let already-active rooms
     * (bounded to Server_Design.md's 30-90s game length) finish naturally before the JVM exits,
     * instead of dropping them mid-game the instant the pod is killed.
     */
    private void registerDrainingShutdownHook(HealthServer healthServer, ShardRegistry shardRegistry,
                                               ScheduledFuture<?> heartbeat) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ServerLog.info("Shard " + GameConfig.SHARD_ID + " draining: no new rooms, waiting on "
                    + roomRegistry.allRooms().size() + " active room(s)");
            if (healthServer != null) {
                healthServer.setReady(false);
            }
            heartbeat.cancel(false);
            shardRegistry.remove(GameConfig.SHARD_ID);

            long deadline = System.currentTimeMillis() + GameConfig.DRAIN_TIMEOUT_SECONDS * 1000L;
            while (!roomRegistry.allRooms().isEmpty() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            int remaining = roomRegistry.allRooms().size();
            if (remaining > 0) {
                ServerLog.warn("Shard " + GameConfig.SHARD_ID + " drain timed out with " + remaining + " room(s) still active");
            } else {
                ServerLog.info("Shard " + GameConfig.SHARD_ID + " drained cleanly, exiting");
            }
        }, "drain-shutdown"));
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        ServerLog.info("Client connected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        ServerLog.info("Client disconnected: " + conn.getRemoteSocketAddress());
        controller.handleDisconnect(conn);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        controller.handleMessage(conn, message);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        ServerLog.error("WebSocket error", ex);
    }

    @Override
    public void onStart() {
        ServerLog.info("Game server (shardId=" + GameConfig.SHARD_ID + ") listening on port " + getPort()
                + ", health on " + GameConfig.GAME_SERVER_HEALTH_PORT);
    }

    public void runGameLoop() throws InterruptedException {
        long lastTime = System.currentTimeMillis();
        while (running) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastTime;
            lastTime = now;

            for (Room room : roomRegistry.allRooms()) {
                room.tick(now, elapsed);
            }

            Thread.sleep(TICK_MS);
        }
    }
}
