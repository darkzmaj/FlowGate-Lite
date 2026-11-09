package dev.korinetwork.flowgate.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class MetricsService {

    private final AtomicLong totalRoutingAttempts = new AtomicLong(0);
    private final AtomicLong successfulRoutes     = new AtomicLong(0);
    private final AtomicLong failedRoutes         = new AtomicLong(0);
    private final AtomicLong fallbackInvocations  = new AtomicLong(0);
    private final AtomicLong pingFailures         = new AtomicLong(0);
    private final AtomicLong quarantineEvents     = new AtomicLong(0);
    private final AtomicLong reconnectAttempts    = new AtomicLong(0);

    private final ConcurrentHashMap<String, AtomicLong> serverRoutingCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> serverPingFailures  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> serverQuarantines   = new ConcurrentHashMap<>();

    public void recordRoutingSuccess(String server) {
        totalRoutingAttempts.incrementAndGet();
        successfulRoutes.incrementAndGet();
        counter(serverRoutingCounts, server).incrementAndGet();
    }

    public void recordRoutingFailure(String reason) {
        totalRoutingAttempts.incrementAndGet();
        failedRoutes.incrementAndGet();
    }

    public void recordFallback()              { fallbackInvocations.incrementAndGet(); }
    public void recordReconnect()             { reconnectAttempts.incrementAndGet(); }
    public void recordQuarantine(String srv)  { quarantineEvents.incrementAndGet(); counter(serverQuarantines, srv).incrementAndGet(); }
    public void recordPingFailure(String srv) { pingFailures.incrementAndGet(); counter(serverPingFailures, srv).incrementAndGet(); }

    public String getSummary() {
        StringBuilder sb = new StringBuilder("FlowGate-Lite Metrics\n");
        sb.append("  Routing attempts  : ").append(totalRoutingAttempts).append("\n");
        sb.append("  Successful routes : ").append(successfulRoutes).append("\n");
        sb.append("  Failed routes     : ").append(failedRoutes).append("\n");
        sb.append("  Fallback triggers : ").append(fallbackInvocations).append("\n");
        sb.append("  Ping failures     : ").append(pingFailures).append("\n");
        sb.append("  Quarantine events : ").append(quarantineEvents).append("\n");
        sb.append("  Reconnects        : ").append(reconnectAttempts);

        if (!serverRoutingCounts.isEmpty()) {
            sb.append("\n  Routes per server:");
            serverRoutingCounts.forEach((s, c) -> sb.append("\n    ").append(s).append(": ").append(c));
        }
        if (!serverPingFailures.isEmpty()) {
            sb.append("\n  Ping failures per server:");
            serverPingFailures.forEach((s, c) -> sb.append("\n    ").append(s).append(": ").append(c));
        }
        return sb.toString();
    }

    private static AtomicLong counter(ConcurrentHashMap<String, AtomicLong> map, String key) {
        return map.computeIfAbsent(key, k -> new AtomicLong(0));
    }

    public long getTotalRoutingAttempts() { return totalRoutingAttempts.get(); }
    public long getSuccessfulRoutes()     { return successfulRoutes.get(); }
    public long getFailedRoutes()         { return failedRoutes.get(); }
    public long getFallbackInvocations()  { return fallbackInvocations.get(); }
    public long getPingFailures()         { return pingFailures.get(); }
    public long getQuarantineEvents()     { return quarantineEvents.get(); }
    public long getReconnectAttempts()    { return reconnectAttempts.get(); }
}
