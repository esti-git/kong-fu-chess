package config;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class GameConfig {
    public static final int CELL_SIZE = 100;
    public static final long MS_PER_CELL = 1000L;
    public static final long JUMP_DURATION_MS = 1000L;
    public static final long LONG_REST_DURATION_MS = 3000L;
    public static final long SHORT_REST_DURATION_MS = 1000L;
    public static final int BOARD_LABEL_MARGIN = 30;

    public static final int SEEK_TIMEOUT_SECONDS = 60;
    public static final int DISCONNECT_GRACE_SECONDS = 60;
    public static final int RATING_RANGE = 100;

    public static final int TICK_MS = 16;
    public static final int MAX_ROOM_NAME_LENGTH = 20;

    public static final String DB_URL = System.getenv().getOrDefault("DB_URL", "jdbc:sqlite:players.db");
    public static final int STARTING_RATING = 1200;

    public static final String REDIS_URL = System.getenv().getOrDefault("REDIS_URL", "redis://localhost:6379");
    public static final String SHARD_ID = System.getenv().getOrDefault("SHARD_ID", defaultShardId());

    private static String defaultShardId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "shard-1";
        }
    }

    public static final String KEY_PREFIX = "kfc:player:";

    public static final String DEFAULT_WS_PORT = "ws://localhost:8887";
    public static final String DEFAULT_HTTP_PORT = "http://localhost:8080";

    public static final int GAME_SERVER_PORT = Integer
            .parseInt(System.getenv().getOrDefault("GAME_SERVER_PORT", "8887"));
    public static final int GATEWAY_PORT = Integer.parseInt(System.getenv().getOrDefault("GATEWAY_PORT", "8887"));
    public static final String GAME_SERVER_URL = System.getenv().getOrDefault("GAME_SERVER_URL", "ws://localhost:8887");

    public static final int MATCHMAKER_PORT = Integer.parseInt(System.getenv().getOrDefault("MATCHMAKER_PORT", "8081"));
    public static final String MATCHMAKER_URL = System.getenv().getOrDefault("MATCHMAKER_URL", "http://localhost:8081");
    public static final String QUEUE_KEY = "kfc:matchqueue";

    public static final int ALLOCATOR_PORT = Integer.parseInt(System.getenv().getOrDefault("ALLOCATOR_PORT", "8082"));
    public static final String ALLOCATOR_URL = System.getenv().getOrDefault("ALLOCATOR_URL", "http://localhost:8082");
    public static final int SHARD_HEARTBEAT_SECONDS = 5;
    public static final String GAME_SERVER_HOST = System.getenv().getOrDefault("GAME_SERVER_HOST",
            "ws://localhost:" + GAME_SERVER_PORT);

    public static final String KEY_PREFIX_SHARD = "kfc:shard:";
    public static final int TTL_SECONDS = 15;

    public static final int GAME_SERVER_HEALTH_PORT = Integer
            .parseInt(System.getenv().getOrDefault("GAME_SERVER_HEALTH_PORT", "9001"));
    public static final int GATEWAY_HEALTH_PORT = Integer
            .parseInt(System.getenv().getOrDefault("GATEWAY_HEALTH_PORT", "9002"));
}
