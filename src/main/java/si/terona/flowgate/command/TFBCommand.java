package si.terona.flowgate.command;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.plugin.Command;
import si.terona.flowgate.FlowGate;
import si.terona.flowgate.model.ServerHealth;

import java.util.Map;

public final class TFBCommand extends Command {

    private final FlowGate plugin;

    public TFBCommand(FlowGate plugin) {
        super("flowgate");
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            send(sender, "commands.usage");
            return;
        }

        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "reload":
                if (!sender.hasPermission("flowgate.command.reload")) {
                    send(sender, "commands.no-permission");
                    return;
                }
                if (!plugin.reloadAll()) {
                    msg(sender, "&c[FlowGate-Lite] Reload failed. Check the console.");
                } else {
                    send(sender, "commands.reload");
                }
                break;

            case "status":
                if (!sender.hasPermission("flowgate.command.reload")
                        && !sender.hasPermission("flowgate.command.status")) {
                    send(sender, "commands.no-permission");
                    return;
                }
                sendStatus(sender);
                break;

            default:
                send(sender, "commands.unknown");
                break;
        }
    }

    private void sendStatus(CommandSender sender) {
        msg(sender, "&b[FlowGate-Lite] &fv1.0.7 Status");

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

        for (String line : plugin.getMetricsService().getSummary().split("\n")) {
            msg(sender, "&7" + line);
        }

        msg(sender, "&7  Sessions: &f" + plugin.getPlayerSessionService().getActiveSessions()
                + "  &7In limbo: &f" + plugin.getSearchingPlayers().size());
    }

    private void send(CommandSender sender, String path) {
        String prefix = plugin.getMessages().getString("prefix", "");
        String text   = plugin.getMessages().getString(path, "");
        if (text == null || text.isEmpty()) return;
        msg(sender, prefix + " " + text);
    }

    private static void msg(CommandSender sender, String text) {
        sender.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&', text)));
    }
}
