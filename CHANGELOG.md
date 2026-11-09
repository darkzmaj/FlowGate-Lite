# 1.0.8 — 2026-11-09

An earlier 1.0.8 build without Velocity support was briefly released and then pulled. This 1.0.8 replaces it.

- Velocity support. The same `FlowGate-Lite-1.0.8.jar` now loads on BungeeCord-family proxies (BungeeCord, Waterfall, FlameCord and other forks) and on Velocity 3.4+ and 4.x.
- On Velocity: hub routing on join, balancing, health pings, quarantine, kick fallback, limbo with waiting titles and automatic exit, `/hub` and other hub commands, slash-server commands, `/flowgate reload|status` (with tab completion), MOTD and hover profiles (hex colours), and policy-checked backend `Connect` messages.
- Velocity notes: backend `Connect` messages reach FlowGate only with `bungee-plugin-message-channel = false` in `velocity.toml` (FlowGate logs a note at startup when it is `true`). Velocity has no restricted-server flag, so restricted-access checks apply on BungeeCord only. Client-sent `BungeeCord` channel messages are dropped.
- Anonymous usage statistics: once a day the plugin tells kori.my that one more server runs it (an anonymous id, the version, the platform `bungeecord` or `velocity`, and that it is a proxy). No IP addresses, server names or player data. Turn it off with `metrics.enabled: false` in `config.yml`; the setting is read before every ping, so a reload applies it. Existing configs without the key count as enabled. The ping stops when the proxy shuts down.
- Fix: the version in the startup log and `/flowgate status` comes from the plugin metadata (`bungee.yml` / `velocity-plugin.json`, both filled from the build version) instead of a hardcoded string.
- Fix: deleted example entries in `slash-servers`, `server-weights`, `server-names` and `motds` no longer come back when defaults are merged.
- Routing core (config, health monitor, router, sessions, rate limiter) is shared by both proxy families. Behaviour and config keys are unchanged.
- Build: JDK 25 toolchain (needed to read the Velocity 4 API), Java 17 bytecode, Gradle 9.8.2, BungeeCord API 1.21-R0.4, Velocity API 4.3.1, JUnit 6.1.3, Mockito 5.24.0. Nothing is shaded.

Validation: 28 unit tests. Real-proxy tests with two offline-mode bot players (Minecraft 1.21.11) and three Paper 1.21.11 backends (two hubs, one limbo) on Velocity 3.4.0 build 566 (Java 17), Velocity 3.5.1 build 615 (Java 21), Velocity 4.3.1 build 45 and 4.3.2-SNAPSHOT build 48 (Java 25), BungeeCord build 2111 and Waterfall build 615 (Java 21): balanced joins, kick fallback, `/hub`, slash-server to limbo with titles and exit, allowed and blocked backend `Connect`, hub outage to limbo and back, status, reload (a new slash command works after it), MOTD/hover, the usage ping (captured locally, correct platform) and clean shutdown. Not tested: high player counts, online mode, FlameCord and Velocity forks.

# 1.0.7 — 2026-10-16

- Fix limbo completion by observing ServerSwitchEvent after the active server changes.
- Respect restricted-server access in hub, failover, direct, and backend-message routes.
- Reject stale backend connections, oversized/malformed messages, and already-cancelled events; accept the namespaced Bungee channel alias.
- Use locale-independent server policy matching; clear waiting titles on successful switches and tolerate animation counter overflow.
- Publish configuration references across proxy threads and serialize reload requests; clear singleton on shutdown/startup failure.
- Reproducible unshaded archives, Gradle 9.1 wrapper checksum, regression tests and CI; remove generated caches from source control.

Validation: 8 JUnit regression tests; BungeeCord build 2104 startup, protocol 777 (Minecraft 26.3) status/MOTD/hover, console status/reload and shutdown. Actual protocol-777 client routing through FlowGate-Bridge between two Paper 26.3 backends is verified, including forged client message rejection. High-load behavior remains unverified. Stable Bungee API 1.21-R0.4 retained because the public API used here remains compatible with current BungeeCord. No runtime libraries are bundled.

- Bound repeated backend rejection chains to prevent infinite kick/fallback loops; route initial joins to limbo when healthy hubs are full. Stage reload candidates before publishing and make per-player message rate windows atomic.
