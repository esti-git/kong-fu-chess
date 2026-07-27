package model;

import config.GameConfig;
import lombok.Getter;

@Getter
public class PendingJump {
    private final int row;
    private final int col;
    private final Piece piece;
    private final long startTime;
    private final long endTime;

    public PendingJump(int row, int col, Piece piece, long startTime) {
        this.row = row;
        this.col = col;
        this.piece = piece;
        this.startTime = startTime;
        this.endTime = startTime + GameConfig.JUMP_DURATION_MS;
    }
}
