import client.GameClient;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Load test per Server_Design.md section 5: simulate many concurrent players end to end (login
 * -> seek -> matched -> active game -> disconnect) through the real distributed stack, rather
 * than relying on the theoretical traffic math in section 3. Written in Java (not k6/Locust) to
 * reuse the project's own client protocol classes and stay consistent with the rest of the
 * codebase. Each simulated pair of players exercises api-gateway/ws-gateway login, matchmaker
 * pairing, allocator shard selection, and one active room on whichever game-server shard it
 * lands on -- the same path real players take, just driven concurrently at scale.
 */
public class LoadTest {

    public static void main(String[] args) throws Exception {
        String wsUrl = args.length > 0 ? args[0] : "ws://ws-gateway:8887";
        int pairs = args.length > 1 ? Integer.parseInt(args[1]) : 25;
        int holdSeconds = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        int players = pairs * 2;

        AtomicInteger loggedIn = new AtomicInteger();
        AtomicInteger matched = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(players);
        CountDownLatch done = new CountDownLatch(players);

        long start = System.currentTimeMillis();
        long suffix = start % 100000;

        System.out.println("Starting load test: " + players + " players (" + pairs + " games), hold=" + holdSeconds + "s");

        for (int i = 0; i < pairs; i++) {
            String p1 = "load_a_" + suffix + "_" + i;
            String p2 = "load_b_" + suffix + "_" + i;
            pool.submit(() -> runPlayer(p1, wsUrl, holdSeconds, loggedIn, matched, errors, done));
            pool.submit(() -> runPlayer(p2, wsUrl, holdSeconds, loggedIn, matched, errors, done));
        }

        boolean finished = done.await(holdSeconds + 40L, TimeUnit.SECONDS);
        pool.shutdownNow();
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("=== LOAD TEST SUMMARY ===");
        System.out.println("Target players: " + players);
        System.out.println("Logged in: " + loggedIn.get());
        System.out.println("Matched (assigned identity): " + matched.get());
        System.out.println("Errors: " + errors.get());
        System.out.println("All threads finished before timeout: " + finished);
        System.out.println("Elapsed: " + elapsed + "ms");

        if (!finished || matched.get() < players || errors.get() > 0) {
            System.out.println("FAIL: not all players matched cleanly");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    private static void runPlayer(String username, String wsUrl, int holdSeconds,
                                   AtomicInteger loggedIn, AtomicInteger matched, AtomicInteger errors,
                                   CountDownLatch done) {
        GameClient client = null;
        try {
            CompletableFuture<Void> loginFuture = new CompletableFuture<>();
            CompletableFuture<Void> matchFuture = new CompletableFuture<>();

            client = new GameClient(new URI(wsUrl), username, "load-pass",
                    state -> {
                    },
                    err -> errors.incrementAndGet(),
                    event -> {
                    },
                    identity -> matchFuture.complete(null),
                    rejected -> errors.incrementAndGet(),
                    oppDisc -> {
                    },
                    closed -> {
                    },
                    loginResult -> {
                        if (loginResult.success) loginFuture.complete(null);
                        else loginFuture.completeExceptionally(new RuntimeException("login failed"));
                    },
                    seekTimeout -> errors.incrementAndGet(),
                    countdown -> {
                    },
                    roomJoined -> {
                    },
                    roomError -> errors.incrementAndGet(),
                    spectate -> {
                    },
                    history -> {
                    });

            client.connect();
            long deadline = System.currentTimeMillis() + 10000;
            while (System.currentTimeMillis() < deadline
                    && client.getReadyState() != org.java_websocket.enums.ReadyState.OPEN) {
                Thread.sleep(50);
            }
            if (client.getReadyState() != org.java_websocket.enums.ReadyState.OPEN) {
                throw new IllegalStateException("could not open connection");
            }

            loginFuture.get(10, TimeUnit.SECONDS);
            loggedIn.incrementAndGet();

            client.sendSeek();
            matchFuture.get(30, TimeUnit.SECONDS);
            matched.incrementAndGet();

            Thread.sleep(holdSeconds * 1000L);
        } catch (Exception e) {
            errors.incrementAndGet();
        } finally {
            if (client != null) {
                client.close();
            }
            done.countDown();
        }
    }
}
