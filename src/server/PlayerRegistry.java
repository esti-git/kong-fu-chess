package server;

import java.util.Optional;

public interface PlayerRegistry {

    void markOnline(String playerId);

    void markOnShard(String playerId, String shardId);

    void markInRoom(String playerId, String shardId, String roomId);

    void markOffline(String playerId);

    Optional<PlayerLocation> find(String playerId);
}
