import board.MatrixBoard;
import common.GameResult;
import enums.PieceState;
import io.BoardParser;
import model.Piece;
import model.PendingJump;
import model.PendingMove;
import model.Position;
import org.junit.jupiter.api.Test;
import rules.RuleEngine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuleEngineTest {

    private static final int SIZE = 8;
    private final RuleEngine ruleEngine = new RuleEngine();

    /** Builds an 8x8 board where each entry is "row,col=wR" style placement; everything else is empty. */
    private MatrixBoard buildBoard(String... placements) {
        String[][] grid = new String[SIZE][SIZE];
        for (String[] row : grid) Arrays.fill(row, ".");
        for (String placement : placements) {
            String[] parts = placement.split("=");
            String[] coords = parts[0].split(",");
            int row = Integer.parseInt(coords[0]);
            int col = Integer.parseInt(coords[1]);
            grid[row][col] = parts[1];
        }
        List<String> rawLines = new ArrayList<>();
        for (String[] row : grid) rawLines.add(String.join(" ", row));

        BoardParser parser = new BoardParser();
        MatrixBoard board = new MatrixBoard();
        board.initialize(parser.parse(rawLines), parser.parseRows(rawLines), parser.parseCols(rawLines));
        return board;
    }

    private Position pos(int row, int col) { return new Position(row, col); }

    @Test
    void testMoveToSamePositionFails() {
        MatrixBoard board = buildBoard("3,3=wR");
        GameResult<Void> result = ruleEngine.validateMove(board, pos(3, 3), pos(3, 3), List.of(), List.of());
        assertFalse(result.isSuccess());
        assertEquals("Move must change position", result.message());
    }

    @Test
    void testNoPieceAtSourceFails() {
        MatrixBoard board = buildBoard();
        GameResult<Void> result = ruleEngine.validateMove(board, pos(3, 3), pos(4, 3), List.of(), List.of());
        assertFalse(result.isSuccess());
        assertEquals("No piece selected", result.message());
    }

    @Test
    void testBusyPieceCannotMove() {
        MatrixBoard board = buildBoard("3,3=wR");
        board.getPieceAt(pos(3, 3)).setState(PieceState.MOVING);
        GameResult<Void> result = ruleEngine.validateMove(board, pos(3, 3), pos(3, 5), List.of(), List.of());
        assertFalse(result.isSuccess());
        assertEquals("Piece cannot act right now", result.message());
    }

    @Test
    void testDestinationOccupiedByFriendlyFails() {
        MatrixBoard board = buildBoard("3,3=wR", "3,5=wP");
        GameResult<Void> result = ruleEngine.validateMove(board, pos(3, 3), pos(3, 5), List.of(), List.of());
        assertFalse(result.isSuccess());
        assertEquals("Destination is occupied by a friendly piece", result.message());
    }

    @Test
    void testIllegalMovementPatternFails() {
        MatrixBoard board = buildBoard("3,3=wR");
        GameResult<Void> result = ruleEngine.validateMove(board, pos(3, 3), pos(5, 5), List.of(), List.of());
        assertFalse(result.isSuccess());
        assertEquals("Movement pattern is illegal", result.message());
    }

    @Test
    void testSlidingPieceBlockedByBoardPieceFails() {
        MatrixBoard board = buildBoard("3,0=wR", "3,4=bP");
        GameResult<Void> result = ruleEngine.validateMove(board, pos(3, 0), pos(3, 7), List.of(), List.of());
        assertFalse(result.isSuccess());
        assertEquals("Path is blocked", result.message());
    }

    @Test
    void testSlidingPieceClearPathSucceeds() {
        MatrixBoard board = buildBoard("3,0=wR");
        GameResult<Void> result = ruleEngine.validateMove(board, pos(3, 0), pos(3, 7), List.of(), List.of());
        assertTrue(result.isSuccess());
    }

    @Test
    void testSlidingPieceBlockedByPendingMoveFails() {
        MatrixBoard board = buildBoard("3,0=wR", "0,0=bP");
        Piece blocker = board.getPieceAt(pos(0, 0));
        List<PendingMove> pendingMoves = List.of(new PendingMove(0, 0, 3, 4, blocker, 1000L));
        GameResult<Void> result = ruleEngine.validateMove(board, pos(3, 0), pos(3, 7), pendingMoves, List.of());
        assertFalse(result.isSuccess());
        assertEquals("Path is blocked", result.message());
    }

    @Test
    void testNonSlidingPieceIgnoresBlockedPath() {
        // Knights jump over pieces, so a blocked path between source and destination is irrelevant.
        MatrixBoard board = buildBoard("4,4=wN", "5,4=bP", "5,5=bP");
        GameResult<Void> result = ruleEngine.validateMove(board, pos(4, 4), pos(6, 5), List.of(), List.of());
        assertTrue(result.isSuccess());
    }

    @Test
    void testPawnForwardMoveBlockedFails() {
        MatrixBoard board = buildBoard("6,4=wP", "5,4=bP");
        GameResult<Void> result = ruleEngine.validateMove(board, pos(6, 4), pos(5, 4), List.of(), List.of());
        assertFalse(result.isSuccess());
        assertEquals("Forward move is blocked", result.message());
    }

    @Test
    void testPawnForwardMoveSucceeds() {
        MatrixBoard board = buildBoard("6,4=wP");
        GameResult<Void> result = ruleEngine.validateMove(board, pos(6, 4), pos(5, 4), List.of(), List.of());
        assertTrue(result.isSuccess());
    }

    @Test
    void testPawnDoubleStepNotFromStartRowFails() {
        // Pawn.isMovementPatternLegal already gates the two-square move on the starting row,
        // so this is rejected as an illegal pattern before RuleEngine's own start-row check runs.
        MatrixBoard board = buildBoard("5,4=wP");
        GameResult<Void> result = ruleEngine.validateMove(board, pos(5, 4), pos(3, 4), List.of(), List.of());
        assertFalse(result.isSuccess());
        assertEquals("Movement pattern is illegal", result.message());
    }

    @Test
    void testPawnDoubleStepMiddleSquareBlockedFails() {
        MatrixBoard board = buildBoard("6,4=wP", "5,4=bP");
        GameResult<Void> result = ruleEngine.validateMove(board, pos(6, 4), pos(4, 4), List.of(), List.of());
        assertFalse(result.isSuccess());
        assertEquals("Pawn double-step path is blocked", result.message());
    }

    @Test
    void testPawnDoubleStepDestinationBlockedFails() {
        MatrixBoard board = buildBoard("6,4=wP", "4,4=bP");
        GameResult<Void> result = ruleEngine.validateMove(board, pos(6, 4), pos(4, 4), List.of(), List.of());
        assertFalse(result.isSuccess());
        assertEquals("Pawn double-step path is blocked", result.message());
    }

    @Test
    void testPawnDoubleStepBlockedByPendingMoveFails() {
        MatrixBoard board = buildBoard("6,4=wP", "0,0=bP");
        Piece other = board.getPieceAt(pos(0, 0));
        List<PendingMove> pendingMoves = List.of(new PendingMove(0, 0, 5, 4, other, 1000L));
        GameResult<Void> result = ruleEngine.validateMove(board, pos(6, 4), pos(4, 4), pendingMoves, List.of());
        assertFalse(result.isSuccess());
        assertEquals("Pawn double-step path is blocked by a pending move", result.message());
    }

    @Test
    void testPawnDoubleStepSucceeds() {
        MatrixBoard board = buildBoard("6,4=wP");
        GameResult<Void> result = ruleEngine.validateMove(board, pos(6, 4), pos(4, 4), List.of(), List.of());
        assertTrue(result.isSuccess());
    }

    @Test
    void testPawnDiagonalCaptureWithoutTargetFails() {
        MatrixBoard board = buildBoard("5,4=wP");
        GameResult<Void> result = ruleEngine.validateMove(board, pos(5, 4), pos(4, 5), List.of(), List.of());
        assertFalse(result.isSuccess());
        assertEquals("Pawn capture requires a target piece", result.message());
    }

    @Test
    void testPawnDiagonalCaptureWithTargetSucceeds() {
        MatrixBoard board = buildBoard("5,4=wP", "4,5=bP");
        GameResult<Void> result = ruleEngine.validateMove(board, pos(5, 4), pos(4, 5), List.of(), List.of());
        assertTrue(result.isSuccess());
    }

    @Test
    void testSourcePieceAlreadyBusyWithJumpFails() {
        MatrixBoard board = buildBoard("3,3=wR");
        Piece rook = board.getPieceAt(pos(3, 3));
        List<PendingJump> pendingJumps = List.of(new PendingJump(3, 3, rook, 0L));
        GameResult<Void> result = ruleEngine.validateMove(board, pos(3, 3), pos(3, 5), List.of(), pendingJumps);
        assertFalse(result.isSuccess());
        assertEquals("Piece is already busy with a jump", result.message());
    }

    @Test
    void testDestinationBlockedByFriendlyJumpFails() {
        MatrixBoard board = buildBoard("3,3=wR", "0,0=wP");
        Piece jumper = board.getPieceAt(pos(0, 0));
        List<PendingJump> pendingJumps = List.of(new PendingJump(3, 5, jumper, 0L));
        GameResult<Void> result = ruleEngine.validateMove(board, pos(3, 3), pos(3, 5), List.of(), pendingJumps);
        assertFalse(result.isSuccess());
        assertEquals("Destination is blocked by a friendly jump", result.message());
    }

    @Test
    void testSourcePieceAlreadyMovingFails() {
        MatrixBoard board = buildBoard("3,3=wR");
        Piece rook = board.getPieceAt(pos(3, 3));
        List<PendingMove> pendingMoves = List.of(new PendingMove(3, 3, 3, 4, rook, 1000L));
        GameResult<Void> result = ruleEngine.validateMove(board, pos(3, 3), pos(3, 5), pendingMoves, List.of());
        assertFalse(result.isSuccess());
        assertEquals("Piece is already moving", result.message());
    }

    @Test
    void testDestinationOccupiedByFriendlyPendingMoveFails() {
        MatrixBoard board = buildBoard("3,3=wR", "0,0=wP");
        Piece other = board.getPieceAt(pos(0, 0));
        List<PendingMove> pendingMoves = List.of(new PendingMove(0, 0, 3, 5, other, 1000L));
        GameResult<Void> result = ruleEngine.validateMove(board, pos(3, 3), pos(3, 5), pendingMoves, List.of());
        assertFalse(result.isSuccess());
        assertEquals("Destination is already occupied by a friendly pending move", result.message());
    }

    @Test
    void testEnemyCaptureByRookSucceeds() {
        MatrixBoard board = buildBoard("3,3=wR", "3,7=bP");
        GameResult<Void> result = ruleEngine.validateMove(board, pos(3, 3), pos(3, 7), List.of(), List.of());
        assertTrue(result.isSuccess());
    }
}
