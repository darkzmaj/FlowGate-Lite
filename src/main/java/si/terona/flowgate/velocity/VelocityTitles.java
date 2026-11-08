package si.terona.flowgate.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.title.Title;
import si.terona.flowgate.config.YamlConfig;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Animated waiting titles for players held on the fallback server; mirrors the BungeeCord TitleManager. */
final class VelocityTitles {

    private static final long GRACE_MS = 2_000L;
    private static final Title.Times TIMES = Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ZERO);

    private final FlowGateVelocity plugin;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> steps = new ConcurrentHashMap<>();
    private final Map<UUID, Long> startTimes = new ConcurrentHashMap<>();
    private ScheduledTask task;

    VelocityTitles(FlowGateVelocity plugin) { this.plugin = plugin; }

    void start(Player player) {
        if (player == null || !player.isActive() || !plugin.getPluginConfig().isTitlesEnabled()) return;
        UUID uuid = player.getUniqueId();
        if (!active.add(uuid)) return;
        steps.put(uuid, 0);
        startTimes.put(uuid, System.currentTimeMillis());
        ensureRunning();
    }

    void clear(Player player) {
        if (player == null) return;
        boolean wasActive = active.contains(player.getUniqueId());
        forget(player.getUniqueId());
        if (wasActive && player.isActive()) player.resetTitle();
    }

    synchronized void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        active.clear();
        steps.clear();
        startTimes.clear();
    }

    private synchronized void ensureRunning() {
        if (task != null) return;
        task = plugin.getProxy().getScheduler().buildTask(plugin, this::tick).repeat(1L, TimeUnit.SECONDS).schedule();
    }

    private void tick() {
        YamlConfig messages = plugin.getMessages();
        if (messages == null || plugin.isShuttingDown()) return;

        for (UUID uuid : new HashSet<>(active)) {
            Player player = plugin.getProxy().getPlayer(uuid).orElse(null);
            if (player == null || !player.isActive()) {
                forget(uuid);
                continue;
            }
            if (System.currentTimeMillis() - startTimes.getOrDefault(uuid, 0L) > GRACE_MS && !plugin.isOnFallback(player)) {
                forget(uuid);
                continue;
            }
            List<String> frames = messages.getStringList("titles.waiting.animation");
            if (frames.isEmpty()) continue;
            int step = steps.getOrDefault(uuid, 0);
            steps.put(uuid, step + 1);
            player.showTitle(Title.title(
                    FlowGateVelocity.text(messages.getString("titles.waiting.title", "")),
                    FlowGateVelocity.text(frames.get(Math.floorMod(step, frames.size()))),
                    TIMES));
        }
    }

    private void forget(UUID uuid) {
        active.remove(uuid);
        steps.remove(uuid);
        startTimes.remove(uuid);
    }
}
