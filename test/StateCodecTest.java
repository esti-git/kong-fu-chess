import enums.PieceColor;
import enums.PieceKind;
import enums.PieceState;
import enums.PlayerRole;
import events.Event;
import events.GameEndedEvent;
import events.GameStartedEvent;
import events.MoveMadeEvent;
import events.PieceCapturedEvent;
import model.Position;
import org.junit.jupiter.api.Test;
import protocol.AssignedIdentity;
import protocol.LoginResult;
import protocol.NetworkState;
import protocol.RoomJoined;
import protocol.SpectateInfo;
import protocol.StateCodec;
import view.BoardSnapshot;
import view.CaptureSnapshot;
import view.PendingJumpSnapshot;
import view.PendingMoveSnapshot;
import view.PendingRestSnapshot;
import view.PieceSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StateCodecTest {

    private PieceSnapshot piece(int id, PieceColor color, PieceKind kind) {
        return new PieceSnapshot(id, color, kind, kind.name().substring(0, 1), PieceState.IDLE);
    }

    private BoardSnapshot emptySnapshot() {
        return new BoardSnapshot(2, 2, new PieceSnapshot[2][2],
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), 100L);
    }

    @Test
    void testEncodeDecodeStateRoundTripWithEmptyBoard() {
        String json = StateCodec.encodeState(emptySnapshot(), false, null);
        NetworkState decoded = StateCodec.decodeState(json);

        assertFalse(decoded.gameOver);
        assertNull(decoded.winnerColor);
        assertEquals(2, decoded.snapshot.getRows());
        assertEquals(2, decoded.snapshot.getCols());
        assertEquals(100L, decoded.snapshot.getGameClock());
    }

    @Test
    void testEncodeDecodeStateRoundTripWithGameOverAndWinner() {
        String json = StateCodec.encodeState(emptySnapshot(), true, PieceColor.BLACK);
        NetworkState decoded = StateCodec.decodeState(json);

        assertTrue(decoded.gameOver);
        assertEquals(PieceColor.BLACK, decoded.winnerColor);
    }

    @Test
    void testEncodeDecodeStateRoundTripWithPieceAndPendingActivity() {
        PieceSnapshot[][] cells = new PieceSnapshot[1][1];
        cells[0][0] = piece(1, PieceColor.WHITE, PieceKind.ROOK);

        List<PendingMoveSnapshot> moves = new ArrayList<>();
        moves.add(new PendingMoveSnapshot(0, 0, 0, 1, piece(2, PieceColor.BLACK, PieceKind.KNIGHT), 500L));

        List<PendingJumpSnapshot> jumps = new ArrayList<>();
        jumps.add(new PendingJumpSnapshot(1, 1, piece(3, PieceColor.WHITE, PieceKind.KING), 10L, 20L));

        List<PendingRestSnapshot> rests = new ArrayList<>();
        rests.add(new PendingRestSnapshot(piece(4, PieceColor.BLACK, PieceKind.QUEEN), 30L));

        List<CaptureSnapshot> captures = new ArrayList<>();
        captures.add(new CaptureSnapshot(PieceColor.WHITE, PieceKind.PAWN));

        BoardSnapshot snapshot = new BoardSnapshot(1, 1, cells, moves, jumps, rests, captures, 42L);
        String json = StateCodec.encodeState(snapshot, false, null);
        NetworkState decoded = StateCodec.decodeState(json);

        PieceSnapshot decodedPiece = decoded.snapshot.getPieceAt(0, 0);
        assertNotNull(decodedPiece);
        assertEquals(1, decodedPiece.getId());
        assertEquals(PieceColor.WHITE, decodedPiece.getColor());
        assertEquals(PieceKind.ROOK, decodedPiece.getKind());

        assertEquals(1, decoded.snapshot.getPendingMoves().size());
        assertEquals(500L, decoded.snapshot.getPendingMoves().get(0).getArrivalTime());

        assertEquals(1, decoded.snapshot.getPendingJumps().size());
        assertEquals(20L, decoded.snapshot.getPendingJumps().get(0).getEndTime());

        assertEquals(1, decoded.snapshot.getPendingRests().size());
        assertEquals(30L, decoded.snapshot.getPendingRests().get(0).getEndTime());

        assertEquals(1, decoded.snapshot.getCaptureLog().size());
        assertEquals(PieceColor.WHITE, decoded.snapshot.getCaptureLog().get(0).getCapturedColor());
    }

    @Test
    void testEncodeDecodeMoveMadeEvent() {
        MoveMadeEvent original = new MoveMadeEvent(PieceColor.WHITE, PieceKind.PAWN, "P",
                new Position(6, 4), new Position(4, 4), 8);
        String json = StateCodec.encodeEvent(original);
        Event decoded = StateCodec.decodeEvent(json);

        assertInstanceOf(MoveMadeEvent.class, decoded);
        MoveMadeEvent move = (MoveMadeEvent) decoded;
        assertEquals(PieceColor.WHITE, move.getPlayer());
        assertEquals(PieceKind.PAWN, move.getPieceKind());
        assertEquals("P", move.getPieceRepresentation());
        assertEquals(new Position(6, 4), move.getFrom());
        assertEquals(new Position(4, 4), move.getTo());
        assertEquals(8, move.getBoardRows());
    }

    @Test
    void testEncodeDecodePieceCapturedEvent() {
        PieceCapturedEvent original = new PieceCapturedEvent(PieceColor.BLACK, PieceKind.QUEEN, PieceColor.WHITE);
        Event decoded = StateCodec.decodeEvent(StateCodec.encodeEvent(original));

        assertInstanceOf(PieceCapturedEvent.class, decoded);
        PieceCapturedEvent captured = (PieceCapturedEvent) decoded;
        assertEquals(PieceColor.BLACK, captured.getCapturedColor());
        assertEquals(PieceKind.QUEEN, captured.getCapturedKind());
        assertEquals(PieceColor.WHITE, captured.getCapturedBy());
    }

    @Test
    void testEncodeDecodeGameStartedEvent() {
        Event decoded = StateCodec.decodeEvent(StateCodec.encodeEvent(new GameStartedEvent()));
        assertInstanceOf(GameStartedEvent.class, decoded);
    }

    @Test
    void testEncodeDecodeGameEndedEventWithWinner() {
        GameEndedEvent original = new GameEndedEvent(PieceColor.WHITE);
        Event decoded = StateCodec.decodeEvent(StateCodec.encodeEvent(original));

        assertInstanceOf(GameEndedEvent.class, decoded);
        assertEquals(PieceColor.WHITE, ((GameEndedEvent) decoded).getWinnerColor());
    }

    @Test
    void testEncodeDecodeGameEndedEventWithNullWinner() {
        GameEndedEvent original = new GameEndedEvent(null);
        Event decoded = StateCodec.decodeEvent(StateCodec.encodeEvent(original));

        assertNull(((GameEndedEvent) decoded).getWinnerColor());
    }

    @Test
    void testDecodeEventRejectsUnknownType() {
        assertThrows(IllegalArgumentException.class,
                () -> StateCodec.decodeEvent("{\"eventType\":\"NOT_A_TYPE\"}"));
    }

    @Test
    void testEncodeDecodeHistoryRoundTrip() {
        MoveMadeEvent move = new MoveMadeEvent(PieceColor.WHITE, PieceKind.PAWN, "P",
                new Position(6, 4), new Position(4, 4), 8);
        List<String> eventJsons = Collections.singletonList(StateCodec.encodeEvent(move));

        String historyJson = StateCodec.encodeHistory(eventJsons);
        List<Event> decoded = StateCodec.decodeHistoryEvents(historyJson);

        assertEquals(1, decoded.size());
        assertInstanceOf(MoveMadeEvent.class, decoded.get(0));
    }

    @Test
    void testEncodeErrorAndDecodeErrorMessage() {
        String json = StateCodec.encodeError("boom");
        assertEquals("boom", StateCodec.decodeErrorMessage(json));
        assertEquals("error", StateCodec.peekType(json));
    }

    @Test
    void testEncodeLoginAndDecodeParts() {
        String json = StateCodec.encodeLogin("alice", "hunter2");
        assertEquals("alice", StateCodec.decodeLoginUsername(json));
        assertEquals("hunter2", StateCodec.decodeLoginPassword(json));
    }

    @Test
    void testEncodeDecodeLoginResultSuccess() {
        String json = StateCodec.encodeLoginResult(true, 1350, null, true);
        LoginResult result = StateCodec.decodeLoginResult(json);

        assertTrue(result.success);
        assertEquals(1350, result.rating);
        assertNull(result.message);
        assertTrue(result.reconnected);
    }

    @Test
    void testEncodeDecodeLoginResultFailureWithMessage() {
        String json = StateCodec.encodeLoginResult(false, 0, "bad password", false);
        LoginResult result = StateCodec.decodeLoginResult(json);

        assertFalse(result.success);
        assertEquals("bad password", result.message);
        assertFalse(result.reconnected);
    }

    @Test
    void testEncodeDecodeAssignRoundTrip() {
        String json = StateCodec.encodeAssign(PieceColor.WHITE, "alice", "bob", 1200, 1250);
        AssignedIdentity assigned = StateCodec.decodeAssign(json);

        assertEquals(PieceColor.WHITE, assigned.color);
        assertEquals("alice", assigned.whiteName);
        assertEquals("bob", assigned.blackName);
        assertEquals(1200, assigned.whiteRating);
        assertEquals(1250, assigned.blackRating);
    }

    @Test
    void testEncodeDecodeAssignWithNullNames() {
        String json = StateCodec.encodeAssign(PieceColor.BLACK, null, null, 1200, 1200);
        AssignedIdentity assigned = StateCodec.decodeAssign(json);

        assertNull(assigned.whiteName);
        assertNull(assigned.blackName);
    }

    @Test
    void testEncodeDecodeRoomJoinedRoundTrip() {
        String json = StateCodec.encodeRoomJoined("ABC123", PlayerRole.SPECTATOR);
        RoomJoined joined = StateCodec.decodeRoomJoined(json);

        assertEquals("ABC123", joined.roomId);
        assertEquals(PlayerRole.SPECTATOR, joined.role);
    }

    @Test
    void testEncodeDecodeSpectateRoundTrip() {
        String json = StateCodec.encodeSpectate("alice", "bob", 1100, 1400);
        SpectateInfo info = StateCodec.decodeSpectate(json);

        assertEquals("alice", info.whiteName);
        assertEquals("bob", info.blackName);
        assertEquals(1100, info.whiteRating);
        assertEquals(1400, info.blackRating);
    }

    @Test
    void testDecodeDisconnectCountdownSeconds() {
        String json = StateCodec.encodeDisconnectCountdown(45);
        assertEquals(45, StateCodec.decodeDisconnectCountdownSeconds(json));
    }

    @Test
    void testDecodeCreateRoomAndJoinRoomIds() {
        assertEquals("MYROOM", StateCodec.decodeCreateRoomId(StateCodec.encodeCreateRoom("MYROOM")));
        assertEquals("", StateCodec.decodeCreateRoomId(StateCodec.encodeCreateRoom(null)));
        assertEquals("MYROOM", StateCodec.decodeJoinRoomId(StateCodec.encodeJoinRoom("MYROOM")));
    }

    @Test
    void testPeekTypeForVariousMessages() {
        assertEquals("seek", StateCodec.peekType(StateCodec.encodeSeek()));
        assertEquals("playAgain", StateCodec.peekType(StateCodec.encodePlayAgain()));
        assertEquals("seekTimeout", StateCodec.peekType(StateCodec.encodeSeekTimeout("timed out")));
        assertEquals("rejected", StateCodec.peekType(StateCodec.encodeRejected("nope")));
        assertEquals("opponentDisconnected", StateCodec.peekType(StateCodec.encodeOpponentDisconnected("bye")));
        assertEquals("roomError", StateCodec.peekType(StateCodec.encodeRoomError("no such room")));
    }

    @Test
    void testPeekTypeReturnsNullForGarbageInput() {
        assertNull(StateCodec.peekType("not json at all"));
    }
}
