import engine.GameEngine;
import enums.PieceColor;
import enums.PlayerRole;
import events.GameEndedEvent;
import engine.GameFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import protocol.NetworkState;
import protocol.StateCodec;
import server.PlayerRepository;
import server.PlayerSession;
import server.Room;
import server.RoomRegistry;
import server.SessionState;

import java.lang.reflect.Field;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;

class RoomTest {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final RoomRegistry registry = new RoomRegistry();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private PlayerRepository newRepository() {
        return new PlayerRepository("jdbc:sqlite::memory:");
    }

    private Room newRoom(PlayerRepository repository) {
        return registry.createRoom(scheduler);
    }

    private GameEngine engineOf(Room room) throws Exception {
        Field factoryField = Room.class.getDeclaredField("factory");
        factoryField.setAccessible(true);
        GameFactory factory = (GameFactory) factoryField.get(room);
        return factory.getEngine();
    }

    private void publishGameEnded(Room room, PieceColor winner) throws Exception {
        Field factoryField = Room.class.getDeclaredField("factory");
        factoryField.setAccessible(true);
        GameFactory factory = (GameFactory) factoryField.get(room);
        factory.getEventBus().publish(new GameEndedEvent(winner));
    }

    @Test
    void testSeatCreatorSendsAssignToWhite() {
        Room room = newRoom(newRepository());
        FakeWebSocket white = new FakeWebSocket();

        room.seatCreator(white.socket(), new PlayerSession("alice", 1200));

        assertTrue(white.hasMessageOfType("assign"));
    }

    @Test
    void testSeatMatchSendsAssignToBothPlayers() {
        Room room = newRoom(newRepository());
        FakeWebSocket white = new FakeWebSocket();
        FakeWebSocket black = new FakeWebSocket();

        room.seatMatch(white.socket(), new PlayerSession("alice", 1200), black.socket(), new PlayerSession("bob", 1250));

        assertTrue(white.hasMessageOfType("assign"));
        assertTrue(black.hasMessageOfType("assign"));
    }

    @Test
    void testJoinAsSecondPlayerBecomesBlackAndReceivesState() {
        Room room = newRoom(newRepository());
        FakeWebSocket white = new FakeWebSocket();
        room.seatCreator(white.socket(), new PlayerSession("alice", 1200));

        FakeWebSocket black = new FakeWebSocket();
        PlayerRole role = room.join(black.socket(), new PlayerSession("bob", 1200));

        assertEquals(PlayerRole.BLACK, role);
        assertTrue(black.hasMessageOfType("state"));
        assertTrue(black.hasMessageOfType("assign"));
    }

    @Test
    void testJoinAsThirdConnectionBecomesSpectator() {
        Room room = newRoom(newRepository());
        FakeWebSocket white = new FakeWebSocket();
        FakeWebSocket black = new FakeWebSocket();
        room.seatMatch(white.socket(), new PlayerSession("alice", 1200), black.socket(), new PlayerSession("bob", 1200));

        FakeWebSocket spectator = new FakeWebSocket();
        PlayerRole role = room.join(spectator.socket(), new PlayerSession("carol", 1200));

        assertEquals(PlayerRole.SPECTATOR, role);
        assertTrue(spectator.hasMessageOfType("state"));
        assertTrue(spectator.hasMessageOfType("spectate"));
    }

    @Test
    void testIsEmptyLifecycle() {
        Room room = newRoom(newRepository());
        assertTrue(room.isEmpty());

        FakeWebSocket white = new FakeWebSocket();
        room.seatCreator(white.socket(), new PlayerSession("alice", 1200));
        assertFalse(room.isEmpty());

        room.removeConn(white.socket());
        assertTrue(room.isEmpty());
    }

    @Test
    void testHandleMoveRejectsUnauthorizedColor() {
        Room room = newRoom(newRepository());
        FakeWebSocket white = new FakeWebSocket();
        FakeWebSocket black = new FakeWebSocket();
        room.seatMatch(white.socket(), new PlayerSession("alice", 1200), black.socket(), new PlayerSession("bob", 1200));

        boolean result = room.handleMove(black.socket(), "WPa2a4");

        assertFalse(result);
        assertTrue(black.hasMessageOfType("error"));
    }

