package si.terona.flowgate.title;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.config.Configuration;
import si.terona.flowgate.FlowGate;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages animated waiting titles for players held in the limbo/fallback server.
 *
 * A single global scheduler drives all active title sessions. Call {@link #shutdown()}
 * on plugin disable to cancel the scheduler and clear state. All public methods are
 * safe to call from any thread.
 */
public final class TitleManager {

    private static final Set<UUID>          ACTIVE     = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Integer> STEPS      = new ConcurrentHashMap<>();
    private static final Map<UUID, Long>    START_TIME = new ConcurrentHashMap<>();

    /** Grace period before auto-clearing a title when the player leaves limbo. */
    private static final long GRACE_MS = 2_000L;

    private static volatile ScheduledTask schedulerTask = null;

    private TitleManager() {}

    // Public API

    /** Begins showing animated titles to {@code player}. Idempotent. */
    public static void start(ProxiedPlayer player) {
        if (player == null || !player.isConnected()) return;

        FlowGate plugin = FlowGate.get();
        if (plugin == null) return;
        if (!plugin.getPluginConfig().isTitlesEnabled()) return;

        UUID uuid = player.getUniqueId();
        if (!ACTIVE.add(uuid)) return;

        STEPS.put(uuid, 0);
        START_TIME.put(uuid, System.currentTimeMillis());

        ensureSchedulerRunning(plugin);
    }

    /** Stops showing titles for {@code player} and clears the screen title. */
    public static void clear(ProxiedPlayer player) {
        if (player != null) {
            boolean active = ACTIVE.contains(player.getUniqueId());
            clearByUUID(player.getUniqueId());
            if (active && player.isConnected()) player.sendTitle(ProxyServer.getInstance().createTitle().reset());
        }
    }

    /** Cancels the global scheduler and clears all active title sessions. Call from {@code onDisable()}. */
    public static synchronized void shutdown() {
        if (schedulerTask != null) {
            schedulerTask.cancel();
            schedulerTask = null;
        }
        ACTIVE.clear();
        STEPS.clear();
        START_TIME.clear();
    }

    // Scheduler

    private static synchronized void ensureSchedulerRunning(FlowGate plugin) {
        if (schedulerTask != null) return;

        schedulerTask = ProxyServer.getInstance().getScheduler().schedule(
                plugin,
                TitleManager::tick,
                0L, 1L,
                TimeUnit.SECONDS
        );
    }

    private static void tick() {
        FlowGate plugin = FlowGate.get();
        if (plugin == null) return;

        String fallbackName = plugin.getPluginConfig().getFallbackServer();
        Configuration messages = plugin.getMessages();
        if (messages == null) return;

        for (UUID uuid : new HashSet<>(ACTIVE)) {
            ProxiedPlayer player = ProxyServer.getInstance().getPlayer(uuid);

            if (player == null || !player.isConnected()) {
                clearByUUID(uuid);
                continue;
            }

            long sinceStart = System.currentTimeMillis() - START_TIME.getOrDefault(uuid, 0L);
            if (sinceStart > GRACE_MS) {
                if (player.getServer() == null ||
                        !player.getServer().getInfo().getName().equalsIgnoreCase(fallbackName)) {
                    clearByUUID(uuid);
                    continue;
                }
            }

            sendFrame(player, uuid, messages);
        }
    }

    private static void sendFrame(ProxiedPlayer player, UUID uuid, Configuration messages) {
        List<String> frames = messages.getStringList("titles.waiting.animation");
        if (frames.isEmpty()) return;

        int step = STEPS.getOrDefault(uuid, 0);
        STEPS.put(uuid, step + 1);

        BaseComponent[] title    = components(messages.getString("titles.waiting.title", ""));
        BaseComponent[] subtitle = components(frames.get(Math.floorMod(step, frames.size())));

        player.sendTitle(
                ProxyServer.getInstance()
                        .createTitle()
                        .title(title)
                        .subTitle(subtitle)
                        .fadeIn(0)
                        .stay(40)
                        .fadeOut(0)
        );
    }

    private static void clearByUUID(UUID uuid) {
        ACTIVE.remove(uuid);
        STEPS.remove(uuid);
        START_TIME.remove(uuid);
    }

    private static BaseComponent[] components(String text) {
        if (text == null || text.isEmpty()) return new BaseComponent[]{ new TextComponent("") };
        return new BaseComponent[]{ new TextComponent(ChatColor.translateAlternateColorCodes('&', text)) };
    }
}