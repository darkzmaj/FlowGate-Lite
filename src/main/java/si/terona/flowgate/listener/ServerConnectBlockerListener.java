package si.terona.flowgate.listener;

import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;
import si.terona.flowgate.FlowGate;
import si.terona.flowgate.model.ConnectionState;
import si.terona.flowgate.model.RoutingResult;
import si.terona.flowgate.title.TitleManager;

public final class ServerConnectBlockerListener implements Listener {

    private final FlowGate plugin;

    public ServerConnectBlockerListener(FlowGate plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onServerConnect(ServerConnectEvent event) {
        if (plugin.isShuttingDown() || event.isCancelled()) return;
        if (event.getReason() != ServerConnectEvent.Reason.JOIN_PROXY) return;
        if (!plugin.getPluginConfig().isRoutingEnabled()) return;

        ProxiedPlayer player = event.getPlayer();
        if (player == null || event.getTarget() == null) return;

        RoutingResult result = plugin.getRouterService().reserveBestHub(
                player, plugin.getPlayerSessionService().getOrCreate(player));
        if (result.isSuccess()) {
            ServerInfo hub = plugin.getProxy().getServerInfo(result.getServerName());
            if (hub != null && !hub.getName().equalsIgnoreCase(event.getTarget().getName())) {
                event.setTarget(hub);
                plugin.debug("JOIN_PROXY -> " + hub.getName() + " [" + player.getName() + "]");
            }
        } else if (plugin.getPluginConfig().isFallbackEnabled()) {
            RoutingResult limbo = plugin.getRouterService().routeToLimbo(player);
            if (limbo.isSuccess()) {
                ServerInfo limboServer = plugin.getProxy().getServerInfo(limbo.getServerName());
                if (limboServer != null) {
                    event.setTarget(limboServer);
                    plugin.getSearchingPlayers().add(player.getUniqueId());
                    plugin.getPlayerSessionService().getOrCreate(player)
                            .setState(ConnectionState.LIMBO);
                    if (plugin.getPluginConfig().isTitlesEnabled()) TitleManager.start(player);
                    plugin.debug("JOIN_PROXY -> limbo [" + player.getName() + "]");
                }
            }
        }
    }
}
