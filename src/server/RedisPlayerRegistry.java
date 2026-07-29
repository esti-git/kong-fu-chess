package server;

import redis.clients.jedis.JedisPooled;
import server.logging.ServerLog;

import java.util.Map;
import java.util.Optional;

import config.GameConfig;

public class RedisPlayerRegistry implements PlayerRegistry {

    private static final String KEY_PREFIX = GameConfig.KEY_PREFIX;

    private final JedisPooled jedis;

    public RedisPlayerRegistry(String redisUrl) {
        JedisPooled pool;
        try {
            pool = new JedisPooled(redisUrl);
        } catch (Exception e) {
            ServerLog.warn("PlayerRegistry: could not initialize Redis client for " + redisUrl + ": " + e.getMessage());
            pool = null;
        }
        this.jedis = pool;
    }

    @Override
    public void markOnline(String playerId) {
        write(playerId, "ONLINE", "", "");
    }

    @Override
    public void markOnShard(String playerId, String shardId) {
        write(playerId, "ONLINE", shardId, "");
    }

    @Override
    public void markInRoom(String playerId, String shardId, String roomId) {
        write(playerId, "IN_ROOM", shardId, roomId);
    }

    @Override
    public void markOffline(String playerId) {
        if (jedis == null) return;
        try {
            jedis.del(key(playerId));
        } catch (Exception e) {
            ServerLog.warn("PlayerRegistry: failed to mark " + playerId + " offline: " + e.getMessage());
        }
    }

    @Override
    public Optional<PlayerLocation> find(String playerId) {
        if (jedis == null) return Optional.empty();
        try {
            Map<String, String> fields = jedis.hgetAll(key(playerId));
            if (fields == null || fields.isEmpty()) {
                return Optional.empty();
            }
            long updatedAt = fields.containsKey("updatedAt") ? Long.parseLong(fields.get("updatedAt")) : 0L;
            return Optional.of(new PlayerLocation(playerId, fields.getOrDefault("status", ""),
                    fields.getOrDefault("shardId", ""), fields.getOrDefault("roomId", ""), updatedAt));
        } catch (Exception e) {
            ServerLog.warn("PlayerRegistry: failed to look up " + playerId + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private void write(String playerId, String status, String shardId, String roomId) {
        if (jedis == null) return;
        try {
            jedis.hset(key(playerId), Map.of(
                    "status", status,
                    "shardId", shardId,
                    "roomId", roomId,
                    "updatedAt", Long.toString(System.currentTimeMillis())));
        } catch (Exception e) {
            ServerLog.warn("PlayerRegistry: failed to update " + playerId + ": " + e.getMessage());
        }
    }

    private static String key(String playerId) {
        return KEY_PREFIX + playerId;
    }
}