    @Test
    void testHandleMoveRejectsMalformedCommand() {
        Room room = newRoom(newRepository());
        FakeWebSocket white = new FakeWebSocket();
        room.seatCreator(white.socket(), new PlayerSession("alice", 1200));

        boolean result = room.handleMove(white.socket(), "garbage");

        assertFalse(result);
        assertTrue(white.hasMessageOfType("error"));
    }

    @Test
    void testHandleMoveRejectsNoMatchingPieceAtSource() {
        Room room = newRoom(newRepository());
        FakeWebSocket white = new FakeWebSocket();
        room.seatCreator(white.socket(), new PlayerSession("alice", 1200));

        boolean result = room.handleMove(white.socket(), "WPa3a4");

        assertFalse(result);
        assertTrue(white.hasMessageOfType("error"));
    }

    @Test
    void testHandleMoveSucceedsForLegalPawnMove() {
        Room room = newRoom(newRepository());
        FakeWebSocket white = new FakeWebSocket();
        room.seatCreator(white.socket(), new PlayerSession("alice", 1200));

        boolean result = room.handleMove(white.socket(), "WPa2a4");

        assertTrue(result);
        assertFalse(white.hasMessageOfType("error"));
    }

    @Test
    void testHandleJumpRejectsMalformedCommand() {
        Room room = newRoom(newRepository());
        FakeWebSocket white = new FakeWebSocket();
        room.seatCreator(white.socket(), new PlayerSession("alice", 1200));

        boolean result = room.handleJump(white.socket(), "notAJump");

        assertFalse(result);
        assertTrue(white.hasMessageOfType("error"));
    }

    @Test
    void testHandleJumpRejectsUnauthorizedColor() {
        Room room = newRoom(newRepository());
        FakeWebSocket white = new FakeWebSocket();
        FakeWebSocket black = new FakeWebSocket();
        room.seatMatch(white.socket(), new PlayerSession("alice", 1200), black.socket(), new PlayerSession("bob", 1200));

        boolean result = room.handleJump(black.socket(), "JWRa1");

        assertFalse(result);
        assertTrue(black.hasMessageOfType("error"));
    }

    @Test
    void testHandleJumpRejectsNoMatchingPieceAtPosition() {
        Room room = newRoom(newRepository());
        FakeWebSocket white = new FakeWebSocket();
        room.seatCreator(white.socket(), new PlayerSession("alice", 1200));

        boolean result = room.handleJump(white.socket(), "JWRa3");

        assertFalse(result);
        assertTrue(white.hasMessageOfType("error"));
    }

    @Test
    void testHandleJumpSucceedsForIdlePiece() {
        Room room = newRoom(newRepository());
        FakeWebSocket white = new FakeWebSocket();
        room.seatCreator(white.socket(), new PlayerSession("alice", 1200));

        boolean result = room.handleJump(white.socket(), "JWRa1");

        assertTrue(result);
        assertFalse(white.hasMessageOfType("error"));
    }

    @Test
    void testRemoveConnOnSeatedPlayerNotifiesOpponentAndMarksDisconnected() {
        Room room = newRoom(newRepository());
        FakeWebSocket white = new FakeWebSocket();
        FakeWebSocket black = new FakeWebSocket();
        PlayerSession whiteSession = new PlayerSession("alice", 1200);
        room.seatMatch(white.socket(), whiteSession, black.socket(), new PlayerSession("bob", 1200));

        room.removeConn(white.socket());

        assertTrue(black.hasMessageOfType("disconnectCountdown"));
        assertEquals(SessionState.DISCONNECTED_PENDING, whiteSession.getState());
    }

