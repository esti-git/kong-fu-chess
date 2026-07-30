import org.java_websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.Room;
import server.RoomRegistry;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;

class RoomRegistryTest {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final RoomRegistry registry = new RoomRegistry();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void testCreateRoomGeneratesRetrievableRoom() {
        Room room = registry.createRoom(scheduler);

        assertNotNull(room);
        assertNotNull(registry.get(room.roomId));
        assertSame(room, registry.get(room.roomId));
    }

    @Test
    void testCreateRoomWithIdSucceedsOnce() {
        Room room = registry.createRoomWithId("MYROOM", scheduler);

        assertNotNull(room);
        assertEquals("MYROOM", room.roomId);
    }

    @Test
    void testCreateRoomWithIdReturnsNullOnDuplicate() {
        registry.createRoomWithId("MYROOM", scheduler);
        Room duplicate = registry.createRoomWithId("MYROOM", scheduler);

        assertNull(duplicate);
    }

    @Test
    void testGetReturnsNullForUnknownRoom() {
        assertNull(registry.get("NOPE"));
    }

    @Test
    void testBindAndRoomForReturnsBoundRoom() {
        Room room = registry.createRoom(scheduler);
        WebSocket conn = new FakeWebSocket().socket();

        registry.bind(conn, room.roomId);

        assertSame(room, registry.roomFor(conn));
    }

    @Test
    void testUnbindClearsBinding() {
        Room room = registry.createRoom(scheduler);
        WebSocket conn = new FakeWebSocket().socket();
        registry.bind(conn, room.roomId);

        registry.unbind(conn);

        assertNull(registry.roomFor(conn));
    }

    @Test
    void testRoomForReturnsNullWhenNeverBound() {
        WebSocket conn = new FakeWebSocket().socket();
        assertNull(registry.roomFor(conn));
    }

    @Test
    void testRemoveIfEmptyRemovesOnlyEmptyRooms() {
        Room room = registry.createRoom(scheduler);
        FakeWebSocket white = new FakeWebSocket();
        room.seatCreator(white.socket(), new server.PlayerSession("alice", 1200));

        registry.removeIfEmpty(room.roomId);
        assertNotNull(registry.get(room.roomId));

        room.removeConn(white.socket());
        registry.removeIfEmpty(room.roomId);
        assertNull(registry.get(room.roomId));
    }

    @Test
    void testAllRoomsReflectsCreatedRooms() {
        Room roomA = registry.createRoom(scheduler);
        Room roomB = registry.createRoom(scheduler);

        assertTrue(registry.allRooms().contains(roomA));
        assertTrue(registry.allRooms().contains(roomB));
    }
}
