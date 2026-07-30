package server;

public record GameSummary(String roomId, String whiteUsername, String blackUsername, String winnerColor,
                           int whiteRatingBefore, int whiteRatingAfter, int blackRatingBefore, int blackRatingAfter,
                           long startedAt, long endedAt) {
}
