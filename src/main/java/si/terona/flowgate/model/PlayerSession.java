package si.terona.flowgate.model;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class PlayerSession {

    private final UUID playerId;
    private final String playerName;
    private final java.util.Set<String> failedServers = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.IDLE);
    private final AtomicInteger retryCount = new AtomicInteger(0);
    private final AtomicLong lastRouteAttempt = new AtomicLong(0L);

    // Sticky session
    private volatile String lastSuccessfulServer;
    private final AtomicLong stickySessionTime = new AtomicLong(0L);

    public PlayerSession(UUID playerId, String playerName) {
        this.playerId = playerId;
        this.playerName = playerName;
    }

    public boolean recordFailedServer(String name) {
        return name == null || failedServers.add(name.toLowerCase(java.util.Locale.ROOT));
    }
    public void clearFailedServers() { failedServers.clear(); }

    // State transitions

    public boolean tryTransition(ConnectionState from, ConnectionState to) {
        return state.compareAndSet(from, to);
    }

    public void setState(ConnectionState newState) {
        state.set(newState);
    }

    public ConnectionState getState() {
        return state.get();
    }

    // Retry tracking

    public int incrementRetry() {
        return retryCount.incrementAndGet();
    }

    public void resetRetry() {
        retryCount.set(0);
        lastRouteAttempt.set(0L);
    }

    public int getRetryCount()          { return retryCount.get(); }
    public long getLastRouteAttempt()   { return lastRouteAttempt.get(); }

    public void setLastRouteAttempt(long timeMs) {
        lastRouteAttempt.set(timeMs);
    }

    // Sticky session

    public void recordSuccessfulServer(String serverName) {
        this.lastSuccessfulServer = serverName;
        this.stickySessionTime.set(System.currentTimeMillis());
    }

    public String getLastSuccessfulServer() { return lastSuccessfulServer; }
    public long getStickySessionTime()      { return stickySessionTime.get(); }

    // Identity

    public UUID getPlayerId()     { return playerId; }
    public String getPlayerName() { return playerName; }
}
