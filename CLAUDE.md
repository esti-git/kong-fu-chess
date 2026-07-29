# KungFu Chess

Real-time chess variant (pieces move async with cooldowns, no turns) — Java + Swing, school project based on CTD slides.

## Language
Everything is Java. Never suggest Python or any other language.

## Structure (src/)
- `engine/` — GameEngine, GameFactory, core game loop
- `rules/` — RuleEngine, PieceFactory, PieceValues, PawnPromotion, `pieces/` per-piece move rules
- `model/` — Board, Piece, GameState, Position, pending move/jump/rest records, CaptureRecord
- `events/` — EventBus + Event types (move made, piece captured, game started/ended)
- `view/` — Swing rendering: BoardRenderer, snapshots, animation, score/move-history tracking
- `protocol/` — shared wire format: MoveCommand, JumpCommand, NetworkState, StateCodec, PieceCodes, LoginResult, RoomJoined, SpectateInfo, AssignedIdentity
- `server/` — GameServer, ServerMain, ServerController, MatchService (authoritative game logic), plus Room/RoomRegistry, PlayerRegistry/RedisPlayerRegistry, SessionRegistry, ShardRegistry, RatingService/EloCalculator, PasswordHasher, AllocatorClient, MatchmakerClient
- `client/` — GameClient, ClientMain, ClientView, HomeView, ApiGatewayClient, BoardInputHandler (thin display)
- `apigateway/` — standalone HTTP API Gateway (login/auth, backed by Postgres + Redis player registry)
- `wsgateway/` — standalone WS Gateway; proxies client WebSocket connections to a game-server shard
- `matchmaker/` — standalone Matchmaker service (Redis ELO queue: seek/cancel)
- `allocator/` — standalone Game Allocator; picks which shard hosts a new room (Redis shard registry, least-loaded)
- `io/` — BoardParser
- `config/` — GameConfig
- `common/`, `enums/`, `board/`, `local/`, `realTime/`, `logging/`, `audio/` — shared model enums, local (non-networked) game loop, real-time move arbiter, logging, sound

## Architecture
This started as a single-process local game (`LocalMain.java` + `local/`) and has grown into a distributed, multi-service system, following the roadmap in `Server_Design.md`:

- **game-server** (`ServerMain`/`GameServer`) — authoritative game logic for one or more rooms (a "shard"). Multiple shards can run at once (`docker compose up --scale game-server=N`); each shard self-registers into Redis via `ShardRegistry` (heartbeats its room count every `GameConfig.SHARD_HEARTBEAT_SECONDS`, 15s TTL) and its `SHARD_ID` defaults to the container hostname so scaled instances get distinct identities automatically.
- **ws-gateway** — standalone WebSocket proxy clients actually connect to; today it forwards every connection to one static `GAME_SERVER_URL` (dynamic per-room routing to the Allocator's chosen shard is the next planned phase, not yet built).
- **api-gateway** — standalone HTTP service for login/auth, backed by Postgres + the Redis-backed `PlayerRegistry`.
- **matchmaker** — standalone service running an ELO-based Redis queue (`RedisMatchQueue`) for pairing players.
- **allocator** — standalone service that picks the least-loaded shard for a new room, reading `ShardRegistry`. `ServerController.handleCreateRoom` and `MatchService.seatPair` call `AllocatorClient.allocateOrDefault()` and use its answer (falling back to the local `GameConfig.SHARD_ID` if the Allocator is unreachable) as the shardId recorded in the player registry.
- Clients (`ClientMain`/`GameClient`/`ClientView`) are thin displays: they talk to the api-gateway for login and to the ws-gateway for the live game connection, and render server state. `protocol/` is the shared wire format. Each client has a local `EventBus` fed by events the server sends, driving score/log/sound.
- All game logic runs **only** on the server/shard — never reimplemented client-side.

Backing stores: Postgres (accounts/ratings) and Redis (player registry, shard registry, matchmaker queue). `docker-compose.yml` wires all of this together; each service has its own `Dockerfile.*`.

## Running
- **Local (single process):** run `LocalMain.java`.
- **Full distributed stack:** `docker compose up` (postgres, redis, matchmaker, allocator, game-server, ws-gateway, api-gateway). Scale shards with `docker compose up --scale game-server=N`.
- **Manual/dev:** start `ServerMain` first, then run `ClientMain` once per player.

## Working conventions
- Minimal changes to existing files; prefer adding new files over rewriting.
- Reuse existing engine/rules code — never reimplement game rules.
- No tests or READMEs unless explicitly asked.
- Always show a short plan before coding.
