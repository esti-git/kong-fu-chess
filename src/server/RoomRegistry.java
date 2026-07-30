package server;

import org.java_websocket.WebSocket;
import server.logging.ServerLog;
import view.BoardSnapshotFactory;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;

public class RoomRegistry {

    private static final String ID_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int ID_LENGTH = 6;

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<WebSocket, String> roomIdByConn = new ConcurrentHashMap<>();
    private final BoardSnapshotFactory snapshotFactory = new BoardSnapshotFactory();
    private final GameRepository gameRepository;
    private final RoomLocationRegistry roomLocationRegistry;

    public RoomRegistry() {
        this(null, null);
    }

    public RoomRegistry(GameRepository gameRepository, RoomLocationRegistry roomLocationRegistry) {
        this.gameRepository = gameRepository;
        this.roomLocationRegistry = roomLocationRegistry;
    }

    public static String normalizeRoomId(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase();
    }

    public Room createRoom(PlayerRepository repository, ScheduledExecutorService scheduler) {
        String roomId;
        Room room;
        do {
            roomId = generateId();
            room = new Room(roomId, repository, scheduler, snapshotFactory, gameRepository, roomLocationRegistry);
        } while (rooms.putIfAbsent(roomId, room) != null);
        ServerLog.info("Room " + roomId + " created");
        return room;
    }

    public Room createRoomWithId(String roomId, PlayerRepository repository, ScheduledExecutorService scheduler) {
        Room room = new Room(roomId, repository, scheduler, snapshotFactory, gameRepository, roomLocationRegistry);
        boolean created = rooms.putIfAbsent(roomId, room) == null;
        if (created) {
            ServerLog.info("Room " + roomId + " created");
        }
        return created ? room : null;
    }

    public Room get(String roomId) {
        return rooms.get(roomId);
    }

    public Room roomFor(WebSocket conn) {
        String roomId = roomIdByConn.get(conn);
        return roomId == null ? null : rooms.get(roomId);
    }

    public void bind(WebSocket conn, String roomId) {
        roomIdByConn.put(conn, roomId);
    }

    public void unbind(WebSocket conn) {
        roomIdByConn.remove(conn);
    }

    public void removeIfEmpty(String roomId) {
        Room room = rooms.get(roomId);
        if (room != null && room.isEmpty()) {
            rooms.remove(roomId);
            if (roomLocationRegistry != null) {
                roomLocationRegistry.remove(roomId);
            }
            ServerLog.info("Room " + roomId + " removed (empty)");
        }
    }

    public Room findRoomWithDisconnectedUser(String username) {
        for (Room room : rooms.values()) {
            if (room.findDisconnectedSession(username) != null) {
                return room;
            }
        }
        return null;
    }

    public Collection<Room> allRooms() {
        return rooms.values();
    }

    private String generateId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < ID_LENGTH; i++) {
            sb.append(ID_CHARS.charAt(random.nextInt(ID_CHARS.length())));
        }
        return sb.toString();
    }
}
