# 1.0.7 — 2026-10-16

- Fix limbo completion by observing ServerSwitchEvent after the active server changes.
- Respect restricted-server access in hub, failover, direct, and backend-message routes.
- Reject stale backend connections, oversized/malformed messages, and already-cancelled events; accept the namespaced Bungee channel alias.
- Use locale-independent server policy matching; clear waiting titles on successful switches and tolerate animation counter overflow.
- Publish configuration references across proxy threads and serialize reload requests; clear singleton on shutdown/startup failure.
- Reproducible unshaded archives, Gradle 9.1 wrapper checksum, regression tests and CI; remove generated caches from source control.

Validation: 4 JUnit regression tests; BungeeCord build 2104 startup, protocol 777 (Minecraft 26.3) status/MOTD/hover, console status/reload and shutdown. Live player routing and load tests remain unverified. Stable Bungee API 1.21-R0.4 retained because the public API used here remains compatible with current BungeeCord. No runtime libraries are bundled.
