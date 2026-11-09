package dev.korinetwork.flowgate.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.korinetwork.flowgate.service.ProxyPlatform;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Velocity (and Velocity forks) implementation of the routing core's needs. */
final class VelocityPlatform implements ProxyPlatform {

    private final ProxyServer proxy;

    VelocityPlatform(ProxyServer proxy) { this.proxy = proxy; }

    @Override public boolean hasServer(String name) { return proxy.getServer(name).isPresent(); }

    // Velocity has no per-server access restriction like BungeeCord's "restricted" flag.
    @Override public boolean canAccess(UUID playerId, String server) { return true; }

    @Override public int playerCount(String server) {
        return proxy.getServer(server).map(s -> s.getPlayersConnected().size()).orElse(0);
    }

    @Override public void ping(String server, Consumer<Boolean> callback) {
        Optional<RegisteredServer> target = proxy.getServer(server);
        if (target.isEmpty()) { callback.accept(false); return; }
        target.get().ping().whenComplete((ping, error) -> callback.accept(error == null && ping != null));
    }

    static String currentServer(Player player) {
        return player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse(null);
    }
}
