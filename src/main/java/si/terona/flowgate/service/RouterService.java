package si.terona.flowgate.service;

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
    private final ProxyPlatform platform;
    private volatile PluginConfig config;
    private final Map<UUID, Reservation> reservations = new HashMap<>();

    private static final long RESERVATION_TIMEOUT_MS = 15_000L;

    public RouterService(HealthMonitorService healthMonitor, PluginConfig config, ProxyPlatform platform) {
        this.healthMonitor = healthMonitor;
        this.platform      = platform;
        this.config        = config;
    }

    // Routing

    public RoutingResult findBestHub(UUID playerId, String current) {
        return findBestHub(playerId, current, null);
    }

    public RoutingResult findBestHub(UUID playerId, String current, PlayerSession session) {
        purgeExpiredReservations();
        if (!config.isRoutingEnabled()) {
            return RoutingResult.failure("Routing is disabled");
        }

        int limit = Math.max(1, config.getHardPlayerLimit() - config.getReservedSlots());

        // Sticky session: re-route to last known-good hub if still within TTL and healthy
        if (config.isStickySessionEnabled() && session != null) {
            String sticky = session.getLastSuccessfulServer();
            if (sticky != null && !sticky.equalsIgnoreCase(current)) {
                long age = System.currentTimeMillis() - session.getStickySessionTime();
                if (age < config.getStickySessionTtlMs()) {
                    ServerHealth health = healthMonitor.getHealth(sticky);
                    if (health != null && health.isAvailable()) {
                        if (isRoutable(playerId, sticky) && getEffectivePlayerCount(sticky) < limit) {
                            return RoutingResult.success(sticky);
                        }
                    }
                }
            }
        }

        // Weighted least-players selection
        String best = null;
        double bestScore = Double.MAX_VALUE;

        for (String hubName : config.getHubServers()) {
            if (hubName.equalsIgnoreCase(current)) continue;

            ServerHealth health = healthMonitor.getHealth(hubName);
            if (health == null || !health.isAvailable()) continue;

            if (!isRoutable(playerId, hubName)) continue;

            int count = getEffectivePlayerCount(hubName);
            if (count >= limit) continue;

            // Lower score = preferred. Weight divides the load, so heavier servers score lower.
            double score = (double) count / config.getServerWeight(hubName);
            if (score < bestScore) {
                bestScore = score;
                best = hubName;
            }
        }

        return best != null
                ? RoutingResult.success(best)
                : RoutingResult.failure("No available hubs (all offline, quarantined, or full)");
    }

    /**
     * Selects and reserves a hub slot for a pending connection. The reservation is
     * released when the player connects, disconnects, or the connection fails.
     */
    public synchronized RoutingResult reserveBestHub(UUID playerId, String current, PlayerSession session) {
        RoutingResult result = findBestHub(playerId, current, session);
        if (result.isSuccess()) {
            reservations.put(playerId, new Reservation(result.getServerName(), System.currentTimeMillis()));
        }
        return result;
    }

    public synchronized boolean reserveHub(UUID playerId, String serverName) {
        purgeExpiredReservations();
        if (!isHub(serverName) || !healthMonitor.isHealthy(serverName)) return false;

        int limit = Math.max(1, config.getHardPlayerLimit() - config.getReservedSlots());
        if (!isRoutable(playerId, serverName) || getEffectivePlayerCount(serverName) >= limit) return false;

        reservations.put(playerId, new Reservation(serverName, System.currentTimeMillis()));
        return true;
    }

    public synchronized void releaseReservation(UUID playerId) {
        reservations.remove(playerId);
    }

    public void reload(PluginConfig config) {
        this.config = config;
    }

    public RoutingResult findFallback(UUID playerId, String current, String failedServer) {
        if (!config.isFallbackEnabled()) {
            return RoutingResult.failure("Fallback is disabled");
        }

        String best = null;
        double bestScore = Double.MAX_VALUE;
        int limit = Math.max(1, config.getHardPlayerLimit() - config.getReservedSlots());

        for (String hubName : config.getHubServers()) {
            if (hubName.equalsIgnoreCase(current)) continue;
            if (failedServer != null && hubName.equalsIgnoreCase(failedServer)) continue;

            ServerHealth health = healthMonitor.getHealth(hubName);
            if (health == null || !health.isAvailable()) continue;

            if (!isRoutable(playerId, hubName)) continue;

            int count = getEffectivePlayerCount(hubName);
            if (count >= limit) continue;

            double score = (double) count / config.getServerWeight(hubName);
            if (score < bestScore) {
                bestScore = score;
                best = hubName;
            }
        }

        if (best != null) return RoutingResult.success(best);
        if (failedServer != null && isFallback(failedServer)) return RoutingResult.failure("Fallback server rejected the connection");
        return routeToLimbo(playerId, current);
    }

    /** Selects a failover target and reserves capacity when it is a hub. */
    public synchronized RoutingResult reserveFallback(UUID playerId, String current, String failedServer) {
        purgeExpiredReservations();
        RoutingResult result = findFallback(playerId, current, failedServer);
        if (result.isSuccess() && isHub(result.getServerName())) {
            reservations.put(playerId,
                    new Reservation(result.getServerName(), System.currentTimeMillis()));
        }
        return result;
    }

    public RoutingResult routeToLimbo(UUID playerId, String current) {
        String name = config.getFallbackServer();
        if (name == null || name.isEmpty()) {
            return RoutingResult.failure("No fallback server configured");
        }
        if (!isRoutable(playerId, name)) {
            return RoutingResult.failure("Fallback server '" + name + "' not found in proxy");
        }
        if (name.equalsIgnoreCase(current)) {
            return RoutingResult.failure("Player is already on the fallback server");
        }
        return RoutingResult.success(name);
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

    private boolean isRoutable(UUID playerId, String serverName) {
        return platform.hasServer(serverName) && platform.canAccess(playerId, serverName);
    }

    private synchronized int getEffectivePlayerCount(String serverName) {
        int pending = 0;
        for (Reservation reservation : reservations.values()) {
            if (reservation.serverName.equalsIgnoreCase(serverName)) pending++;
        }
        return platform.playerCount(serverName) + pending;
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
