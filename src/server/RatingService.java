package server;

import enums.PieceColor;

/**
 * Pure Elo calculation, applied immediately to the in-memory {@link PlayerSession}s so players
 * see their new rating the instant the game ends. Persisting that new rating is a separate,
 * decoupled concern -- see {@link GameResultQueue} / {@link GameResultWorker} -- so this class
 * does no I/O itself.
 */
public class RatingService {

    public void applyGameEnd(PieceColor winnerColor, PlayerSession whiteSession, PlayerSession blackSession) {
        PlayerSession winner = winnerColor == PieceColor.WHITE ? whiteSession : blackSession;
        PlayerSession loser = winnerColor == PieceColor.WHITE ? blackSession : whiteSession;

        int[] updated = EloCalculator.computeNewRatings(winner.getRating(), loser.getRating());
        winner.setRating(updated[0]);
        loser.setRating(updated[1]);
    }
}
