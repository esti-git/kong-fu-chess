package config;

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

}