    @Test
    void testReconnectRestoresPlayingStateAndCancelsTimer() {
        Room room = newRoom(newRepository());
        FakeWebSocket white = new FakeWebSocket();
        FakeWebSocket black = new FakeWebSocket();
        PlayerSession whiteSession = new PlayerSession("alice", 1200);
        room.seatMatch(white.socket(), whiteSession, black.socket(), new PlayerSession("bob", 1200));
        room.removeConn(white.socket());
        assertEquals(SessionState.DISCONNECTED_PENDING, whiteSession.getState());

        FakeWebSocket newWhite = new FakeWebSocket();
        room.reconnect(newWhite.socket(), whiteSession, 1210);

        assertEquals(SessionState.PLAYING, whiteSession.getState());
        assertNull(whiteSession.getDisconnectTimer());
        assertEquals(1210, whiteSession.getRating());
        assertTrue(newWhite.hasMessageOfType("assign"));
    }

    @Test
    void testFindDisconnectedSessionOnlyMatchesDisconnectedUsername() {
        Room room = newRoom(newRepository());
        FakeWebSocket white = new FakeWebSocket();
        FakeWebSocket black = new FakeWebSocket();
        room.seatMatch(white.socket(), new PlayerSession("alice", 1200), black.socket(), new PlayerSession("bob", 1200));

        assertNull(room.findDisconnectedSession("alice"));

        room.removeConn(white.socket());

        assertNotNull(room.findDisconnectedSession("alice"));
        assertNull(room.findDisconnectedSession("bob"));
    }

    @Test
    void testRequestRestartIsNoOpWhileGameInProgress() {
        Room room = newRoom(newRepository());
        FakeWebSocket white = new FakeWebSocket();
        room.seatCreator(white.socket(), new PlayerSession("alice", 1200));
        int before = white.sentMessages().size();

        room.requestRestart(white.socket());

        assertEquals(before, white.sentMessages().size());
    }

    @Test
    void testRequestRestartResetsGameAfterGameOver() throws Exception {
        Room room = newRoom(newRepository());
        FakeWebSocket white = new FakeWebSocket();
        room.seatCreator(white.socket(), new PlayerSession("alice", 1200));
        engineOf(room).setGameOver(true);
        int before = white.sentMessages().size();

        room.requestRestart(white.socket());

        assertTrue(white.sentMessages().size() > before);
        String lastState = null;
        for (String message : white.sentMessages()) {
            if ("state".equals(StateCodec.peekType(message))) {
                lastState = message;
            }
        }
        assertNotNull(lastState);
        NetworkState decoded = StateCodec.decodeState(lastState);
        assertFalse(decoded.gameOver);
    }

    @Test
    void testApplyEloOnGameEndUpdatesRatingsAndPersists() throws Exception {
        PlayerRepository repository = newRepository();
        repository.loginOrRegister("alice", "pw1");
        repository.loginOrRegister("bob", "pw2");

        Room room = newRoom(repository);
        FakeWebSocket white = new FakeWebSocket();
        FakeWebSocket black = new FakeWebSocket();
        PlayerSession whiteSession = new PlayerSession("alice", 1200);
        PlayerSession blackSession = new PlayerSession("bob", 1200);
        room.seatMatch(white.socket(), whiteSession, black.socket(), blackSession);

        publishGameEnded(room, PieceColor.WHITE);

        assertTrue(whiteSession.getRating() > 1200);
        assertTrue(blackSession.getRating() < 1200);

        var persistedWinner = repository.loginOrRegister("alice", "pw1");
        assertEquals(whiteSession.getRating(), persistedWinner.rating);
    }

    @Test
    void testApplyEloIsIdempotentPerGame() throws Exception {
        PlayerRepository repository = newRepository();
        repository.loginOrRegister("alice", "pw1");
        repository.loginOrRegister("bob", "pw2");

        Room room = newRoom(repository);
        FakeWebSocket white = new FakeWebSocket();
        FakeWebSocket black = new FakeWebSocket();
        PlayerSession whiteSession = new PlayerSession("alice", 1200);
        PlayerSession blackSession = new PlayerSession("bob", 1200);
        room.seatMatch(white.socket(), whiteSession, black.socket(), blackSession);

        publishGameEnded(room, PieceColor.WHITE);
        int ratingAfterFirst = whiteSession.getRating();
        publishGameEnded(room, PieceColor.WHITE);

        assertEquals(ratingAfterFirst, whiteSession.getRating());
    }
}
