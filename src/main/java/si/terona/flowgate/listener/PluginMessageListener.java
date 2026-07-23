package si.terona.flowgate.listener;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import si.terona.flowgate.FlowGate;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Intercepts BungeeCord plugin-channel messages originating from backend servers.
 *
 * In BungeeCord, when a Bukkit plugin sends a plugin message via player.sendPluginMessage(),
 * the proxy fires PluginMessageEvent with the backend Server as the sender and the
 * ProxiedPlayer as the receiver. This listener validates those messages before BungeeCord
 * processes them natively.
 *
 * Security: invalid destinations are cancelled. Rate limiting applied per player.
 */
public final class PluginMessageListener implements Listener {

    private static final int RATE_LIMIT_PER_SECOND = 10;

    private final FlowGate plugin;
    private final ConcurrentHashMap<UUID, AtomicInteger> rateCounts  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AtomicLong>   rateWindows = new ConcurrentHashMap<>();

    public PluginMessageListener(FlowGate plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getTag().equals("BungeeCord")) return;

        // Backend → proxy messages have Server as sender; receiver is the ProxiedPlayer.
        if (!(event.getSender() instanceof Server)) return;
        if (!(event.getReceiver() instanceof ProxiedPlayer)) return;

        ProxiedPlayer player = (ProxiedPlayer) event.getReceiver();
        Server source = (Server) event.getSender();

        ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
        String subchannel;
        try {
            subchannel = in.readUTF();
        } catch (Exception e) {
            event.setCancelled(true);
            return;
        }

        if (!subchannel.equals("Connect")) return;

        // BungeeCord "Connect" format: writeUTF("Connect"), writeUTF("server-name")
        // The player to connect is the ProxiedPlayer sender.
        String targetServerName;
        try {
            targetServerName = in.readUTF();
        } catch (Exception e) {
            event.setCancelled(true);
            return;
        }

        ServerInfo target = plugin.getProxy().getServerInfo(targetServerName);
        if (target == null || !plugin.getRouterService().isPluginMessageDestinationAllowed(targetServerName)) {
            plugin.debug("Plugin message blocked: disallowed destination '" + targetServerName + "'");
            event.setCancelled(true);
            return;
        }

        if (!plugin.getRouterService().isPluginMessageSourceAllowed(source.getInfo().getName())) {
            plugin.debug("Plugin message blocked: disallowed source '" + source.getInfo().getName() + "'");
            event.setCancelled(true);
            return;
        }

        if (isRateLimited(player.getUniqueId())) {
            plugin.debug("Plugin message rate-limited for: " + player.getName());
            event.setCancelled(true);
            return;
        }

        if (plugin.getRouterService().isHub(targetServerName)
                && !plugin.getRouterService().reserveHub(player, targetServerName)) {
            plugin.debug("Plugin message blocked: hub unavailable or full '" + targetServerName + "'");
            event.setCancelled(true);
            return;
        }

        plugin.debug("Plugin message Connect: " + player.getName() + " -> " + targetServerName);
        // Let BungeeCord handle the validated message natively.
    }

    // Rate limiting

    private boolean isRateLimited(UUID playerId) {
        long now = System.currentTimeMillis();

        AtomicLong windowRef = rateWindows.computeIfAbsent(playerId, k -> new AtomicLong(now));
        long window = windowRef.get();

        if (now - window > 1000L) {
            windowRef.set(now);
            rateCounts.computeIfAbsent(playerId, k -> new AtomicInteger(0)).set(1);
            return false;
        }

        int count = rateCounts.computeIfAbsent(playerId, k -> new AtomicInteger(0)).incrementAndGet();
        return count > RATE_LIMIT_PER_SECOND;
    }

    public void removeRateLimit(UUID playerId) {
        rateCounts.remove(playerId);
        rateWindows.remove(playerId);
    }
}
