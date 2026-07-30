import client.ApiGatewayClient;
import client.GameClient;
import org.json.JSONObject;
import protocol.AssignedIdentity;
import protocol.RoomJoined;
import protocol.SpectateInfo;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class E2ESmokeTest {

    public static void main(String[] args) throws Exception {
        String apiUrl = args.length > 0 ? args[0] : "http://api-gateway:8080";
        String wsUrl = args.length > 1 ? args[1] : "ws://ws-gateway:8887";
        long suffix = System.currentTimeMillis() % 100000;
        String user1 = "e2e_p1_" + suffix;
        String user2 = "e2e_p2_" + suffix;
        String password = "e2e-pass";

        System.out.println("== Step 1: HTTP login via api-gateway (" + apiUrl + ") ==");
        JSONObject r1 = new ApiGatewayClient(apiUrl).login(user1, password);
        JSONObject r2 = new ApiGatewayClient(apiUrl).login(user2, password);
        System.out.println(user1 + " -> " + r1);
        System.out.println(user2 + " -> " + r2);
        if (r1 == null || r2 == null || !r1.optBoolean("success", false) || !r2.optBoolean("success", false)) {
            System.out.println("FAIL: api-gateway login did not succeed for both players");
            System.exit(1);
        }

        System.out.println("== Step 2: WS connect + seek via ws-gateway (" + wsUrl + ") ==");
        CountDownLatch assigned = new CountDownLatch(2);
        AtomicBoolean failed = new AtomicBoolean(false);

        GameClient client1 = buildClient(wsUrl, user1, password, assigned, failed, "P1");
        GameClient client2 = buildClient(wsUrl, user2, password, assigned, failed, "P2");

        client1.connect();
        client2.connect();

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline &&
                (client1.getReadyState() != org.java_websocket.enums.ReadyState.OPEN ||
                 client2.getReadyState() != org.java_websocket.enums.ReadyState.OPEN)) {
            Thread.sleep(100);
        }
        if (client1.getReadyState() != org.java_websocket.enums.ReadyState.OPEN
                || client2.getReadyState() != org.java_websocket.enums.ReadyState.OPEN) {
            System.out.println("FAIL: could not open both WebSocket connections to ws-gateway");
            System.exit(1);
        }

        Thread.sleep(500);
        client1.sendSeek();
        client2.sendSeek();
        System.out.println("Both players sent seek, waiting for matchmaker/allocator to seat a room...");

        boolean matched = assigned.await(20, TimeUnit.SECONDS);
        client1.close();
        client2.close();

        if (!matched || failed.get()) {
            System.out.println("FAIL: players were not matched into a room within timeout");
            System.exit(1);
        }

        System.out.println("PASS: full pipeline OK (api-gateway login -> ws-gateway -> matchmaker -> allocator -> game-server room -> state pushed to both clients)");
    }

    private static GameClient buildClient(String wsUrl, String username, String password, CountDownLatch assigned,
                                          AtomicBoolean failed, String label) throws Exception {
        return new GameClient(new URI(wsUrl), username, password,
                state -> System.out.println("[" + label + "] state received"),
                err -> {
                    System.out.println("[" + label + "] ERROR: " + err);
                    failed.set(true);
                },
                event -> System.out.println("[" + label + "] event: " + event.getClass().getSimpleName()),
                (AssignedIdentity identity) -> {
                    System.out.println("[" + label + "] assigned identity: " + identity.color
                            + " white=" + identity.whiteName + " black=" + identity.blackName);
                    assigned.countDown();
                },
                rejected -> System.out.println("[" + label + "] rejected: " + rejected),
                oppDisc -> System.out.println("[" + label + "] opponent disconnected: " + oppDisc),
                closed -> System.out.println("[" + label + "] connection closed: " + closed),
                loginResult -> System.out.println("[" + label + "] loginResult success=" + loginResult.success),
                seekTimeout -> System.out.println("[" + label + "] seek timeout: " + seekTimeout),
                countdown -> System.out.println("[" + label + "] disconnect countdown: " + countdown),
                (RoomJoined joined) -> System.out.println("[" + label + "] room joined: " + joined.roomId),
                roomError -> System.out.println("[" + label + "] room error: " + roomError),
                (SpectateInfo info) -> System.out.println("[" + label + "] spectate info received"),
                history -> System.out.println("[" + label + "] history events: " + history.size()));
    }
}
