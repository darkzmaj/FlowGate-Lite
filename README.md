# FlowGate Lite

**Sends every player to the best hub on your BungeeCord or Velocity proxy, and keeps them waiting in limbo instead of kicking them when no hub is free.**

FlowGate Lite checks your hubs all the time and sends each new player to the least busy healthy one. When every hub is down or full, players wait on a limbo server and are moved to a hub by themselves as soon as one is free. It runs only on the proxy, and the same jar works on BungeeCord, Waterfall and Velocity.

## Features

- **Health checks.** Pings every hub in the background and skips a hub that stops answering until it is back.
- **Least busy hub.** New players go to the healthy hub with the fewest players, with optional weights and a player cap.
- **Limbo.** When no hub is free, players wait on a fallback server with a "Waiting" title and leave by themselves when a hub is ready.
- **Kick fallback.** A player kicked from a backend goes to another hub or to limbo instead of being disconnected.
- **Hub and server commands.** `/hub`, `/lobby` and your own commands such as `/survival`.
- **Backend requests.** Checks `Connect` requests from game servers, for example from FlowGate Bridge, against your allow list.
- **MOTD.** Server list text and hover lines with `&` codes and hex colours.
- **Status.** `/flowgate status` shows every server's health and routing counters.

## Supported platforms

- **Velocity:** 3.4 and newer, including 4.x (tested on 3.4.0, 3.5.1, 4.3.1 and 4.3.2).
- **BungeeCord and Waterfall:** current builds (tested on BungeeCord 2111 and Waterfall 615).
- **Java:** 17 or newer. Velocity 4 needs Java 25.
- **On Velocity:** restricted-server checks are BungeeCord-only, and backend `Connect` checks need `bungee-plugin-message-channel = false` in `velocity.toml`.

## Installation

1. Download `FlowGate-Lite-1.0.8.jar` from one of the links below.
2. Put it in the proxy's `plugins` folder and restart the proxy.
3. Put your hub and limbo server names in `config.yml`, then run `/flowgate reload`.

Full setup: [Install guide](https://kori.my/docs/flowgate-lite/install/).

## Privacy

Once a day FlowGate Lite tells kori.my that one more server runs it: an anonymous ID, the plugin version, the platform and that it is a proxy. No IP addresses, server names or player data. Turn it off with `metrics.enabled: false` in `config.yml`. Details: [Privacy Policy](https://kori.my/privacy/).

## Links

- **Documentation:** https://kori.my/docs/flowgate-lite/
- **Download:** [GitHub](https://github.com/darkzmaj/FlowGate-Lite/releases/latest), [Modrinth](https://modrinth.com/plugin/flowgate-lite), [SpigotMC](https://www.spigotmc.org/resources/132152/)
- **Source code:** https://github.com/darkzmaj/FlowGate-Lite
- **Support:** https://kori.my

Free to use, with the source code on GitHub. Made by DarkZmaj for KORI Network.

## For developers

Build with JDK 25 (needed to read the Velocity 4 API; Gradle finds an installed JDK 25 through its toolchain support): `bash gradlew clean build`. Output: `build/libs/FlowGate-Lite-<version>.jar`. The version is set once, in `build.gradle`. See [CHANGELOG.md](CHANGELOG.md) for changes and validation limits, and the release notes in [docs/releases](docs/releases/).

The **Publish release JAR** workflow runs for new `vMAJOR.MINOR.PATCH` tags. To attach assets to an existing release, run it manually from `main` with the exact version tag (for example `v1.0.7`). It checks out the resolved tag commit, uses Java 25, runs `clean build` and existing tests, validates plugin metadata and bytecode, and preserves the JAR as an Actions artifact before publishing. The build has read-only repository access; only the separate publishing job has `contents: write`. Existing assets are compared and never overwritten. The audited release tag and original JAR checksum are pinned for v1.0.7; a mismatch stops publication.

Repository Actions must be enabled and permit the workflow's `GITHUB_TOKEN` to request `contents: write` (organization policy must permit it). No personal access token is required. No tag or release needs to be deleted or recreated. See the release notes ([1.0.8](docs/releases/v1.0.8.md), [1.0.7](docs/releases/v1.0.7.md)) for verified platforms and limitations.

© Terona Studios. All rights reserved.
