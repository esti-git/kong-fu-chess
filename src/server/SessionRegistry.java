package server;

import org.java_websocket.WebSocket;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SessionRegistry {

    private final Map<WebSocket, PlayerSession> sessionsByConn = new ConcurrentHashMap<>();

    public PlayerSession get(WebSocket conn) {
        return sessionsByConn.get(conn);
    }

    public void put(WebSocket conn, PlayerSession session) {
        sessionsByConn.put(conn, session);
    }

    public void remove(WebSocket conn) {
        sessionsByConn.remove(conn);
    }

    public WebSocket findConnByUsername(String username) {
        for (Map.Entry<WebSocket, PlayerSession> entry : sessionsByConn.entrySet()) {
            if (entry.getValue().getUsername().equals(username)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
