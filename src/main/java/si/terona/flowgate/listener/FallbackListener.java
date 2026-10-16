package si.terona.flowgate.listener;

import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ServerKickEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;
import si.terona.flowgate.FlowGate;
import si.terona.flowgate.model.ConnectionState;
import si.terona.flowgate.model.PlayerSession;
import si.terona.flowgate.model.RoutingResult;
import si.terona.flowgate.title.TitleManager;

public final class FallbackListener implements Listener {

    private final FlowGate plugin;

    public FallbackListener(FlowGate plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onServerKick(ServerKickEvent event) {
        if (plugin.isShuttingDown() || event.isCancelled()) return;
        if (!plugin.getPluginConfig().isFallbackEnabled()) return;

        ProxiedPlayer player = event.getPlayer();
        if (player == null) return;
        if (plugin.isOnFallback(player)) return;

        String kickedFrom = event.getKickedFrom() != null ? event.getKickedFrom().getName() : null;
        PlayerSession failedSession = plugin.getPlayerSessionService().getOrCreate(player);
        if (!failedSession.recordFailedServer(kickedFrom)) {
            plugin.debug("Fallback stopped: repeated rejection by the same backend");
            return;
        }
        RoutingResult result = plugin.getRouterService().reserveFallback(player, kickedFrom);

        if (!result.isSuccess()) {
            plugin.debug("Fallback failed for " + player.getName() + ": " + result.getReason());
            return;
        }

        ServerInfo target = plugin.getProxy().getServerInfo(result.getServerName());
        if (target == null) return;

        event.setCancelled(true);
        event.setCancelServer(target);
        event.setReason(new TextComponent(""));

        plugin.getMetricsService().recordFallback();
        plugin.debug("Kick-fallback: " + player.getName() + " (" + kickedFrom + ") -> " + result.getServerName());

        if (!plugin.getRouterService().isHub(result.getServerName())) {
            if (plugin.getSearchingPlayers().add(player.getUniqueId())) {
                PlayerSession session = plugin.getPlayerSessionService().getOrCreate(player);
                session.setState(ConnectionState.LIMBO);
                if (plugin.getPluginConfig().isTitlesEnabled()) TitleManager.start(player);
                plugin.sendFallbackSearching(player);
            }
        }
    }
}
