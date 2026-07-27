package model;

public interface Board {
    Piece getPieceAt(Position pos);
    void addPiece(Position pos, Piece piece);
    Piece removePiece(Position pos);
    boolean isEmpty(Position pos);
    void clearCellOnly(Position position);
    boolean isValidPosition(Position pos);
    int getRows();
    int getCols();
}