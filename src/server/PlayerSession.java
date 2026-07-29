package server;

import enums.PieceColor;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.ScheduledFuture;

@Getter
public class PlayerSession {
    private final String username;
    @Getter(AccessLevel.NONE)
    @Setter
    private PieceColor color;
    @Setter
    private int rating;
    @Setter
    private volatile SessionState state = SessionState.IDLE;
    @Setter
    private ScheduledFuture<?> disconnectTimer;

    public PlayerSession(String username, int rating) {
        this.username = username;
        this.rating = rating;
    }
}
