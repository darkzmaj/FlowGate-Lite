package dev.korinetwork.flowgate.model;

/**
 * Immutable snapshot of a backend server's health state.
 * Updated exclusively by HealthMonitorService.
 */
public final class ServerHealth {

    private final String serverName;
    private final boolean online;
    private final long latencyMs;
    private final long lastChecked;
    private final boolean quarantined;
    private final long quarantineExpiry;
    private final int consecutiveFailures;

    private ServerHealth(
            String serverName,
            boolean online,
            long latencyMs,
            long lastChecked,
            boolean quarantined,
            long quarantineExpiry,
            int consecutiveFailures
    ) {
        this.serverName = serverName;
        this.online = online;
        this.latencyMs = latencyMs;
        this.lastChecked = lastChecked;
        this.quarantined = quarantined;
        this.quarantineExpiry = quarantineExpiry;
        this.consecutiveFailures = consecutiveFailures;
    }

    // Factories

    public static ServerHealth online(String serverName, long latencyMs) {
        return new ServerHealth(serverName, true, latencyMs,
                System.currentTimeMillis(), false, 0L, 0);
    }

    public static ServerHealth offline(String serverName, int consecutiveFailures) {
        return new ServerHealth(serverName, false, -1L,
                System.currentTimeMillis(), false, 0L, consecutiveFailures);
    }

    public static ServerHealth quarantined(String serverName, int consecutiveFailures, long quarantineDurationMs) {
        long expiry = System.currentTimeMillis() + quarantineDurationMs;
        return new ServerHealth(serverName, false, -1L,
                System.currentTimeMillis(), true, expiry, consecutiveFailures);
    }

    // Computed checks

    /** True when the server is online and not currently quarantined. */
    public boolean isAvailable() {
        return online && !quarantined;
    }

    /** True when the quarantine window has elapsed and the server can be retried. */
    public boolean isQuarantineExpired() {
        return quarantined && System.currentTimeMillis() > quarantineExpiry;
    }

    // Accessors

    public String getServerName()        { return serverName; }
    public boolean isOnline()            { return online; }
    public long getLatencyMs()           { return latencyMs; }
    public long getLastChecked()         { return lastChecked; }
    public boolean isQuarantined()       { return quarantined; }
    public long getQuarantineExpiry()    { return quarantineExpiry; }
    public int getConsecutiveFailures()  { return consecutiveFailures; }

    @Override
    public String toString() {
        return "ServerHealth{server='" + serverName + "', online=" + online
                + ", latencyMs=" + latencyMs + ", quarantined=" + quarantined
                + ", failures=" + consecutiveFailures + '}';
    }
}
