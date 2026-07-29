package server;

import config.GameConfig;
import enums.PieceColor;
import enums.PlayerRole;
import org.java_websocket.WebSocket;
import protocol.JumpCommand;
import protocol.LoginResult;
import protocol.StateCodec;
import server.logging.ServerLog;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ServerController {

    private static final int MAX_ROOM_NAME_LENGTH = GameConfig.MAX_ROOM_NAME_LENGTH;

    private final PlayerRepository repository;
    private final SessionRegistry sessionRegistry;
    private final Matchmaker matchmaker;
    private final RoomRegistry roomRegistry;
    private final MatchService matchService;
    private final ScheduledExecutorService scheduler;
    private final PlayerRegistry playerRegistry;
    private final MatchmakerClient matchmakerClient;
    private final Map<WebSocket, ScheduledFuture<?>> remoteSeekTimeouts = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public ServerController(PlayerRepository repository, SessionRegistry sessionRegistry, Matchmaker matchmaker,
            RoomRegistry roomRegistry, MatchService matchService, ScheduledExecutorService scheduler,
            PlayerRegistry playerRegistry, MatchmakerClient matchmakerClient) {
        this.repository = repository;
        this.sessionRegistry = sessionRegistry;
        this.matchmaker = matchmaker;
        this.roomRegistry = roomRegistry;
        this.matchService = matchService;
        this.scheduler = scheduler;
        this.playerRegistry = playerRegistry;
        this.matchmakerClient = matchmakerClient;
    }

    public void handleDisconnect(WebSocket conn) {
        try {
            PlayerSession session;
            synchronized (lock) {
                matchmaker.remove(conn);
                ScheduledFuture<?> remoteTimeout = remoteSeekTimeouts.remove(conn);
                if (remoteTimeout != null) {
                    remoteTimeout.cancel(false);
                }
                session = sessionRegistry.get(conn);
                sessionRegistry.remove(conn);
            }
            if (session != null) {
                playerRegistry.markOffline(session.getUsername());
                if (session.getState() == SessionState.SEEKING) {
                    matchmakerClient.cancel(session.getUsername());
                }
            }

            Room room = roomRegistry.roomFor(conn);
            if (room != null) {
                room.removeConn(conn);
                roomRegistry.unbind(conn);
                roomRegistry.removeIfEmpty(room.roomId);
            }
        } catch (Exception e) {
            ServerLog.error("Failed to clean up disconnected connection", e);
        }
    }

    public void handleMessage(WebSocket conn, String message) {
        try {
            String trimmed = message.trim();
            if (trimmed.startsWith("{")) {
                // "seek" is dispatched outside the global lock: it calls the standalone
                // Matchmaker service over HTTP, and holding this lock for that network round
                // trip would stall every other connection's login/room/move handling.
                if ("seek".equals(StateCodec.peekType(trimmed))) {
                    handleSeek(conn);
                } else {
                    synchronized (lock) {
                        handleControlMessage(conn, trimmed);
                    }
                }
                return;
            }

            Room room = roomRegistry.roomFor(conn);
            if (room == null) {
                conn.send(StateCodec.encodeError("Not in a game."));
                return;
            }
            boolean ok = JumpCommand.isJumpCommand(trimmed) ? room.handleJump(conn, trimmed) : room.handleMove(conn, trimmed);
            if (ok) {
                room.broadcastState();
            }
        } catch (Exception e) {
            ServerLog.error("Failed to handle message: " + message, e);
            conn.send(StateCodec.encodeError("Server error while processing your request."));
        }
    }

    private void handleControlMessage(WebSocket conn, String message) {
        String type = StateCodec.peekType(message);
        if ("login".equals(type)) {
            handleLogin(conn, StateCodec.decodeLoginUsername(message), StateCodec.decodeLoginPassword(message));
        } else if ("createRoom".equals(type)) {
            handleCreateRoom(conn, StateCodec.decodeCreateRoomId(message));
        } else if ("joinRoom".equals(type)) {
            handleJoinRoom(conn, StateCodec.decodeJoinRoomId(message));
        } else if ("playAgain".equals(type)) {
            handlePlayAgain(conn);
        }
    }

    private void handlePlayAgain(WebSocket conn) {
        Room room = roomRegistry.roomFor(conn);
        if (room != null) {
            room.requestRestart(conn);
        }
    }

    private void handleLogin(WebSocket conn, String username, String password) {
        String name = (username == null || username.isBlank()) ? "Player" : username;

        LoginResult result = repository.loginOrRegister(name, password);
        if (!result.success) {
            conn.send(StateCodec.encodeLoginResult(false, 0, result.message, false));
            conn.close();
            ServerLog.warn("Login failed for " + name + ": " + result.message);
            return;
        }

        Room reconnectRoom = roomRegistry.findRoomWithDisconnectedUser(name);
        if (reconnectRoom != null) {
            handleReconnectLogin(conn, name, result, reconnectRoom);
        } else {
            handleFreshLogin(conn, name, result);
        }
    }

    private void handleReconnectLogin(WebSocket conn, String name, LoginResult result, Room reconnectRoom) {
        PlayerSession session = reconnectRoom.findDisconnectedSession(name);
        reconnectRoom.reconnect(conn, session, result.rating);
        sessionRegistry.put(conn, session);
        roomRegistry.bind(conn, reconnectRoom.roomId);
        playerRegistry.markInRoom(name, GameConfig.SHARD_ID, reconnectRoom.roomId);
        conn.send(StateCodec.encodeLoginResult(true, result.rating, null, true));
        ServerLog.info(name + " reconnected to room " + reconnectRoom.roomId);
    }

    private void handleFreshLogin(WebSocket conn, String name, LoginResult result) {
        PlayerSession session = new PlayerSession(name, result.rating);
        sessionRegistry.put(conn, session);
        playerRegistry.markOnShard(name, GameConfig.SHARD_ID);
        conn.send(StateCodec.encodeLoginResult(true, result.rating, null, false));
        ServerLog.info("Login: " + name + " (rating " + result.rating + ")");
    }

    private void handleSeek(WebSocket conn) {
        PlayerSession session;
        synchronized (lock) {
            session = sessionRegistry.get(conn);
            if (session == null || session.getState() != SessionState.IDLE) {
                ServerLog.warn("Seek rejected: no active session or session not idle");
                return;
            }
            session.setState(SessionState.SEEKING);
        }
        seekAndSeat(conn, session);
    }

    /**
     * Runs the (blocking, unlocked) matchmaker HTTP round trip for {@code conn}/{@code session}
     * and seats the result. Used both for a fresh seek and to re-queue an opponent whose match
     * fell through because the other side disconnected mid-seek (see the {@code selfStillActive}
     * check below).
     */
    private void seekAndSeat(WebSocket conn, PlayerSession session) {
        MatchmakerClient.SeekResult result;
        try {
            result = matchmakerClient.seek(session.getUsername(), session.getRating());
        } catch (Exception e) {
            ServerLog.warn("Matchmaker service unreachable, falling back to local matching: " + e.getMessage());
            synchronized (lock) {
                matchmaker.addWaiting(conn, session, () -> handleSeekTimeout(conn));
                matchService.tryPromoteFromQueue();
            }
            return;
        }

        if (result.matched()) {
            boolean selfStillActive;
            WebSocket opponentConn;
            PlayerSession opponentSession;
            synchronized (lock) {
                // conn may have disconnected while the HTTP call above was in flight; seating it
                // anyway would strand the opponent with an already-closed connection.
                selfStillActive = conn.isOpen() && sessionRegistry.get(conn) == session;
                opponentConn = sessionRegistry.findConnByUsername(result.opponent());
                opponentSession = opponentConn == null ? null : sessionRegistry.get(opponentConn);
                if (selfStillActive && opponentConn != null && opponentSession != null) {
                    matchService.seatMatchedPair(conn, session, opponentConn, opponentSession);
                    return;
                }
            }
            if (!selfStillActive) {
                ServerLog.warn(session.getUsername() + " disconnected before its match with " + result.opponent()
                        + " could be seated; re-queuing " + result.opponent());
                if (opponentConn != null && opponentSession != null) {
                    requeueAfterAbandonedMatch(opponentConn, opponentSession);
                }
            } else {
                ServerLog.warn("Matchmaker matched " + session.getUsername() + " with " + result.opponent()
                        + " but no local connection was found for them");
            }
            return;
        }

        synchronized (lock) {
            ScheduledFuture<?> timeout = scheduler.schedule(() -> handleRemoteSeekTimeout(conn, session.getUsername()),
                    GameConfig.SEEK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            remoteSeekTimeouts.put(conn, timeout);
        }
    }

    private void requeueAfterAbandonedMatch(WebSocket opponentConn, PlayerSession opponentSession) {
        synchronized (lock) {
            ScheduledFuture<?> pending = remoteSeekTimeouts.remove(opponentConn);
            if (pending != null) {
                pending.cancel(false);
            }
        }
        seekAndSeat(opponentConn, opponentSession);
    }

    private void handleRemoteSeekTimeout(WebSocket conn, String username) {
        synchronized (lock) {
            if (remoteSeekTimeouts.remove(conn) == null) {
                return;
            }
            if (!matchmakerClient.cancel(username)) {
                return;
            }
            PlayerSession session = sessionRegistry.get(conn);
            if (session != null) {
                session.setState(SessionState.IDLE);
            }
            ServerLog.info(username + " seek timed out, no match found");
            conn.send(StateCodec.encodeSeekTimeout("Couldn't find a match. Try again."));
        }
    }

    private void handleSeekTimeout(WebSocket conn) {
        synchronized (lock) {
            if (!matchmaker.isWaiting(conn)) {
                return;
            }
            PlayerSession session = matchmaker.remove(conn);
            if (session != null) {
                session.setState(SessionState.IDLE);
                ServerLog.info(session.getUsername() + " seek timed out, no match found");
            }
            conn.send(StateCodec.encodeSeekTimeout("Couldn't find a match. Try again."));
        }
    }

    private PlayerSession requireIdleSession(WebSocket conn, String errorMessage) {
        PlayerSession session = sessionRegistry.get(conn);
        if (session == null || session.getState() != SessionState.IDLE) {
            ServerLog.warn("Request rejected: " + errorMessage);
            conn.send(StateCodec.encodeRoomError(errorMessage));
            return null;
        }
        return session;
    }

    private void handleCreateRoom(WebSocket conn, String desiredRoomId) {
        PlayerSession session = requireIdleSession(conn, "Log in before creating a room.");
        if (session == null) return;

        String normalizedId = RoomRegistry.normalizeRoomId(desiredRoomId);
        Room room;
        if (normalizedId.isEmpty()) {
            room = roomRegistry.createRoom(repository, scheduler);
        } else if (normalizedId.length() > MAX_ROOM_NAME_LENGTH) {
            ServerLog.warn("Create room rejected: name too long (" + normalizedId.length() + " chars)");
            conn.send(StateCodec.encodeRoomError("Room name is too long (max " + MAX_ROOM_NAME_LENGTH + " characters)."));
            return;
        } else {
            room = roomRegistry.createRoomWithId(normalizedId, repository, scheduler);
            if (room == null) {
                ServerLog.warn("Create room rejected: \"" + normalizedId + "\" already in use");
                conn.send(StateCodec.encodeRoomError("Room name \"" + normalizedId + "\" is already in use."));
                return;
            }
        }

        MatchService.assignAndActivate(session, PieceColor.WHITE);
        room.seatCreator(conn, session);
        roomRegistry.bind(conn, room.roomId);
        playerRegistry.markInRoom(session.getUsername(), GameConfig.SHARD_ID, room.roomId);
        conn.send(StateCodec.encodeRoomJoined(room.roomId, PlayerRole.WHITE));
        ServerLog.info(session.getUsername() + " created room " + room.roomId);
    }

    private void handleJoinRoom(WebSocket conn, String roomId) {
        PlayerSession session = requireIdleSession(conn, "Log in before joining a room.");
        if (session == null) return;

        String normalizedId = RoomRegistry.normalizeRoomId(roomId);
        Room room = roomRegistry.get(normalizedId);
        if (room == null) {
            ServerLog.warn("Join room rejected: room not found: " + roomId);
            conn.send(StateCodec.encodeRoomError("Room not found: " + roomId));
            return;
        }

        PlayerRole role = room.join(conn, session);
        session.setState(SessionState.PLAYING);
        roomRegistry.bind(conn, room.roomId);
        playerRegistry.markInRoom(session.getUsername(), GameConfig.SHARD_ID, room.roomId);
        conn.send(StateCodec.encodeRoomJoined(room.roomId, role));
        ServerLog.info(session.getUsername() + " joined room " + room.roomId + " as " + role);
    }
}
