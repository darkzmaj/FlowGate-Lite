package si.terona.flowgate.command;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import si.terona.flowgate.FlowGate;
import si.terona.flowgate.model.RoutingResult;
import si.terona.flowgate.model.PlayerSession;

/**
 * Hub command – sends the player to the best available hub server.
 * Uses cached health snapshots only; never triggers a live ping.
 */
public final class HubCommand extends Command {

    private final FlowGate plugin;

    public HubCommand(String name, FlowGate plugin) {
        super(name);
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer)) return;
        ProxiedPlayer player = (ProxiedPlayer) sender;
        if (!player.isConnected()) return;

        // Find best hub using cached health – no pings
        PlayerSession session = plugin.getPlayerSessionService().getOrCreate(player);
        RoutingResult result = plugin.getRouterService().reserveBestHub(player, session);

        if (!result.isSuccess()) {
            // All hubs unavailable – try limbo as last resort
            if (plugin.getPluginConfig().isFallbackEnabled()) {
                RoutingResult limbo = plugin.getRouterService().routeToLimbo(player);
                if (limbo.isSuccess()) {
                    ServerInfo fallback = plugin.getProxy().getServerInfo(limbo.getServerName());
                    if (fallback != null) {
                        String msg = plugin.getMessages()
                                .getString("fallback.connecting",
                                        "&eAll Hubs are offline. Sending you to fallback...");
                        player.sendMessage(color(msg));
                        player.connect(fallback);
                        plugin.getMetricsService().recordFallback();
                        return;
                    }
                }
            }
            plugin.sendMessage(player, "routing.no-hubs");
            return;
        }

        ServerInfo hub = plugin.getProxy().getServerInfo(result.getServerName());
        if (hub == null) {
            plugin.getRouterService().releaseReservation(player.getUniqueId());
            plugin.getMetricsService().recordRoutingFailure("hub not found in proxy");
            return;
        }

        String displayName = plugin.getConfig()
                .getString("server-names." + hub.getName(), hub.getName());

        String connectMsg = plugin.getMessages().getString("slash-server.connecting", "");
        if (connectMsg != null && !connectMsg.isEmpty()) {
            player.sendMessage(color(connectMsg.replace("{server}", displayName)));
        }

        player.connect(hub, (success, error) -> {
            plugin.getRouterService().releaseReservation(player.getUniqueId());
            if (success) {
                plugin.getPlayerSessionService().markConnected(player.getUniqueId(), hub.getName());
                plugin.getMetricsService().recordRoutingSuccess(hub.getName());
                plugin.debug("HubCommand: " + player.getName() + " → " + hub.getName());
            } else {
                plugin.getMetricsService().recordRoutingFailure("connect callback false");
            }
        });
    }

    private BaseComponent[] color(String text) {
        return new BaseComponent[]{ new TextComponent(
                ChatColor.translateAlternateColorCodes('&', text)) };
    }
}
