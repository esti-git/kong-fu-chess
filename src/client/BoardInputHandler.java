package client;

import model.Position;
import view.BoardMapper;
import view.BoardSnapshot;
import view.PieceSnapshot;
import view.SelectionState;

public class BoardInputHandler {

    private final SelectionState selection = new SelectionState();

    public Position getSelected() {
        return selection.get();
    }

    public void handleClick(GameClient client, BoardSnapshot snapshot, boolean gameOver, boolean spectator,
                             int pixelX, int pixelY, Runnable onChange) {
        if (snapshot == null || client == null || gameOver || spectator) return;

        Position clicked = BoardMapper.pixelToCell(pixelX, pixelY, snapshot.getRows(), snapshot.getCols()).orElse(null);
        if (clicked == null) return;

        if (!selection.isSelected()) {
            if (snapshot.getPieceAt(clicked.getRow(), clicked.getCol()) != null) {
                selection.select(clicked);
            }
            onChange.run();
            return;
        }

        if (selection.isSameAsSelected(clicked)) {
            selection.clear();
            onChange.run();
            return;
        }

        Position selectedPosition = selection.get();
        PieceSnapshot selectedPiece = snapshot.getPieceAt(selectedPosition.getRow(), selectedPosition.getCol());
        if (selectedPiece != null) {
            client.sendMove(selectedPiece.getColor(), selectedPiece.getKind(), selectedPosition, clicked, snapshot.getRows());
        }

        selection.clear();
        onChange.run();
    }

    public void handleDoubleClick(GameClient client, BoardSnapshot snapshot, boolean gameOver, boolean spectator,
                                   int pixelX, int pixelY, Runnable onChange) {
        if (snapshot == null || client == null || gameOver || spectator) return;

        Position clicked = BoardMapper.pixelToCell(pixelX, pixelY, snapshot.getRows(), snapshot.getCols()).orElse(null);
        if (clicked == null) return;

        PieceSnapshot piece = snapshot.getPieceAt(clicked.getRow(), clicked.getCol());
        if (piece != null) {
            client.sendJump(piece.getColor(), piece.getKind(), clicked, snapshot.getRows());
        }

        selection.clear();
        onChange.run();
    }
}
