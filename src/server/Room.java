package server;

import common.GameResult;
import config.GameConfig;
import engine.GameEngine;
import enums.PieceColor;
import enums.PieceKind;
import enums.PlayerRole;
import events.Event;
import events.EventBus;
import events.GameEndedEvent;
import events.GameStartedEvent;
import events.MoveMadeEvent;
import events.PieceCapturedEvent;
import engine.GameFactory;
import model.Piece;
import org.java_websocket.WebSocket;
import protocol.JumpCommand;
import protocol.MoveCommand;
import protocol.StateCodec;
import server.logging.ServerLog;
import view.BoardSnapshot;
import view.BoardSnapshotFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Room {

    public final String roomId;

    private final GameFactory factory;
    private final GameEngine engine;
    private final RatingService ratingService;
    private final BoardSnapshotFactory snapshotFactory;
    private final ScheduledExecutorService scheduler;
    private final Object engineLock = new Object();

    private boolean ratingsAppliedForCurrentGame;
    private final List<String> eventHistory = new ArrayList<>();

    private PlayerSession whiteSession;
    private PlayerSession blackSession;
    private WebSocket whiteConn;
    private WebSocket blackConn;
    private final Map<WebSocket, PlayerSession> spectators = new LinkedHashMap<>();

    public Room(String roomId, PlayerRepository repository,
            ScheduledExecutorService scheduler, BoardSnapshotFactory snapshotFactory) {
        this.roomId = roomId;
        this.ratingService = new RatingService(repository);
        this.scheduler = scheduler;
        this.snapshotFactory = snapshotFactory;

        this.factory = new GameFactory();
        this.factory.initializeStandardBoard();
        this.engine = factory.getEngine();

        EventBus eventBus = factory.getEventBus();
        eventBus.subscribe(MoveMadeEvent.TYPE, this::broadcastEvent);
        eventBus.subscribe(PieceCapturedEvent.TYPE, this::broadcastEvent);
        eventBus.subscribe(GameStartedEvent.TYPE, this::broadcastEvent);
        eventBus.subscribe(GameEndedEvent.TYPE, this::broadcastEvent);
        eventBus.subscribe(GameEndedEvent.TYPE, this::applyEloOnGameEnd);
    }

    public void seatCreator(WebSocket conn, PlayerSession session) {
        synchronized (engineLock) {
            whiteConn = conn;
            whiteSession = session;
            broadcastAssignments();
        }
    }

    public void seatMatch(WebSocket whiteConn, PlayerSession whiteSession, WebSocket blackConn,
            PlayerSession blackSession) {
        synchronized (engineLock) {
            this.whiteConn = whiteConn;
            this.whiteSession = whiteSession;
            this.blackConn = blackConn;
            this.blackSession = blackSession;
            broadcastAssignments();
        }
    }

    public PlayerRole join(WebSocket conn, PlayerSession session) {
        synchronized (engineLock) {
            PlayerRole role;
            if (blackConn == null && whiteConn != null) {
                session.setColor(PieceColor.BLACK);
                blackConn = conn;
                blackSession = session;
                role = PlayerRole.BLACK;
            } else {
                spectators.put(conn, session);
                role = PlayerRole.SPECTATOR;
            }
            conn.send(currentStateJson());
            replayHistory(conn);
            broadcastAssignments();
            return role;
        }
    }

    public boolean isEmpty() {
        synchronized (engineLock) {
            return whiteConn == null && blackConn == null && spectators.isEmpty();
        }
    }

    public void removeConn(WebSocket conn) {
        synchronized (engineLock) {
            if (conn == whiteConn || conn == blackConn) {
                handlePlayerDisconnect(conn);
            } else {
                spectators.remove(conn);
            }
        }
    }

    private void handlePlayerDisconnect(WebSocket conn) {
        PlayerSession disconnected;
        WebSocket remaining;
        if (conn == whiteConn) {
            disconnected = whiteSession;
            remaining = blackConn;
            whiteConn = null;
        } else {
            disconnected = blackSession;
            remaining = whiteConn;
            blackConn = null;
        }

        disconnected.setState(SessionState.DISCONNECTED_PENDING);

        if (remaining != null) {
            remaining.send(StateCodec.encodeDisconnectCountdown(GameConfig.DISCONNECT_GRACE_SECONDS));
        }

        ScheduledFuture<?> timer = scheduler.schedule(() -> resolveAutoResign(disconnected),
                GameConfig.DISCONNECT_GRACE_SECONDS, TimeUnit.SECONDS);
        disconnected.setDisconnectTimer(timer);
    }

    private void resolveAutoResign(PlayerSession disconnected) {
        boolean resigned;
        synchronized (engineLock) {
            if (disconnected.getState() != SessionState.DISCONNECTED_PENDING) {
                resigned = false;
            } else {
                resigned = true;

                WebSocket remaining = disconnected == whiteSession ? blackConn : whiteConn;

                engine.setGameOver(true);

                if (remaining != null) {
                    String message = "Opponent disconnected. Game stopped.";
                    remaining.send(StateCodec.encodeOpponentDisconnected(message));
                    remaining.close(1000, message);
                }
                for (WebSocket spectatorConn : spectators.keySet()) {
                    spectatorConn.send(StateCodec.encodeOpponentDisconnected("A player disconnected. Game stopped."));
                }

                whiteConn = null;
                blackConn = null;
            }
        }
        if (resigned) {
            broadcastState();
            ServerLog.info("Room " + roomId + ": " + disconnected.getUsername()
                    + " disconnected, game stopped (no result, no rating change)");
        }
    }

    public void requestRestart(WebSocket conn) {
        synchronized (engineLock) {
            if (!engine.isGameOver())
                return;
            if (conn != whiteConn && conn != blackConn)
                return;

            factory.restartGame();
            ratingsAppliedForCurrentGame = false;
        }
        broadcastState();
    }

    public void reconnect(WebSocket conn, PlayerSession session, int rating) {
        synchronized (engineLock) {
            ScheduledFuture<?> timer = session.getDisconnectTimer();
            if (timer != null) {
                timer.cancel(false);
                session.setDisconnectTimer(null);
            }
            session.setRating(rating);
            session.setState(SessionState.PLAYING);

            if (session == whiteSession) {
                whiteConn = conn;
            } else {
                blackConn = conn;
            }
            replayHistory(conn);
            broadcastAssignments();
        }
    }

    public PlayerSession findDisconnectedSession(String username) {
        synchronized (engineLock) {
            if (whiteSession != null && whiteSession.getState() == SessionState.DISCONNECTED_PENDING
                    && whiteSession.getUsername().equals(username)) {
                return whiteSession;
            }
            if (blackSession != null && blackSession.getState() == SessionState.DISCONNECTED_PENDING
                    && blackSession.getUsername().equals(username)) {
                return blackSession;
            }
            return null;
        }
    }

    private void broadcastAssignments() {
        String whiteName = whiteSession == null ? null : whiteSession.getUsername();
        String blackName = blackSession == null ? null : blackSession.getUsername();
        int whiteRating = whiteSession == null ? 1200 : whiteSession.getRating();
        int blackRating = blackSession == null ? 1200 : blackSession.getRating();

        if (whiteConn != null)
            whiteConn.send(StateCodec.encodeAssign(PieceColor.WHITE, whiteName, blackName, whiteRating, blackRating));
        if (blackConn != null)
            blackConn.send(StateCodec.encodeAssign(PieceColor.BLACK, whiteName, blackName, whiteRating, blackRating));

        String spectateMsg = StateCodec.encodeSpectate(whiteName, blackName, whiteRating, blackRating);
        for (WebSocket spectatorConn : spectators.keySet()) {
            spectatorConn.send(spectateMsg);
        }
    }

    private void applyEloOnGameEnd(Event event) {
        PieceColor winnerColor = ((GameEndedEvent) event).getWinnerColor();
        if (winnerColor == null || whiteSession == null || blackSession == null || ratingsAppliedForCurrentGame) {
            return;
        }
        ratingsAppliedForCurrentGame = true;

        ratingService.applyGameEnd(winnerColor, whiteSession, blackSession);

        broadcastAssignments();
    }

    private boolean isAuthorized(WebSocket conn, PieceColor color) {
        return (color == PieceColor.WHITE && conn == whiteConn) || (color == PieceColor.BLACK && conn == blackConn);
    }

    private boolean isPausedForDisconnect() {
        return (whiteSession != null && whiteSession.getState() == SessionState.DISCONNECTED_PENDING)
                || (blackSession != null && blackSession.getState() == SessionState.DISCONNECTED_PENDING);
    }

    public boolean handleMove(WebSocket conn, String message) {
        synchronized (engineLock) {
            if (rejectIfPausedForDisconnect(conn))
                return false;

            MoveCommand command = MoveCommand.parse(message, engine.getBoardRows());
            if (command == null) {
                conn.send(StateCodec.encodeError("Malformed command: " + message));
                return false;
            }

            if (!isAuthorized(conn, command.color)) {
                conn.send(StateCodec.encodeError("You can only move your own pieces."));
                return false;
            }

            if (!piecePresentAndMatches(engine.pieceAt(command.from), command.color, command.kind, conn,
                    "No matching piece at source square")) {
                return false;
            }

            return respondToResult(conn, engine.requestMove(command.from, command.to));
        }
    }

    public boolean handleJump(WebSocket conn, String message) {
        synchronized (engineLock) {
            if (rejectIfPausedForDisconnect(conn))
                return false;

            JumpCommand command = JumpCommand.parse(message, engine.getBoardRows());
            if (command == null) {
                conn.send(StateCodec.encodeError("Malformed command: " + message));
                return false;
            }

            if (!isAuthorized(conn, command.color)) {
                conn.send(StateCodec.encodeError("You can only move your own pieces."));
                return false;
            }

            if (!piecePresentAndMatches(engine.pieceAt(command.position), command.color, command.kind, conn,
                    "No matching piece at jump square")) {
                return false;
            }

            return respondToResult(conn, engine.requestJump(command.position));
        }
    }

    private boolean rejectIfPausedForDisconnect(WebSocket conn) {
        if (isPausedForDisconnect()) {
            conn.send(StateCodec.encodeError("Waiting for opponent to reconnect..."));
            return true;
        }
        return false;
    }

    private boolean piecePresentAndMatches(Optional<Piece> piece, PieceColor color, PieceKind kind, WebSocket conn,
            String noPieceMessage) {
        if (!piece.isPresent() || piece.get().getColor() != color || piece.get().getKind() != kind) {
            conn.send(StateCodec.encodeError(noPieceMessage));
            return false;
        }
        return true;
    }

    private boolean respondToResult(WebSocket conn, GameResult<Void> result) {
        if (!result.isSuccess()) {
            conn.send(StateCodec.encodeError(result.message()));
            return false;
        }
        return true;
    }

    public void tick(long now, long elapsed) {
        synchronized (engineLock) {
            if (!engine.isGameOver() && !isPausedForDisconnect()) {
                engine.advanceClock(elapsed);
            }
        }
        broadcastState();
    }

    public void broadcastState() {
        synchronized (engineLock) {
            broadcastToRoom(currentStateJson());
        }
    }

    private void broadcastEvent(Event event) {
        String json = StateCodec.encodeEvent(event);
        if (event.getType().equals(MoveMadeEvent.TYPE) || event.getType().equals(PieceCapturedEvent.TYPE)) {
            eventHistory.add(json);
        } else if (event.getType().equals(GameStartedEvent.TYPE)) {
            eventHistory.clear();
        }
        broadcastToRoom(json);
    }

    private void replayHistory(WebSocket conn) {
        if (!eventHistory.isEmpty()) {
            conn.send(StateCodec.encodeHistory(eventHistory));
        }
    }

    private void broadcastToRoom(String json) {
        if (whiteConn != null && whiteConn.isOpen())
            whiteConn.send(json);
        if (blackConn != null && blackConn.isOpen())
            blackConn.send(json);
        for (WebSocket spectatorConn : spectators.keySet()) {
            if (spectatorConn.isOpen())
                spectatorConn.send(json);
        }
    }

    private String currentStateJson() {
        BoardSnapshot snapshot = snapshotFactory.capture(engine);
        return StateCodec.encodeState(snapshot, engine.isGameOver(), engine.getWinnerColor());
    }
}
