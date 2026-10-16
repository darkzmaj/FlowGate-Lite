package si.terona.flowgate.config;

import net.md_5.bungee.config.Configuration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * Typed, immutable wrapper around the raw BungeeCord {@link Configuration}.
 * All values are read once at construction time with safe defaults.
 */
public final class PluginConfig {

    private final List<String> hubServers;
    private final String fallbackServer;
    private final int pingIntervalSeconds;
    private final long retryCooldownMs;
    private final int quarantineThreshold;
    private final long quarantineDurationMs;
    private final boolean debugMode;
    private final int hardPlayerLimit;
    private final int reservedSlots;
    private final boolean routingEnabled;
    private final boolean fallbackEnabled;
    private final boolean titlesEnabled;
    private final boolean stickySessionEnabled;
    private final long stickySessionTtlMs;
    private final Map<String, Integer> serverWeights;
    private final Set<String> pluginMessageAllowedConnectServers;
    private final Set<String> pluginMessageAllowedSourceServers;

    public PluginConfig(Configuration cfg) {
        List<String> hubs = cfg.getStringList("hubs");
        this.hubServers = hubs != null
                ? Collections.unmodifiableList(new ArrayList<>(hubs))
                : Collections.emptyList();

        this.fallbackServer       = cfg.getString("fallback.fallback-server", "limbo-wait");
        this.pingIntervalSeconds  = clamp(cfg.getInt("check-interval-seconds", 10), 1, 300);
        this.retryCooldownMs      = clampL(cfg.getLong("routing.retry-cooldown-ms", 5000L), 500L, 60_000L);
        this.quarantineThreshold  = clamp(cfg.getInt("routing.quarantine-threshold", 3), 1, 50);
        this.quarantineDurationMs = clampL(cfg.getLong("routing.quarantine-duration-ms", 60_000L), 5_000L, 600_000L);
        this.debugMode            = cfg.getBoolean("debug.enabled", false);
        this.hardPlayerLimit      = clamp(cfg.getInt("players.hub-max-players", 100), 1, 10_000);
        this.reservedSlots        = Math.min(
                clamp(cfg.getInt("players.reserved-slots", 5), 0, 1_000), hardPlayerLimit - 1);
        this.routingEnabled       = cfg.getBoolean("routing.enabled", true);
        this.fallbackEnabled      = cfg.getBoolean("fallback.enabled", true);
        this.titlesEnabled        = cfg.getBoolean("titles.enabled", true);
        this.stickySessionEnabled = cfg.getBoolean("routing.sticky-sessions", false);
        this.stickySessionTtlMs   = clampL(cfg.getLong("routing.sticky-session-ttl-ms", 300_000L), 10_000L, 3_600_000L);

        Map<String, Integer> weights = new HashMap<>();
        if (cfg.get("server-weights") instanceof Configuration) {
            for (String server : cfg.getSection("server-weights").getKeys()) {
                int w = cfg.getInt("server-weights." + server, 1);
                weights.put(server.toLowerCase(java.util.Locale.ROOT), clamp(w, 1, 1000));
            }
        }
        this.serverWeights = Collections.unmodifiableMap(weights);

        this.pluginMessageAllowedConnectServers = readLowerCaseSet(
                cfg.getStringList("plugin-messages.allowed-connect-servers"));
        this.pluginMessageAllowedSourceServers = readLowerCaseSet(
                cfg.getStringList("plugin-messages.allowed-source-servers"));
    }

    public int getServerWeight(String serverName) {
        return serverWeights.getOrDefault(serverName.toLowerCase(java.util.Locale.ROOT), 1);
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static long clampL(long v, long min, long max) { return Math.max(min, Math.min(max, v)); }

    private static Set<String> readLowerCaseSet(List<String> values) {
        Set<String> result = new HashSet<>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) result.add(value.toLowerCase(java.util.Locale.ROOT));
        }
        return Collections.unmodifiableSet(result);
    }

    public List<String> getHubServers()        { return hubServers; }
    public String getFallbackServer()          { return fallbackServer; }
    public int getPingIntervalSeconds()        { return pingIntervalSeconds; }
    public long getRetryCooldownMs()           { return retryCooldownMs; }
    public int getQuarantineThreshold()        { return quarantineThreshold; }
    public long getQuarantineDurationMs()      { return quarantineDurationMs; }
    public boolean isDebugMode()               { return debugMode; }
    public int getHardPlayerLimit()            { return hardPlayerLimit; }
    public int getReservedSlots()              { return reservedSlots; }
    public boolean isRoutingEnabled()          { return routingEnabled; }
    public boolean isFallbackEnabled()         { return fallbackEnabled; }
    public boolean isTitlesEnabled()           { return titlesEnabled; }
    public boolean isStickySessionEnabled()    { return stickySessionEnabled; }
    public long getStickySessionTtlMs()        { return stickySessionTtlMs; }
    public Map<String, Integer> getServerWeights() { return serverWeights; }
    public Set<String> getPluginMessageAllowedConnectServers() { return pluginMessageAllowedConnectServers; }
    public Set<String> getPluginMessageAllowedSourceServers() { return pluginMessageAllowedSourceServers; }
}
