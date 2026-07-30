import client.GameClient;
import protocol.AssignedIdentity;
import protocol.RoomJoined;
import protocol.SpectateInfo;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manual harness for the connection-draining fix: connects one player, creates a room, prints
 * the room id, then holds the connection open for a fixed window (simulating an in-progress
 * game) before disconnecting. Meant to be run while an external script deletes the hosting
 * shard's pod mid-window and observes /readyz + ShardRegistry + pod termination timing.
 */
public class DrainTest {

    public static void main(String[] args) throws Exception {
        String wsUrl = args.length > 0 ? args[0] : "ws://ws-gateway:8887";
        int holdSeconds = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        String username = "drain_creator_" + (System.currentTimeMillis() % 100000);
        String password = "e2e-pass";

        CompletableFuture<Void> loggedIn = new CompletableFuture<>();
        CompletableFuture<RoomJoined> roomCreated = new CompletableFuture<>();

        GameClient client = new GameClient(new URI(wsUrl), username, password,
                state -> {
                },
                err -> System.out.println("ERROR: " + err),
                event -> {
                },
                (AssignedIdentity identity) -> System.out.println("assigned identity: " + identity.color),
                rejected -> System.out.println("rejected: " + rejected),
                oppDisc -> {
                },
                closed -> System.out.println("connection closed: " + closed),
                loginResult -> {
                    System.out.println("loginResult success=" + loginResult.success);
                    if (loginResult.success) loggedIn.complete(null);
                },
                seekTimeout -> {
                },
                countdown -> {
                },
                (RoomJoined joined) -> {
                    System.out.println("ROOM_CREATED:" + joined.roomId);
                    roomCreated.complete(joined);
                },
                roomError -> System.out.println("roomError: " + roomError),
                (SpectateInfo info) -> {
                },
                history -> {
                });

        client.connect();
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && client.getReadyState() != org.java_websocket.enums.ReadyState.OPEN) {
            Thread.sleep(100);
        }
        loggedIn.get(10, TimeUnit.SECONDS);
        client.sendCreateRoom("");
        roomCreated.get(10, TimeUnit.SECONDS);

        System.out.println("HOLDING connection for " + holdSeconds + "s to simulate an active game...");
        Thread.sleep(holdSeconds * 1000L);

        System.out.println("Closing connection now.");
        client.close();
        Thread.sleep(1000);
    }
}
