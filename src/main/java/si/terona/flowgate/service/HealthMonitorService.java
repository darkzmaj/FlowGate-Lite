package si.terona.flowgate.service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import si.terona.flowgate.config.PluginConfig;
import si.terona.flowgate.model.ServerHealth;

public final class HealthMonitorService {

    private final Logger logger;
    private volatile PluginConfig config;
    private final MetricsService metrics;

    private final ConcurrentHashMap<String, ServerHealth> healthCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> failureCounters = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;
    private final AtomicLong generation = new AtomicLong(0L);

    public HealthMonitorService(Logger logger, PluginConfig config, MetricsService metrics) {
        this.logger  = logger;
        this.config  = config;
        this.metrics = metrics;
    }

    // Lifecycle

    public void start() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FlowGate-Lite-HealthMonitor");
            t.setDaemon(true);
            return t;
        });

        long currentGeneration = generation.incrementAndGet();
        int interval = config.getPingIntervalSeconds();
        // scheduleWithFixedDelay: next run only starts after current completes.
        // Prevents overlapping ping cycles during backend stalls.
        scheduler.scheduleWithFixedDelay(() -> runPingCycle(currentGeneration), 0L, interval, TimeUnit.SECONDS);

        if (config.isDebugMode()) {
            logger.info("[FlowGate-Lite] HealthMonitorService started (interval=" + interval + "s)");
        }
    }

    public void shutdown() {
        generation.incrementAndGet();
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(3L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        healthCache.clear();
        failureCounters.clear();
    }

    public void reload(PluginConfig config) {
        shutdown();
        this.config = config;
        start();
    }

    // Ping cycle

    private void runPingCycle(long cycleGeneration) {
        if (Thread.currentThread().isInterrupted()) return;
        try {
            Set<String> servers = new LinkedHashSet<>(config.getHubServers());
            String fallback = config.getFallbackServer();
            if (fallback != null && !fallback.isEmpty()) servers.add(fallback);

            for (String serverName : servers) {
                if (Thread.currentThread().isInterrupted()) break;

                ServerInfo info = ProxyServer.getInstance().getServerInfo(serverName);
                if (info == null) {
                    healthCache.put(serverName, ServerHealth.offline(serverName, getFailureCount(serverName)));
                    continue;
                }

                ServerHealth current = healthCache.get(serverName);
                if (current != null && current.isQuarantined() && !current.isQuarantineExpired()) {
                    continue;
                }

                final String name = serverName;
                final long start = System.currentTimeMillis();
                info.ping((result, error) -> {
                    if (generation.get() != cycleGeneration) return;
                    if (error != null || result == null) {
                        handleFailure(name);
                    } else {
                        handleSuccess(name, System.currentTimeMillis() - start);
                    }
                });
            }
        } catch (Exception e) {
            logger.warning("[FlowGate-Lite] Health monitor error: " + e.getMessage());
        }
    }

    private void handleSuccess(String serverName, long latencyMs) {
        getOrCreateCounter(serverName).set(0);
        healthCache.put(serverName, ServerHealth.online(serverName, latencyMs));
        if (config.isDebugMode()) {
            logger.info("[FlowGate-Lite] Health OK: " + serverName + " (" + latencyMs + "ms)");
        }
    }

    private void handleFailure(String serverName) {
        int failures = getOrCreateCounter(serverName).incrementAndGet();
        metrics.recordPingFailure(serverName);

        if (failures >= config.getQuarantineThreshold()) {
            getOrCreateCounter(serverName).set(0);
            healthCache.put(serverName,
                    ServerHealth.quarantined(serverName, failures, config.getQuarantineDurationMs()));
            metrics.recordQuarantine(serverName);
            logger.warning("[FlowGate-Lite] Server quarantined: " + serverName
                    + " (failures=" + failures
                    + ", retry in " + (config.getQuarantineDurationMs() / 1000) + "s)");
        } else {
            healthCache.put(serverName, ServerHealth.offline(serverName, failures));
            if (config.isDebugMode()) {
                logger.info("[FlowGate-Lite] Ping failed: " + serverName + " (failures=" + failures + ")");
            }
        }
    }

    // Public API

    public ServerHealth getHealth(String serverName) {
        return healthCache.get(serverName);
    }

    public Map<String, ServerHealth> getAllHealth() {
        return Collections.unmodifiableMap(healthCache);
    }

    public boolean isHealthy(String serverName) {
        ServerHealth h = healthCache.get(serverName);
        return h != null && h.isAvailable();
    }

    // Internals

    private AtomicInteger getOrCreateCounter(String serverName) {
        return failureCounters.computeIfAbsent(serverName, k -> new AtomicInteger(0));
    }

    private int getFailureCount(String serverName) {
        AtomicInteger c = failureCounters.get(serverName);
        return c == null ? 0 : c.get();
    }
}
