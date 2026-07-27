package client;

import client.logging.ClientLog;
import enums.PieceColor;
import enums.PieceKind;
import events.Event;
import model.Position;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import protocol.JumpCommand;
import protocol.MoveCommand;
import protocol.PieceCodes;
import protocol.RoomJoined;
import protocol.SpectateInfo;
import protocol.StateCodec;

import java.net.URI;
import java.util.function.Consumer;

public class GameClient extends WebSocketClient {

    private final String username;
    private final String password;
    private final Consumer<protocol.NetworkState> onState;
    private final Consumer<String> onError;
    private final Consumer<Event> onEvent;
    private final Consumer<protocol.AssignedIdentity> onAssign;
    private final Consumer<String> onRejected;
    private final Consumer<String> onOpponentDisconnected;
    private final Consumer<String> onConnectionClosed;
    private final Consumer<protocol.LoginResult> onLoginResult;
    private final Consumer<String> onSeekTimeout;
    private final Consumer<Integer> onDisconnectCountdown;
    private final Consumer<RoomJoined> onRoomJoined;
    private final Consumer<String> onRoomError;
    private final Consumer<SpectateInfo> onSpectate;
    private final Consumer<java.util.List<Event>> onHistory;

    public GameClient(URI serverUri, String username, String password, Consumer<protocol.NetworkState> onState,
                       Consumer<String> onError, Consumer<Event> onEvent, Consumer<protocol.AssignedIdentity> onAssign,
                       Consumer<String> onRejected, Consumer<String> onOpponentDisconnected,
                       Consumer<String> onConnectionClosed, Consumer<protocol.LoginResult> onLoginResult,
                       Consumer<String> onSeekTimeout, Consumer<Integer> onDisconnectCountdown,
                       Consumer<RoomJoined> onRoomJoined, Consumer<String> onRoomError, Consumer<SpectateInfo> onSpectate,
                       Consumer<java.util.List<Event>> onHistory) {
        super(serverUri);
        this.username = username;
        this.password = password;
        this.onState = onState;
        this.onError = onError;
        this.onEvent = onEvent;
        this.onAssign = onAssign;
        this.onRejected = onRejected;
        this.onOpponentDisconnected = onOpponentDisconnected;
        this.onConnectionClosed = onConnectionClosed;
        this.onLoginResult = onLoginResult;
        this.onSeekTimeout = onSeekTimeout;
        this.onDisconnectCountdown = onDisconnectCountdown;
        this.onRoomJoined = onRoomJoined;
        this.onRoomError = onRoomError;
        this.onSpectate = onSpectate;
        this.onHistory = onHistory;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        ClientLog.info("Connected to game server");
        send(StateCodec.encodeLogin(username, password));
    }

    @Override
    public void onMessage(String message) {
        String type = StateCodec.peekType(message);
        if ("state".equals(type)) {
            onState.accept(StateCodec.decodeState(message));
        } else if ("error".equals(type)) {
            onError.accept(StateCodec.decodeErrorMessage(message));
        } else if ("event".equals(type)) {
            onEvent.accept(StateCodec.decodeEvent(message));
        } else if ("assign".equals(type)) {
            onAssign.accept(StateCodec.decodeAssign(message));
        } else if ("rejected".equals(type)) {
            onRejected.accept(StateCodec.decodeErrorMessage(message));
        } else if ("opponentDisconnected".equals(type)) {
            onOpponentDisconnected.accept(StateCodec.decodeErrorMessage(message));
        } else if ("loginResult".equals(type)) {
            onLoginResult.accept(StateCodec.decodeLoginResult(message));
        } else if ("seekTimeout".equals(type)) {
            onSeekTimeout.accept(StateCodec.decodeErrorMessage(message));
        } else if ("disconnectCountdown".equals(type)) {
            onDisconnectCountdown.accept(StateCodec.decodeDisconnectCountdownSeconds(message));
        } else if ("roomJoined".equals(type)) {
            onRoomJoined.accept(StateCodec.decodeRoomJoined(message));
        } else if ("roomError".equals(type)) {
            onRoomError.accept(StateCodec.decodeErrorMessage(message));
        } else if ("spectate".equals(type)) {
            onSpectate.accept(StateCodec.decodeSpectate(message));
        } else if ("history".equals(type)) {
            onHistory.accept(StateCodec.decodeHistoryEvents(message));
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        ClientLog.info("Disconnected from game server: " + reason);
        onConnectionClosed.accept(reason);
    }

    @Override
    public void onError(Exception ex) {
        ClientLog.error("WebSocket error", ex);
    }

    public void sendSeek() {
        send(StateCodec.encodeSeek());
    }

    public void sendPlayAgain() {
        send(StateCodec.encodePlayAgain());
    }

    public void sendCreateRoom(String desiredRoomId) {
        send(StateCodec.encodeCreateRoom(desiredRoomId));
    }

    public void sendJoinRoom(String roomId) {
        send(StateCodec.encodeJoinRoom(roomId));
    }

    public void sendMove(PieceColor color, PieceKind kind, Position from, Position to, int boardRows) {
        String command = "" + PieceCodes.colorChar(color) + PieceCodes.kindChar(kind)
                + MoveCommand.squareName(from.getRow(), from.getCol(), boardRows)
                + MoveCommand.squareName(to.getRow(), to.getCol(), boardRows);
        send(command);
    }

    public void sendJump(PieceColor color, PieceKind kind, Position position, int boardRows) {
        send(JumpCommand.build(color, kind, position.getRow(), position.getCol(), boardRows));
    }
}
