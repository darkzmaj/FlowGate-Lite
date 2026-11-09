package dev.korinetwork.flowgate.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.korinetwork.flowgate.model.PlayerSession;
import dev.korinetwork.flowgate.model.RoutingResult;
import dev.korinetwork.flowgate.model.ServerHealth;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Velocity equivalents of /flowgate, the hub commands and the slash-server commands. */
final class VelocityCommands {

    private VelocityCommands() {}

    /** /flowgate reload | status */
    static final class Admin implements SimpleCommand {
        private final FlowGateVelocity plugin;

        Admin(FlowGateVelocity plugin) { this.plugin = plugin; }

        @Override
        public void execute(Invocation invocation) {
            CommandSource sender = invocation.source();
            String[] args = invocation.arguments();
            if (args.length == 0) {
                plugin.sendKey(sender, "commands.usage");
                return;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "reload":
                    if (!sender.hasPermission("flowgate.command.reload")) {
                        plugin.sendKey(sender, "commands.no-permission");
                    } else if (!plugin.reloadAll()) {
                        sender.sendMessage(FlowGateVelocity.text("&c[FlowGate-Lite] Reload failed. Check the console."));
                    } else {
                        plugin.sendKey(sender, "commands.reload");
                    }
                    break;
                case "status":
                    if (!sender.hasPermission("flowgate.command.reload") && !sender.hasPermission("flowgate.command.status")) {
                        plugin.sendKey(sender, "commands.no-permission");
                    } else {
                        sendStatus(sender);
                    }
                    break;
                default:
                    plugin.sendKey(sender, "commands.unknown");
            }
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            return invocation.arguments().length <= 1 ? List.of("reload", "status") : List.of();
        }

        private void sendStatus(CommandSource sender) {
            msg(sender, "&b[FlowGate-Lite] &fv" + plugin.getVersion() + " Status");
            Map<String, ServerHealth> health = plugin.getHealthMonitorService().getAllHealth();
            if (health.isEmpty()) {
                msg(sender, "&7  No health data yet (first ping pending).");
            } else {
                for (Map.Entry<String, ServerHealth> entry : health.entrySet()) {
                    ServerHealth h = entry.getValue();
                    String status;
                    if (h.isQuarantined()) {
                        long remaining = Math.max(0, (h.getQuarantineExpiry() - System.currentTimeMillis()) / 1000);
                        status = "&cQUARANTINED &7(retry in " + remaining + "s)";
                    } else if (h.isOnline()) {
                        status = "&aONLINE &7(" + h.getLatencyMs() + "ms)";
                    } else {
                        status = "&eOFFLINE &7(failures=" + h.getConsecutiveFailures() + ")";
                    }
                    msg(sender, "&7  &f" + entry.getKey() + "&7: " + status);
                }
            }
            for (String line : plugin.getMetricsService().getSummary().split("\n")) msg(sender, "&7" + line);
            msg(sender, "&7  Sessions: &f" + plugin.getPlayerSessionService().getActiveSessions()
                    + "  &7In limbo: &f" + plugin.getSearchingPlayers().size());
        }

        private static void msg(CommandSource sender, String text) { sender.sendMessage(FlowGateVelocity.text(text)); }
    }

    /** Sends the player to the best available hub using cached health only. */
    static final class Hub implements SimpleCommand {
        private final FlowGateVelocity plugin;

