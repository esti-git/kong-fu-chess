# Kung Fu Chess

Real-time chess variant (pieces move asynchronously with cooldowns instead of turns) — Java + Swing.

## Requirements

- Java 17
- Maven

## Build

```
mvn compile
```

Run the tests:

```
mvn test
```

## Running — network mode (client/server)

All game logic runs on the server; each client is a thin display that connects over WebSocket.

1. Start the server first (`server.ServerMain`, listens on port `8887`):

   ```
   mvn compile exec:java -Dexec.mainClass=server.ServerMain
   ```

2. Start one client per player (`client.ClientMain`), each in its own terminal/process:

   ```
   mvn compile exec:java -Dexec.mainClass=client.ClientMain
   ```

   Each client prompts for a username and password on the console (a new username registers automatically). After logging in, use the **Play** button to get matched by rating, or **Room** to create/join a private room with another player.

   By default the client connects to `ws://localhost:8887`. To connect elsewhere, pass the URL as an argument:

   ```
   mvn compile exec:java -Dexec.mainClass=client.ClientMain -Dexec.args="ws://<host>:8887"
   ```

   > Note: `exec:java` requires the `exec-maven-plugin`. If it isn't configured in `pom.xml`, run `ServerMain` / `ClientMain` directly from your IDE instead (both are plain classes with a `main` method).

## Running — local mode (single process)

For a quick offline game on one machine (no server needed), run `LocalMain` directly from your IDE, or:

```
mvn compile
java -cp target/classes LocalMain
```

## Distributed architecture

Beyond the single-process server above, this project also implements the cloud-scale design in
[Server_Design.md](Server_Design.md): API Gateway, WebSocket Gateway, Matchmaker, Game Allocator,
and Game Server Shards as separate services, coordinated through Redis and NATS, with Postgres for
persistent data. See `docker-compose.yml` and `k8s/` below for the two ways to run it.

### Docker Compose (single machine)

```
docker compose up
```

Brings up Postgres, Redis, NATS, and all five services on one machine. Scale shards with:

```
docker compose up --scale game-server=N
```

### Kubernetes

The `k8s/` directory has the full manifest set to run the same architecture on a real cluster,
with production concerns the Compose setup doesn't cover:

```
kubectl apply -f k8s/
```

**Status: complete and verified**, not just deployed —

- Namespace, backing services (Postgres/Redis/NATS with JetStream), and all 5 app services
- `game-server` runs as a `StatefulSet` with per-shard addressing, so ws-gateway can route a
  reconnecting or cross-shard-joining player to the exact shard hosting their room
- **Autoscaling** by active room count (not CPU) via Prometheus + prometheus-adapter — confirmed
  scaling up under real concurrent load and back down after
- **Graceful connection draining** — a shard being scaled down stops taking new rooms but lets
  in-flight games finish before it exits
- Game-result writes (rating + history) are decoupled onto a NATS JetStream queue instead of
  blocking room teardown on Postgres
- A load test (`test/LoadTest.java`, run via `k8s/16-load-test.yaml`) simulates concurrent players
  through the real login → matchmaking → game flow

**Not implemented:** centralized log aggregation (e.g. Loki/ELK) — each service logs structured
output, but it isn't yet collected in one place across pods.

The other test manifests (`k8s/09`, `k8s/11`, `k8s/13`) are one-off verification Jobs, not part of
the running stack — apply them individually to re-check a specific behavior (basic flow, cross-shard
routing, queued writes) after making changes.
