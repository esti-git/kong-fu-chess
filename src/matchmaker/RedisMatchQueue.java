package matchmaker;

import config.GameConfig;
import redis.clients.jedis.JedisPooled;

import java.util.List;
import java.util.Optional;

public class RedisMatchQueue {

    private static final String QUEUE_KEY = GameConfig.QUEUE_KEY;

    private final JedisPooled jedis;

    public RedisMatchQueue(String redisUrl) {
        this.jedis = new JedisPooled(redisUrl);
    }

    public Optional<String> enqueueAndFindMatch(String username, int rating) {
        double min = rating - GameConfig.RATING_RANGE;
        double max = rating + GameConfig.RATING_RANGE;
        List<String> candidates = jedis.zrangeByScore(QUEUE_KEY, min, max);
        for (String candidate : candidates) {
            if (candidate.equals(username)) {
                continue;
            }
            if (jedis.zrem(QUEUE_KEY, candidate) == 1) {
                return Optional.of(candidate);
            }
        }
        jedis.zadd(QUEUE_KEY, rating, username);
        return Optional.empty();
    }

    public boolean cancel(String username) {
        return jedis.zrem(QUEUE_KEY, username) == 1;
    }
}
