package dev.korinetwork.flowgate;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import dev.korinetwork.flowgate.service.ProxyPlatform;

import java.util.UUID;
import java.util.function.Consumer;

/** BungeeCord / Waterfall / FlameCord (any BungeeCord API proxy) implementation of the routing core's needs. */
public final class BungeePlatform implements ProxyPlatform {

    private final ProxyServer proxy;

    BungeePlatform(ProxyServer proxy) { this.proxy = proxy; }

    @Override public boolean hasServer(String name) { return proxy.getServerInfo(name) != null; }

    @Override public boolean canAccess(UUID playerId, String server) {
        ServerInfo info = proxy.getServerInfo(server);
        ProxiedPlayer player = proxy.getPlayer(playerId);
        return info != null && player != null && info.canAccess(player);
    }

    @Override public int playerCount(String server) {
        ServerInfo info = proxy.getServerInfo(server);
        return info == null ? 0 : info.getPlayers().size();
    }

    @Override public void ping(String server, Consumer<Boolean> callback) {
        ServerInfo info = proxy.getServerInfo(server);
        if (info == null) { callback.accept(false); return; }
        info.ping((result, error) -> callback.accept(error == null && result != null));
    }

    public static String currentServer(ProxiedPlayer player) {
        return player.getServer() != null ? player.getServer().getInfo().getName() : null;
    }
}
