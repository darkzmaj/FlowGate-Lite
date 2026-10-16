package si.terona.flowgate.service;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import si.terona.flowgate.config.PluginConfig;
import si.terona.flowgate.model.PlayerSession;
import si.terona.flowgate.model.RoutingResult;
import si.terona.flowgate.model.ServerHealth;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class RouterService {

    private final HealthMonitorService healthMonitor;
    private volatile PluginConfig config;
    private final Map<UUID, Reservation> reservations = new HashMap<>();

    private static final long RESERVATION_TIMEOUT_MS = 15_000L;

    public RouterService(HealthMonitorService healthMonitor, PluginConfig config) {
        this.healthMonitor = healthMonitor;
        this.config        = config;
    }

    // Routing

    public RoutingResult findBestHub(ProxiedPlayer player) {
        return findBestHub(player, null);
    }

    public RoutingResult findBestHub(ProxiedPlayer player, PlayerSession session) {
        purgeExpiredReservations();
        if (!config.isRoutingEnabled()) {
            return RoutingResult.failure("Routing is disabled");
        }

        String current = currentServerName(player);
        int limit = Math.max(1, config.getHardPlayerLimit() - config.getReservedSlots());

        // Sticky session: re-route to last known-good hub if still within TTL and healthy
        if (config.isStickySessionEnabled() && session != null) {
            String sticky = session.getLastSuccessfulServer();
            if (sticky != null && !sticky.equalsIgnoreCase(current)) {
                long age = System.currentTimeMillis() - session.getStickySessionTime();
                if (age < config.getStickySessionTtlMs()) {
                    ServerHealth health = healthMonitor.getHealth(sticky);
                    if (health != null && health.isAvailable()) {
                        ServerInfo info = ProxyServer.getInstance().getServerInfo(sticky);
                        if (info != null && info.canAccess(player) && getEffectivePlayerCount(info) < limit) {
                            return RoutingResult.success(sticky);
                        }
                    }
                }
            }
        }

        // Weighted least-players selection
        ServerInfo best = null;
        double bestScore = Double.MAX_VALUE;

        for (String hubName : config.getHubServers()) {
            if (hubName.equalsIgnoreCase(current)) continue;

            ServerHealth health = healthMonitor.getHealth(hubName);
            if (health == null || !health.isAvailable()) continue;

            ServerInfo info = ProxyServer.getInstance().getServerInfo(hubName);
            if (info == null || !info.canAccess(player)) continue;

            int count = getEffectivePlayerCount(info);
            if (count >= limit) continue;

            // Lower score = preferred. Weight divides the load, so heavier servers score lower.
            double score = (double) count / config.getServerWeight(hubName);
            if (score < bestScore) {
                bestScore = score;
                best = info;
            }
        }

        return best != null
                ? RoutingResult.success(best.getName())
                : RoutingResult.failure("No available hubs (all offline, quarantined, or full)");
    }

    /**
     * Selects and reserves a hub slot for a pending connection. The reservation is
     * released when the player connects, disconnects, or the connection fails.
     */
    public synchronized RoutingResult reserveBestHub(ProxiedPlayer player, PlayerSession session) {
        RoutingResult result = findBestHub(player, session);
        if (result.isSuccess()) {
            reservations.put(player.getUniqueId(), new Reservation(result.getServerName(), System.currentTimeMillis()));
        }
        return result;
    }

    public synchronized boolean reserveHub(ProxiedPlayer player, String serverName) {
        purgeExpiredReservations();
        if (!isHub(serverName) || !healthMonitor.isHealthy(serverName)) return false;

        ServerInfo info = ProxyServer.getInstance().getServerInfo(serverName);
        int limit = Math.max(1, config.getHardPlayerLimit() - config.getReservedSlots());
        if (info == null || !info.canAccess(player) || getEffectivePlayerCount(info) >= limit) return false;

        reservations.put(player.getUniqueId(), new Reservation(info.getName(), System.currentTimeMillis()));
        return true;
    }

    public synchronized void releaseReservation(UUID playerId) {
        reservations.remove(playerId);
    }

    public void reload(PluginConfig config) {
        this.config = config;
    }

    public RoutingResult findFallback(ProxiedPlayer player, String failedServer) {
        if (!config.isFallbackEnabled()) {
            return RoutingResult.failure("Fallback is disabled");
        }

        String current = currentServerName(player);
        ServerInfo best = null;
        double bestScore = Double.MAX_VALUE;
        int limit = Math.max(1, config.getHardPlayerLimit() - config.getReservedSlots());

        for (String hubName : config.getHubServers()) {
            if (hubName.equalsIgnoreCase(current)) continue;
            if (failedServer != null && hubName.equalsIgnoreCase(failedServer)) continue;

            ServerHealth health = healthMonitor.getHealth(hubName);
            if (health == null || !health.isAvailable()) continue;

            ServerInfo info = ProxyServer.getInstance().getServerInfo(hubName);
            if (info == null || !info.canAccess(player)) continue;

            int count = getEffectivePlayerCount(info);
            if (count >= limit) continue;

            double score = (double) count / config.getServerWeight(hubName);
            if (score < bestScore) {
                bestScore = score;
                best = info;
            }
        }

        if (best != null) return RoutingResult.success(best.getName());
        if (failedServer != null && isFallback(failedServer)) return RoutingResult.failure("Fallback server rejected the connection");
        return routeToLimbo(player);
    }

    /** Selects a failover target and reserves capacity when it is a hub. */
    public synchronized RoutingResult reserveFallback(ProxiedPlayer player, String failedServer) {
        purgeExpiredReservations();
        RoutingResult result = findFallback(player, failedServer);
        if (result.isSuccess() && isHub(result.getServerName())) {
            reservations.put(player.getUniqueId(),
                    new Reservation(result.getServerName(), System.currentTimeMillis()));
        }
        return result;
    }

    public RoutingResult routeToLimbo(ProxiedPlayer player) {
        String name = config.getFallbackServer();
        if (name == null || name.isEmpty()) {
            return RoutingResult.failure("No fallback server configured");
        }
        ServerInfo fallback = ProxyServer.getInstance().getServerInfo(name);
        if (fallback == null || !fallback.canAccess(player)) {
            return RoutingResult.failure("Fallback server '" + name + "' not found in proxy");
        }
        if (name.equalsIgnoreCase(currentServerName(player))) {
            return RoutingResult.failure("Player is already on the fallback server");
        }
        return RoutingResult.success(fallback.getName());
    }

    // Queries

    public boolean isAnyHubAvailable() {
        for (String name : config.getHubServers()) {
            ServerHealth h = healthMonitor.getHealth(name);
            if (h != null && h.isAvailable()) return true;
        }
        return false;
    }

    public boolean isHub(String serverName) {
        if (serverName == null) return false;
        for (String hub : config.getHubServers()) {
            if (hub.equalsIgnoreCase(serverName)) return true;
        }
        return false;
    }

    public boolean isFallback(String serverName) {
        return serverName != null && config.getFallbackServer().equalsIgnoreCase(serverName);
    }

    public boolean isPluginMessageDestinationAllowed(String serverName) {
        if (serverName == null) return false;
        if (config.getPluginMessageAllowedConnectServers().isEmpty()) {
            return isHub(serverName) || isFallback(serverName);
        }
        return config.getPluginMessageAllowedConnectServers().contains(serverName.toLowerCase(java.util.Locale.ROOT));
    }

    public boolean isPluginMessageSourceAllowed(String serverName) {
        return serverName != null && (config.getPluginMessageAllowedSourceServers().isEmpty()
                || config.getPluginMessageAllowedSourceServers().contains(serverName.toLowerCase(java.util.Locale.ROOT)));
    }

    private static String currentServerName(ProxiedPlayer player) {
        return player.getServer() != null ? player.getServer().getInfo().getName() : null;
    }

    private synchronized int getEffectivePlayerCount(ServerInfo info) {
        int pending = 0;
        for (Reservation reservation : reservations.values()) {
            if (reservation.serverName.equalsIgnoreCase(info.getName())) pending++;
        }
        return info.getPlayers().size() + pending;
    }

    private synchronized void purgeExpiredReservations() {
        long cutoff = System.currentTimeMillis() - RESERVATION_TIMEOUT_MS;
        Iterator<Map.Entry<UUID, Reservation>> iterator = reservations.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().createdAt < cutoff) iterator.remove();
        }
    }

    private static final class Reservation {
        private final String serverName;
        private final long createdAt;

        private Reservation(String serverName, long createdAt) {
            this.serverName = serverName;
            this.createdAt = createdAt;
        }
    }
}
