package si.terona.flowgate.service;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import si.terona.flowgate.config.PluginConfig;
import si.terona.flowgate.model.ConnectionState;
import si.terona.flowgate.model.PlayerSession;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerSessionService {

    private final ConcurrentHashMap<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();
    private volatile PluginConfig config;

    public PlayerSessionService(PluginConfig config) {
        this.config = config;
    }

    // Session access

    public PlayerSession getOrCreate(ProxiedPlayer player) {
        return sessions.computeIfAbsent(
                player.getUniqueId(),
                id -> new PlayerSession(id, player.getName())
        );
    }

    public PlayerSession get(UUID playerId) {
        return sessions.get(playerId);
    }

    public void remove(UUID playerId) {
        sessions.remove(playerId);
    }

    // Retry control

    /**
     * Returns true if enough time has elapsed since the player's last routing attempt.
     * No hard retry cap — players remain eligible once hubs recover from an outage.
     */
    public boolean canRetry(UUID playerId) {
        PlayerSession session = sessions.get(playerId);
        if (session == null) return true;
        return System.currentTimeMillis() - session.getLastRouteAttempt() >= config.getRetryCooldownMs();
    }

    public void recordRoutingAttempt(UUID playerId) {
        PlayerSession session = sessions.get(playerId);
        if (session != null) {
            session.incrementRetry();
            session.setLastRouteAttempt(System.currentTimeMillis());
        }
    }

    public void reload(PluginConfig config) {
        this.config = config;
    }

    // Lifecycle

    public void clearAll() {
        sessions.clear();
    }

    public int getActiveSessions() {
        return sessions.size();
    }

    public void markLimbo(ProxiedPlayer player) {
        getOrCreate(player).setState(ConnectionState.LIMBO);
    }

    public void markConnected(UUID playerId, String serverName) {
        PlayerSession session = sessions.get(playerId);
        if (session != null) {
            session.clearFailedServers();
            session.resetRetry();
            session.setState(ConnectionState.CONNECTED);
            session.recordSuccessfulServer(serverName);
        }
    }
}
