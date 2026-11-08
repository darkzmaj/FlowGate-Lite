# 1.1.0 — unreleased

- One universal JAR: the same file now loads on BungeeCord-family proxies (BungeeCord, Waterfall, FlameCord and other BungeeCord API forks) and on Velocity 3.4+ / 4.x and Velocity forks.
- Velocity support: health-checked hub routing for initial joins, kick fallback/limbo, limbo auto-exit, `/flowgate reload|status` (with tab completion), hub and slash-server commands, waiting titles, MOTD/hover profiles (hex colours) and policy-checked backend `Connect` messages when Velocity's own `bungee-plugin-message-channel` is disabled. Client-sent `BungeeCord` channel messages are dropped on Velocity.
- Routing core (config, health monitor, router, sessions, rate limiter) is now platform-neutral; behaviour and configuration keys are unchanged.
- Fix: deleted example entries in `slash-servers`, `server-weights`, `server-names` and `motds` no longer reappear when defaults are merged.
- Fix: version shown in the startup log and `/flowgate status` now comes from the plugin metadata instead of a hard-coded string.
- Build: Gradle 9.8.2 (official wrapper checksum), JDK 25 toolchain emitting Java 17 bytecode, BungeeCord API 1.21-R0.4 (latest stable), Velocity API 4.3.1, JUnit 6.1.3, Mockito 5.24.0. Nothing is shaded.

Validation: 19 unit tests (including Velocity listener, plugin-message and config-merge tests). Tested with bot players (1.21.11) and Paper 1.21.11 backends on BungeeCord build 2111, Waterfall build 615, Velocity 4.3.1 build 45 (Java 25) and Velocity 3.4.0 build 566 (Java 17): balanced joins, kick fallback, `/hub`, slash-server to limbo with titles and auto-exit, allowed/blocked backend `Connect` messages, hub outage to limbo and back, status/reload, MOTD/hover, clean shutdown. Adventure call sites were checked against Velocity 3.4's bundled Adventure. Not tested: high player counts, FlameCord and Velocity forks individually.

# 1.0.7 — 2026-10-16

- Fix limbo completion by observing ServerSwitchEvent after the active server changes.
- Respect restricted-server access in hub, failover, direct, and backend-message routes.
- Reject stale backend connections, oversized/malformed messages, and already-cancelled events; accept the namespaced Bungee channel alias.
- Use locale-independent server policy matching; clear waiting titles on successful switches and tolerate animation counter overflow.
- Publish configuration references across proxy threads and serialize reload requests; clear singleton on shutdown/startup failure.
- Reproducible unshaded archives, Gradle 9.1 wrapper checksum, regression tests and CI; remove generated caches from source control.

Validation: 8 JUnit regression tests; BungeeCord build 2104 startup, protocol 777 (Minecraft 26.3) status/MOTD/hover, console status/reload and shutdown. Actual protocol-777 client routing through FlowGate-Bridge between two Paper 26.3 backends is verified, including forged client message rejection. High-load behavior remains unverified. Stable Bungee API 1.21-R0.4 retained because the public API used here remains compatible with current BungeeCord. No runtime libraries are bundled.

- Bound repeated backend rejection chains to prevent infinite kick/fallback loops; route initial joins to limbo when healthy hubs are full. Stage reload candidates before publishing and make per-player message rate windows atomic.
