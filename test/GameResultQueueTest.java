import client.ApiGatewayClient;
import org.json.JSONObject;
import server.GameResultQueue;

/**
 * Verifies the NATS JetStream-backed decoupled-write path (GameResultQueue -> GameResultWorker)
 * end to end: registers two real players via the api-gateway (so they exist in Postgres),
 * publishes a synthetic "game ended" result straight to the queue -- bypassing the actual
 * GameEngine/Room, since what's under test is the queue plumbing, not chess rules -- and prints
 * the players' ratings before publishing. An external script is expected to poll Postgres
 * afterward and confirm the rating actually changed and a games row appeared, proving a
 * GameResultWorker running in one of the game-server pods consumed and wrote it asynchronously.
 */
public class GameResultQueueTest {

    public static void main(String[] args) throws Exception {
        String apiUrl = args.length > 0 ? args[0] : "http://api-gateway:8080";
        String natsUrl = args.length > 1 ? args[1] : "nats://nats:4222";
        long suffix = System.currentTimeMillis() % 100000;
        String white = "queuetest_white_" + suffix;
        String black = "queuetest_black_" + suffix;
        String password = "e2e-pass";

        ApiGatewayClient api = new ApiGatewayClient(apiUrl);
        JSONObject whiteLogin = api.login(white, password);
        JSONObject blackLogin = api.login(black, password);
        System.out.println(white + " -> " + whiteLogin);
        System.out.println(black + " -> " + blackLogin);
        if (whiteLogin == null || blackLogin == null || !whiteLogin.optBoolean("success", false)
                || !blackLogin.optBoolean("success", false)) {
            System.out.println("FAIL: could not register test players via api-gateway");
            System.exit(1);
        }

        int whiteBefore = whiteLogin.getInt("rating");
        int blackBefore = blackLogin.getInt("rating");
        int whiteAfter = whiteBefore + 37;
        int blackAfter = blackBefore - 21;
        String roomId = "QTEST" + suffix;

        JSONObject result = new JSONObject()
                .put("roomId", roomId)
                .put("whiteUsername", white)
                .put("blackUsername", black)
                .put("winnerColor", "WHITE")
                .put("whiteRatingBefore", whiteBefore)
                .put("whiteRatingAfter", whiteAfter)
                .put("blackRatingBefore", blackBefore)
                .put("blackRatingAfter", blackAfter)
                .put("startedAt", System.currentTimeMillis() - 45000)
                .put("endedAt", System.currentTimeMillis())
                .put("eventsJson", "[]");

        GameResultQueue queue = new GameResultQueue(natsUrl);
        queue.publish(result);

        System.out.println("PUBLISHED roomId=" + roomId + " white=" + white + " (expect rating -> " + whiteAfter + ")"
                + " black=" + black + " (expect rating -> " + blackAfter + ")");
    }
}
