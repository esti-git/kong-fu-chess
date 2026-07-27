package server;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import server.logging.ServerLog;
import config.GameConfig;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class GameServer extends WebSocketServer {

    private static final int TICK_MS = GameConfig.TICK_MS;

    private volatile boolean running = true;

    private final RoomRegistry roomRegistry = new RoomRegistry();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "matchmaking-timers");
        thread.setDaemon(true);
        return thread;
    });
    private final ServerController controller;

    public GameServer(int port, PlayerRepository repository) {
        super(new InetSocketAddress(port));
        Matchmaker matchmaker = new Matchmaker(scheduler);
        MatchService matchService = new MatchService(matchmaker, roomRegistry, repository, scheduler);
        this.controller = new ServerController(repository, new SessionRegistry(), matchmaker, roomRegistry,
                matchService, scheduler);
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
        ServerLog.info("Game server listening on port " + getPort());
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
