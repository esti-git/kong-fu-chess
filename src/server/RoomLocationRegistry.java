package server;

import redis.clients.jedis.JedisPooled;
import server.logging.ServerLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Redis-backed index of currently active rooms (roomId -> which shard hosts it and who's
 * seated), used by the API Gateway's {@code /api/rooms} listing. Written by {@link Room} as
 * players are seated and removed by {@link RoomRegistry} once a room is actually torn down.
 * Best-effort like the other Redis-backed registries in this package — failures here must never
 * disrupt gameplay.
 */
public class RoomLocationRegistry {

    private static final String KEY_PREFIX = "kfc:room:";

    private final JedisPooled jedis;

    public RoomLocationRegistry(String redisUrl) {
        JedisPooled pool;
        try {
            pool = new JedisPooled(redisUrl);
        } catch (Exception e) {
            ServerLog.warn("RoomLocationRegistry: could not initialize Redis client for " + redisUrl + ": " + e.getMessage());
            pool = null;
        }
        this.jedis = pool;
    }

    public void update(String roomId, String shardId, String whiteUsername, String blackUsername) {
        if (jedis == null) return;
        try {
            jedis.hset(KEY_PREFIX + roomId, Map.of(
                    "shardId", shardId,
                    "whiteUsername", whiteUsername == null ? "" : whiteUsername,
                    "blackUsername", blackUsername == null ? "" : blackUsername,
                    "updatedAt", Long.toString(System.currentTimeMillis())));
        } catch (Exception e) {
            ServerLog.warn("RoomLocationRegistry: failed to update " + roomId + ": " + e.getMessage());
        }
    }

    public Optional<RoomSummary> find(String roomId) {
        if (jedis == null) return Optional.empty();
        try {
            Map<String, String> fields = jedis.hgetAll(KEY_PREFIX + roomId);
            if (fields == null || fields.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new RoomSummary(roomId, fields.get("shardId"),
                    fields.get("whiteUsername"), fields.get("blackUsername")));
        } catch (Exception e) {
            ServerLog.warn("RoomLocationRegistry: find failed for " + roomId + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    public void remove(String roomId) {
        if (jedis == null) return;
        try {
            jedis.del(KEY_PREFIX + roomId);
        } catch (Exception e) {
            ServerLog.warn("RoomLocationRegistry: failed to remove " + roomId + ": " + e.getMessage());
        }
    }

    public List<RoomSummary> findAll() {
        List<RoomSummary> result = new ArrayList<>();
        if (jedis == null) return result;
        try {
            Set<String> keys = jedis.keys(KEY_PREFIX + "*");
            for (String key : keys) {
                Map<String, String> fields = jedis.hgetAll(key);
                if (fields == null || fields.isEmpty()) {
                    continue;
                }
                result.add(new RoomSummary(key.substring(KEY_PREFIX.length()), fields.get("shardId"),
                        fields.get("whiteUsername"), fields.get("blackUsername")));
            }
        } catch (Exception e) {
            ServerLog.warn("RoomLocationRegistry: findAll failed: " + e.getMessage());
        }
        return result;
    }
}