        Hub(FlowGateVelocity plugin) { this.plugin = plugin; }

        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player)) return;
            Player player = (Player) invocation.source();
            if (!player.isActive()) return;

            String current = VelocityPlatform.currentServer(player);
            PlayerSession session = plugin.getPlayerSessionService().getOrCreate(player.getUniqueId(), player.getUsername());
            RoutingResult result = plugin.getRouterService().reserveBestHub(player.getUniqueId(), current, session);

            if (!result.isSuccess()) {
                if (plugin.getPluginConfig().isFallbackEnabled()) {
                    RoutingResult limbo = plugin.getRouterService().routeToLimbo(player.getUniqueId(), current);
                    RegisteredServer fallback = limbo.isSuccess() ? plugin.getProxy().getServer(limbo.getServerName()).orElse(null) : null;
                    if (fallback != null) {
                        player.sendMessage(FlowGateVelocity.text(plugin.getMessages().getString("fallback.connecting",
                                "&eAll Hubs are offline. Sending you to fallback...")));
                        player.createConnectionRequest(fallback).fireAndForget();
                        plugin.getMetricsService().recordFallback();
                        return;
                    }
                }
                plugin.sendKey(player, "routing.no-hubs");
                return;
            }

            RegisteredServer hub = plugin.getProxy().getServer(result.getServerName()).orElse(null);
            if (hub == null) {
                plugin.getRouterService().releaseReservation(player.getUniqueId());
                plugin.getMetricsService().recordRoutingFailure("hub not found in proxy");
                return;
            }
            String name = hub.getServerInfo().getName();
            String connectMsg = plugin.getMessages().getString("slash-server.connecting", "");
            if (!connectMsg.isEmpty()) {
                player.sendMessage(FlowGateVelocity.text(connectMsg.replace("{server}", plugin.getConfig().getString("server-names." + name, name))));
            }
            player.createConnectionRequest(hub).connect().whenComplete((r, error) -> {
                plugin.getRouterService().releaseReservation(player.getUniqueId());
                if (r != null && r.isSuccessful()) {
                    plugin.getPlayerSessionService().markConnected(player.getUniqueId(), name);
                    plugin.getMetricsService().recordRoutingSuccess(name);
                    plugin.debug("HubCommand: " + player.getUsername() + " -> " + name);
                } else {
                    plugin.getMetricsService().recordRoutingFailure("connect callback false");
                }
            });
        }
    }

    /** Sends the player to one configured server, falling back to limbo when every hub is down. */
    static final class Slash implements SimpleCommand {
        private final FlowGateVelocity plugin;
        private final String targetServer;

        Slash(FlowGateVelocity plugin, String targetServer) {
            this.plugin = plugin;
            this.targetServer = targetServer;
        }

        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player)) return;
            Player player = (Player) invocation.source();
            RegisteredServer target = plugin.getProxy().getServer(targetServer).orElse(null);
            if (target == null) {
                plugin.sendKey(player, "slash-server.not-exist");
                return;
            }
            player.createConnectionRequest(target).connect().whenComplete((r, error) -> {
                ConnectionRequestBuilder.Status status = r == null ? null : r.getStatus();
                if (status == ConnectionRequestBuilder.Status.SUCCESS) {
                    String msg = plugin.getMessages().getString("slash-server.connecting", "");
                    if (!msg.isEmpty()) {
                        player.sendMessage(FlowGateVelocity.text(plugin.getMessages().getString("prefix", "") + " "
                                + msg.replace("{server}", plugin.getConfig().getString("server-names." + targetServer, targetServer))));
                    }
                } else if (status == ConnectionRequestBuilder.Status.ALREADY_CONNECTED
                        || status == ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS) {
                    return; // nothing failed; the player is (or is becoming) connected there
                } else if (!plugin.getRouterService().isAnyHubAvailable()) {
                    plugin.sendKey(player, "slash-server.fallback");
                    sendToFallback(player);
                } else {
                    plugin.sendKey(player, "slash-server.unavailable");
                }
            });
        }

        private void sendToFallback(Player player) {
            if (!plugin.getPluginConfig().isFallbackEnabled() || plugin.isOnFallback(player)) return;
            plugin.getProxy().getServer(plugin.getPluginConfig().getFallbackServer()).ifPresent(fallback ->
                    player.createConnectionRequest(fallback).connect().whenComplete((r, error) -> {
                        if (r != null && r.isSuccessful() && plugin.getPluginConfig().isTitlesEnabled()) {
                            plugin.getTitles().start(player);
                        }
                    }));
        }
    }
}
