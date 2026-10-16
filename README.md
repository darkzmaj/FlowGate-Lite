## Maintenance release 1.0.7

Use a maintained **BungeeCord build 2104 or newer** for Minecraft Java 26.3 (protocol 777). Tested on Java 21. Plugin bytecode remains Java 17; follow your proxy's runtime requirement. Waterfall is discontinued and is only a legacy, untested target: do not use it to claim 26.3 support. FlowGate Lite remains a Bungee API plugin; Velocity requires migration to an equivalent router and cannot load this JAR.

Existing configuration and the backend `BungeeCord` / `Connect` wire format are preserved. All routes now honor Bungee restricted-server access. Backend servers are trusted infrastructure: firewall them from direct client access, and narrow `plugin-messages.allowed-source-servers` where practical. This plugin forwards connections, never privileged console commands.

Build with JDK 25: `bash gradlew clean build`. Output: `build/libs/FlowGate-Lite-1.0.7.jar`. See [CHANGELOG.md](CHANGELOG.md) for fixes and validation limits.

# FlowGate-Lite

**FlowGate-Lite** is a production-grade BungeeCord proxy plugin for intelligent hub routing, backend health monitoring, automatic failover, and controlled player distribution across Minecraft server networks.

> **FlowGate** is the ecosystem name. **FlowGate-Lite** is this specific plugin. Additional plugins may be released under the FlowGate ecosystem in the future.

---

## Overview

FlowGate-Lite replaces the default BungeeCord server selection logic with a health-aware routing layer. Instead of blindly sending players to whatever server the proxy configuration lists first, it actively monitors backend availability, quarantines failing servers, and selects the best destination based on configurable criteria.

Players who cannot be routed (all hubs offline or full) are held in a configurable limbo server and automatically moved out as soon as a hub becomes available again — no reconnect required.

---

## Features

- **Async health monitoring** – backends are pinged continuously on a dedicated daemon thread
- **Quarantine system** – servers with repeated failures are isolated and retried after a configurable delay
- **Least-players routing** – players are sent to the hub with the lowest current load
- **Weighted balancing** – assign higher weights to servers that should absorb more traffic
- **Sticky sessions** – returning players are sent back to their last successful hub while it remains healthy
- **Limbo / fallback system** – players land in a waiting server when all hubs are unavailable
- **Automatic limbo exit** – scheduler continuously routes limbo players to hubs as they recover
- **Kick-based fallback** – players kicked from any backend are caught and rerouted automatically
- **Plugin message security** – BungeeCord channel messages are validated and rate-limited per-player
- **Per-server metrics** – routing success/failure, ping failures, quarantine counts tracked in memory
- **Live status command** – `/flowgate status` shows current health state and metrics
- **Hot reload** – `/flowgate reload` applies config changes without a full restart
- **Slash-server commands** – custom `/command → server` mappings
- **Hub commands** – configurable `/hub`, `/lobby` style commands (max 3)
- **MOTD management** – selectable MOTD profiles

---

## Architecture

### Health Monitor

A single-threaded `ScheduledExecutorService` drives the ping cycle using `scheduleWithFixedDelay`. A per-server in-flight guard prevents a slow backend ping from being duplicated; stale callbacks from a reload are ignored.

Ping results update an immutable `ServerHealth` snapshot stored in a `ConcurrentHashMap`. All routing reads from this cache. No routing code performs live pings.

### Quarantine System

When a server accumulates `quarantine-threshold` consecutive ping failures, it is placed in quarantine for `quarantine-duration-ms`. During quarantine it is excluded from all routing. The health monitor automatically re-pings it after the quarantine expires.

### Routing

The `RouterService` selects destinations using:

1. **Sticky session check** (if enabled): if the player has a recent successful server and it is still healthy and has room, route there immediately.
2. **Weighted least-players selection**: each hub's score is `playerCount / weight`. Lower score = preferred. Hubs at or above the effective cap (`hub-max-players - reserved-slots`) are excluded.

The `PlayerSession` stores per-player state (connection state, retry count, last successful server) using lock-free atomics. State transitions use `compareAndSet` to prevent duplicate routing attempts.

### Limbo Loop

A BungeeCord `ScheduledTask` ticks every `check-interval-seconds`. For each player in the limbo set, it:

1. Verifies they are still connected and still on the fallback server.
2. Checks the retry cooldown.
3. Atomically transitions session state from `LIMBO` to `ROUTING` via CAS (prevents double-routing).
4. Calls `RouterService.findBestHub()`.
5. Connects the player and records the result in the async callback.

### Shutdown

On disable, the plugin cancels the limbo task, shuts down the health monitor executor (with a 3s drain), clears all player sessions, and unregisters listeners and commands. Startup failures trigger the same cleanup path — no partially initialized state survives.

---

## Installation

1. Drop `FlowGate-Lite-1.0.7.jar` into your proxy's `plugins/` directory.
2. Restart the proxy. Config files are generated automatically.
3. Edit `plugins/FlowGate-Lite/config.yml` with your server names.
4. Edit `plugins/FlowGate-Lite/messages.yml` and `motd.yml` as needed.
5. Run `/flowgate reload` or restart.

**Requirements:**
- Java 17 or newer
- BungeeCord build 2104 for Minecraft 26.3; Waterfall/FlameCord are untested legacy forks

---

## Configuration

### `config.yml`

