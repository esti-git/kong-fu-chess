package server;

public record PlayerLocation(String playerId, String status, String shardId, String roomId, long updatedAt) {
}
