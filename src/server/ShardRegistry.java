package server;

import redis.clients.jedis.JedisPooled;
import server.logging.ServerLog;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import config.GameConfig;

public class ShardRegistry {

    private static final String KEY_PREFIX = GameConfig.KEY_PREFIX_SHARD;
    private static final int TTL_SECONDS = GameConfig.TTL_SECONDS;

    private final JedisPooled jedis;

    public ShardRegistry(String redisUrl) {
        JedisPooled pool;
        try {
            pool = new JedisPooled(redisUrl);
        } catch (Exception e) {
            ServerLog.warn("ShardRegistry: could not initialize Redis client for " + redisUrl + ": " + e.getMessage());
            pool = null;
        }
        this.jedis = pool;
    }

    public void registerHeartbeat(String shardId, String host, int roomCount) {
        if (jedis == null) return;
        try {
            String key = KEY_PREFIX + shardId;
            jedis.hset(key, Map.of(
                    "host", host,
                    "roomCount", Integer.toString(roomCount),
                    "updatedAt", Long.toString(System.currentTimeMillis())));
            jedis.expire(key, TTL_SECONDS);
        } catch (Exception e) {
            ServerLog.warn("ShardRegistry: heartbeat failed for " + shardId + ": " + e.getMessage());
        }
    }

    public record ShardInfo(String shardId, String host) {
    }

    /** Deregisters a shard immediately (used on graceful shutdown) instead of waiting for TTL expiry. */
    public void remove(String shardId) {
        if (jedis == null) return;
        try {
            jedis.del(KEY_PREFIX + shardId);
        } catch (Exception e) {
            ServerLog.warn("ShardRegistry: remove failed for " + shardId + ": " + e.getMessage());
        }
    }

    public Optional<ShardInfo> pickLeastLoaded() {
        if (jedis == null) return Optional.empty();
        try {
            Set<String> keys = jedis.keys(KEY_PREFIX + "*");
            ShardInfo best = null;
            int bestLoad = Integer.MAX_VALUE;
            for (String key : keys) {
                Map<String, String> fields = jedis.hgetAll(key);
                if (fields == null || fields.isEmpty()) {
                    continue;
                }
                int roomCount = Integer.parseInt(fields.getOrDefault("roomCount", "0"));
                if (roomCount < bestLoad) {
                    bestLoad = roomCount;
                    best = new ShardInfo(key.substring(KEY_PREFIX.length()), fields.get("host"));
                }
            }
            return Optional.ofNullable(best);
        } catch (Exception e) {
            ServerLog.warn("ShardRegistry: pickLeastLoaded failed: " + e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<String> findHost(String shardId) {
        if (jedis == null) return Optional.empty();
        try {
            String host = jedis.hget(KEY_PREFIX + shardId, "host");
            return Optional.ofNullable(host);
        } catch (Exception e) {
            ServerLog.warn("ShardRegistry: findHost failed for " + shardId + ": " + e.getMessage());
            return Optional.empty();
        }
    }
}