```yaml
debug:
  enabled: false

titles:
  enabled: true

hubs:
  - hub-1
  - hub-2

hub-commands:
  - hub

routing:
  enabled: true
  retry-cooldown-ms: 5000
  quarantine-threshold: 3
  quarantine-duration-ms: 60000
  sticky-sessions: false
  sticky-session-ttl-ms: 300000

fallback:
  enabled: true
  fallback-server: limbo

players:
  hub-max-players: 100
  reserved-slots: 5

check-interval-seconds: 10

server-weights:
  hub-1: 1
  hub-2: 2

slash-servers:
  anarchy: anarchy-server
```

### Key settings

| Key | Default | Description |
|-----|---------|-------------|
| `hubs` | — | Hub server name list |
| `fallback.fallback-server` | `limbo-wait` | Holding server when all hubs are offline |
| `check-interval-seconds` | `10` | Ping and limbo check frequency |
| `routing.quarantine-threshold` | `3` | Consecutive failures before quarantine |
| `routing.quarantine-duration-ms` | `60000` | Quarantine window (ms) |
| `routing.sticky-sessions` | `false` | Prefer last known hub for returning players |
| `routing.sticky-session-ttl-ms` | `300000` | Sticky session validity period (ms) |
| `server-weights` | (none) | Per-server balancing weight (higher = more traffic) |
| `players.hub-max-players` | `100` | Hard routing cap per hub |
| `players.reserved-slots` | `5` | Slots held back from routing |

---

## Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/flowgate reload` | `flowgate.command.reload` | Reloads configs and restarts services |
| `/flowgate status` | `flowgate.command.status` or `flowgate.command.reload` | Shows backend health and metrics |
| `/hub` (configurable) | — | Sends player to best available hub |
| `/<slash-server>` | — | Sends player to a specific named server |

---

## Routing Behavior

**On join:** The plugin intercepts `JOIN_PROXY`. If any hub is healthy, the player is routed to the best hub. If no hubs are available, they land in the fallback server and are marked as searching.

**On kick:** The plugin intercepts server kicks and reroutes the player. First tries other healthy hubs (excluding the failed server). If none available, routes to limbo.

**In limbo:** Players in the limbo server are re-evaluated every `check-interval-seconds`. As soon as a hub becomes available, they are routed out automatically — no reconnect required.

---

## Sticky Sessions

When `routing.sticky-sessions: true`, the router remembers the last hub a player successfully connected to during the current proxy session. On a later routing decision in that session, if that hub is still healthy and has capacity, it is selected directly. The session expires after `sticky-session-ttl-ms` milliseconds.

Useful for networks where game state is hub-local and players should return to the same hub after a reroute.

Sticky assignments are intentionally not persisted across proxy reconnects. This keeps FlowGate-Lite stateless and avoids a storage dependency.

---

## Backend Plugin Messages

Backend `BungeeCord:Connect` messages may target configured hubs and the fallback server by default. To allow other destinations or restrict which backend servers may issue them, configure `plugin-messages.allowed-connect-servers` and `plugin-messages.allowed-source-servers`.

---

## Weighted Balancing

```yaml
server-weights:
  hub-1: 1
  hub-2: 3   # 3x more likely to receive players vs hub-1
```

The router computes a score of `playerCount / weight`. A server with weight 3 will handle up to 3x more players than a weight-1 server before its score exceeds the weight-1 server's score. Weighted balancing combines with the player limit — a full hub is never selected regardless of weight.

---

## Troubleshooting

**Players get kicked instead of being rerouted**
: Check that `fallback.fallback-server` matches a server name registered in BungeeCord. Ensure the fallback server is running.

**Hubs are quarantined frequently**
: Increase `quarantine-threshold` if backends are slow to respond. Check network latency between proxy and backends. A threshold of 5 is reasonable for high-latency links.

**Sticky sessions not working**
: The player must have previously connected to a hub successfully during the current proxy session. Check that `routing.sticky-sessions: true` and the TTL is long enough.

**`/flowgate reload` fails**
: Check the proxy console for specific config parse errors. The plugin stays on the previous valid config until reload succeeds.

---

## Production Recommendations

- Set `check-interval-seconds` to `5`–`10`. Lower values improve responsiveness but increase ping load.
- Set `quarantine-duration-ms` longer than your average backend restart time (60–120 seconds).
- Use `reserved-slots` to maintain operator access headroom during full load.
- Enable `sticky-sessions` only if your game design benefits from player-hub affinity.
- Run the limbo server on a minimal server implementation (e.g., NanoLimbo) to reduce overhead.
- Avoid setting `hub-max-players` higher than the actual server can handle — let BungeeCord's own limits act as a secondary backstop.

---

## Compatibility

| Platform | Status |
|----------|--------|
| BungeeCord | Verified: build 2104, protocol 777 / MC 26.3 |
| Waterfall | Discontinued; legacy only, untested |
| FlameCord | Untested legacy fork |
| Velocity | ✗ Not compatible (different API) |

**Java:** 17+

---

## Project Naming

| Name | Role |
|------|------|
| **FlowGate** | Ecosystem name. Used in package paths and branding. |
| **FlowGate-Lite** | This plugin. Lightweight proxy routing for BungeeCord networks. |

Future plugins under the FlowGate ecosystem may include advanced cross-proxy coordination, metrics exporters, or Velocity support.

---

© Terona Studios. All rights reserved.
