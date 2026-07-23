package si.terona.flowgate.listener;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import si.terona.flowgate.FlowGate;
import si.terona.flowgate.model.ConnectionState;
import si.terona.flowgate.model.PlayerSession;
import si.terona.flowgate.title.TitleManager;

import java.util.UUID;

public final class LimboRouterListener implements Listener {

    private final FlowGate plugin;

    public LimboRouterListener(FlowGate plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onServerConnected(ServerConnectedEvent event) {
        ProxiedPlayer player = event.getPlayer();
        if (player == null) return;
        plugin.getRouterService().releaseReservation(player.getUniqueId());
        if (!plugin.isOnFallback(player)) return;

        UUID uuid = player.getUniqueId();
        if (!plugin.getSearchingPlayers().add(uuid)) return;

        PlayerSession session = plugin.getPlayerSessionService().getOrCreate(player);
        session.setState(ConnectionState.LIMBO);

        if (plugin.getPluginConfig().isTitlesEnabled()) TitleManager.start(player);

        plugin.sendFallbackSearching(player);
        plugin.getMetricsService().recordReconnect();
        plugin.debug(player.getName() + " entered limbo.");
    }

    @EventHandler
    public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        ProxiedPlayer player = event.getPlayer();
        if (player == null) return;

        UUID uuid = player.getUniqueId();
        plugin.getSearchingPlayers().remove(uuid);
        plugin.getRouterService().releaseReservation(uuid);
        plugin.getPlayerSessionService().remove(uuid);
        TitleManager.clear(player);
    }
}
