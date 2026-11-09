package dev.korinetwork.flowgate.service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Per-player fixed one-second window for backend Connect messages; each window is atomic. */
public final class MessageRateLimiter {

    private final int perSecond;
    private final ConcurrentHashMap<UUID, Window> windows = new ConcurrentHashMap<>();

    public MessageRateLimiter(int perSecond) { this.perSecond = perSecond; }

    public boolean limited(UUID playerId) {
        return windows.computeIfAbsent(playerId, id -> new Window()).limited(perSecond);
    }

    public void remove(UUID playerId) { windows.remove(playerId); }

    private static final class Window {
        private long start = System.nanoTime();
        private int count;

        synchronized boolean limited(int max) {
            long now = System.nanoTime();
            if (now - start >= TimeUnit.SECONDS.toNanos(1)) {
                start = now;
                count = 0;
            }
            return ++count > max;
        }
    }
}
