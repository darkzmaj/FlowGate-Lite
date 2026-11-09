package dev.korinetwork.flowgate.service;

import java.util.UUID;
import java.util.function.Consumer;

/** The few proxy operations the routing core needs; implemented once per proxy family. */
public interface ProxyPlatform {

    boolean hasServer(String name);

    /** False when the proxy restricts the server for this player (BungeeCord restricted servers). */
    boolean canAccess(UUID playerId, String server);

    int playerCount(String server);

    /** Pings the backend asynchronously; the callback receives true when it answered. */
    void ping(String server, Consumer<Boolean> callback);
}
