package server;

import config.GameConfig;
import enums.PieceColor;
import org.java_websocket.WebSocket;
import server.logging.ServerLog;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

public class MatchService {

    private final Matchmaker matchmaker;
    private final RoomRegistry roomRegistry;
    private final PlayerRepository repository;
    private final ScheduledExecutorService scheduler;
    private final PlayerRegistry playerRegistry;
    private final AllocatorClient allocatorClient;

    public MatchService(Matchmaker matchmaker, RoomRegistry roomRegistry,
                         PlayerRepository repository, ScheduledExecutorService scheduler,
                         PlayerRegistry playerRegistry, AllocatorClient allocatorClient) {
        this.matchmaker = matchmaker;
        this.roomRegistry = roomRegistry;
        this.repository = repository;
        this.scheduler = scheduler;
        this.playerRegistry = playerRegistry;
        this.allocatorClient = allocatorClient;
    }

    public static void assignAndActivate(PlayerSession session, PieceColor color) {
        session.setColor(color);
        session.setState(SessionState.PLAYING);
    }

    public void tryPromoteFromQueue() {
        List<Map.Entry<WebSocket, PlayerSession>> pair;
        while ((pair = matchmaker.findCompatiblePair()) != null) {
            Map.Entry<WebSocket, PlayerSession> first = pair.get(0);
            Map.Entry<WebSocket, PlayerSession> second = pair.get(1);
            matchmaker.remove(first.getKey());
            matchmaker.remove(second.getKey());
            seatPair(first.getKey(), first.getValue(), second.getKey(), second.getValue());
        }
    }

    public void seatMatchedPair(WebSocket connA, PlayerSession sessionA, WebSocket connB, PlayerSession sessionB) {
        seatPair(connA, sessionA, connB, sessionB);
    }

    private void seatPair(WebSocket connWhite, PlayerSession newWhite, WebSocket connBlack, PlayerSession newBlack) {
        assignAndActivate(newWhite, PieceColor.WHITE);
        assignAndActivate(newBlack, PieceColor.BLACK);

        Room room = roomRegistry.createRoom(repository, scheduler);
        room.seatMatch(connWhite, newWhite, connBlack, newBlack);
        roomRegistry.bind(connWhite, room.roomId);
        roomRegistry.bind(connBlack, room.roomId);

        String hostingShardId = allocatorClient.allocateOrDefault();
        if (!hostingShardId.equals(GameConfig.SHARD_ID)) {
            ServerLog.warn("Allocator assigned room " + room.roomId + " to " + hostingShardId
                    + " but it is hosted locally on " + GameConfig.SHARD_ID + " (cross-shard placement not yet supported)");
        }
        playerRegistry.markInRoom(newWhite.getUsername(), hostingShardId, room.roomId);
        playerRegistry.markInRoom(newBlack.getUsername(), hostingShardId, room.roomId);
        ServerLog.info("Matched " + newWhite.getUsername() + " vs " + newBlack.getUsername() + " into room " + room.roomId);
    }
}
