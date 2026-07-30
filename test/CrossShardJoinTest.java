import client.GameClient;
import protocol.AssignedIdentity;
import protocol.RoomJoined;
import protocol.SpectateInfo;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Regression test for the "join an existing room by code lands on the wrong shard" bug:
 * player A creates a room (seated on whichever shard the Allocator picks), player B logs in
 * fresh (likely landing on a *different* shard, since the Allocator prefers the now-less-loaded
 * one) and joins that room by code. Before the WsGatewayServer migration fix this failed with
 * "Room not found"; after the fix it should succeed.
 */
public class CrossShardJoinTest {

    public static void main(String[] args) throws Exception {
        String wsUrl = args.length > 0 ? args[0] : "ws://ws-gateway:8887";
        long suffix = System.currentTimeMillis() % 100000;
        String creator = "xshard_creator_" + suffix;
        String joiner = "xshard_joiner_" + suffix;
        String password = "e2e-pass";

        CompletableFuture<Void> creatorLoggedIn = new CompletableFuture<>();
        CompletableFuture<RoomJoined> creatorRoom = new CompletableFuture<>();
        AtomicBoolean creatorFailed = new AtomicBoolean(false);
        GameClient creatorClient = buildClient(wsUrl, creator, password, "CREATOR",
                creatorRoom, null, creatorFailed, creatorLoggedIn);

        CompletableFuture<Void> joinerLoggedIn = new CompletableFuture<>();
        CompletableFuture<RoomJoined> joinerRoom = new CompletableFuture<>();
        AtomicBoolean joinerFailed = new AtomicBoolean(false);
        GameClient joinerClient = buildClient(wsUrl, joiner, password, "JOINER",
                null, joinerRoom, joinerFailed, joinerLoggedIn);

        creatorClient.connect();
        waitForOpen(creatorClient, "creator");
        creatorLoggedIn.get(10, TimeUnit.SECONDS);
        creatorClient.sendCreateRoom("");

        RoomJoined created = creatorRoom.get(10, TimeUnit.SECONDS);
        System.out.println("Creator seated in room " + created.roomId);

        // Wait past one shard heartbeat cycle (GameConfig.SHARD_HEARTBEAT_SECONDS = 5s) so the
        // Allocator sees this shard's incremented roomCount and picks the *other* (less loaded)
        // shard for the joiner's fresh login -- otherwise both could land on the same shard by
        // chance and the migration path would never actually be exercised.
        Thread.sleep(7000);

        joinerClient.connect();
        waitForOpen(joinerClient, "joiner");
        joinerLoggedIn.get(10, TimeUnit.SECONDS);
        joinerClient.sendJoinRoom(created.roomId);

        boolean joined;
        try {
            RoomJoined joinedRoom = joinerRoom.get(10, TimeUnit.SECONDS);
            joined = joinedRoom.roomId.equals(created.roomId);
            System.out.println("Joiner result: joined room " + joinedRoom.roomId);
        } catch (Exception e) {
            joined = false;
            System.out.println("Joiner did not get roomJoined in time: " + e.getMessage());
        }

        creatorClient.close();
        joinerClient.close();

        if (!joined || creatorFailed.get() || joinerFailed.get()) {
            System.out.println("FAIL: cross-shard room join did not succeed");
            System.exit(1);
        }
        System.out.println("PASS: joiner successfully joined creator's room across shards");
    }

    private static void waitForOpen(GameClient client, String label) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline
                && client.getReadyState() != org.java_websocket.enums.ReadyState.OPEN) {
            Thread.sleep(100);
        }
        if (client.getReadyState() != org.java_websocket.enums.ReadyState.OPEN) {
            throw new IllegalStateException(label + " could not open WebSocket connection");
        }
    }

    private static GameClient buildClient(String wsUrl, String username, String password, String label,
                                          CompletableFuture<RoomJoined> onCreatorRoom,
                                          CompletableFuture<RoomJoined> onJoinerRoom,
                                          AtomicBoolean failed,
                                          CompletableFuture<Void> loggedIn) throws Exception {
        return new GameClient(new URI(wsUrl), username, password,
                state -> {
                },
                err -> {
                    System.out.println("[" + label + "] ERROR: " + err);
                    failed.set(true);
                },
                event -> {
                },
                (AssignedIdentity identity) -> System.out.println("[" + label + "] assigned identity: " + identity.color),
                rejected -> {
                    System.out.println("[" + label + "] rejected: " + rejected);
                    failed.set(true);
                },
                oppDisc -> {
                },
                closed -> System.out.println("[" + label + "] connection closed: " + closed),
                loginResult -> {
                    System.out.println("[" + label + "] loginResult success=" + loginResult.success);
                    if (loginResult.success) {
                        loggedIn.complete(null);
                    } else {
                        loggedIn.completeExceptionally(new RuntimeException("login failed"));
                    }
                },
                seekTimeout -> {
                },
                countdown -> {
                },
                (RoomJoined joined) -> {
                    System.out.println("[" + label + "] roomJoined: " + joined.roomId + " role=" + joined.role);
                    if (onCreatorRoom != null) onCreatorRoom.complete(joined);
                    if (onJoinerRoom != null) onJoinerRoom.complete(joined);
                },
                roomError -> {
                    System.out.println("[" + label + "] roomError: " + roomError);
                    failed.set(true);
                    if (onJoinerRoom != null) onJoinerRoom.completeExceptionally(new RuntimeException(roomError));
                },
                (SpectateInfo info) -> {
                },
                history -> {
                });
    }
}
