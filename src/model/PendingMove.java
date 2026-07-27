package model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PendingMove {
    private final int fromRow;
    private final int fromCol;
    private final int toRow;
    private final int toCol;
    private final Piece piece;
    private final long arrivalTime;
}
