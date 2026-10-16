package si.terona.flowgate.command;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import si.terona.flowgate.FlowGate;
import si.terona.flowgate.title.TitleManager;

/**
 * Sends a player to a specific configured server via a slash command.
 * On failure, routes to the fallback server if available.
 */
public final class SlashServerCommand extends Command {

    private final String targetServer;
    private final FlowGate plugin;

    public SlashServerCommand(String commandName, String targetServer, FlowGate plugin) {
        super(commandName);
        this.targetServer = targetServer;
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer)) return;
        ProxiedPlayer player = (ProxiedPlayer) sender;

        ServerInfo target = ProxyServer.getInstance().getServerInfo(targetServer);
        if (target == null || !target.canAccess(player)) {
            sendKey(player, "slash-server.not-exist");
            return;
        }

        player.connect(target, (success, error) -> {
            if (success) {
                String displayName = plugin.getConfig()
                        .getString("server-names." + targetServer, targetServer);
                String msg = plugin.getMessages().getString("slash-server.connecting", "");
                if (msg != null && !msg.isEmpty()) {
                    String prefix = plugin.getMessages().getString("prefix", "");
                    player.sendMessage(new TextComponent(
                            color(prefix + " " + msg.replace("{server}", displayName))));
                }
                return;
            }

            // Connect failed – check for available hub or route to fallback
            if (!plugin.getRouterService().isAnyHubAvailable()) {
                sendKey(player, "slash-server.fallback");
                sendToFallback(player);
            } else {
                sendKey(player, "slash-server.unavailable");
            }
        });
    }

    private void sendToFallback(ProxiedPlayer player) {
        if (!plugin.getPluginConfig().isFallbackEnabled()) return;

        String fallbackName = plugin.getPluginConfig().getFallbackServer();
        ServerInfo fallback = ProxyServer.getInstance().getServerInfo(fallbackName);
        if (fallback == null || !fallback.canAccess(player)) return;

        if (player.getServer() != null &&
                player.getServer().getInfo().getName().equalsIgnoreCase(fallbackName)) {
            return; // already there
        }

        player.connect(fallback, (success, error) -> {
            if (success && plugin.getPluginConfig().isTitlesEnabled()) {
                TitleManager.start(player);
            }
        });
    }

    private void sendKey(ProxiedPlayer player, String key) {
        String msg = plugin.getMessages().getString(key);
        if (msg == null || msg.isEmpty()) return;
        String prefix = plugin.getMessages().getString("prefix", "");
        player.sendMessage(new TextComponent(color(prefix + " " + msg)));
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
