package dev.korinetwork.flowgate.usage;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * KORI Network plugin usage statistics (template; copy this file into a plugin and change the
 * package). Once a day it tells https://api.kori.my that one more server runs the plugin. It sends
 * an anonymous id (a one-way hash), the plugin version, the platform and whether this is a proxy
 * or a game server. Nothing else: no IPs, server names, player data or keys.
 *
 * <p>Owners turn it off with {@code metrics.enabled: false} in the plugin's config.yml; the
 * {@code enabled} supplier is read before every ping, so a reload takes effect without a restart.
 * Stats can never affect the server: everything runs on one daemon thread and every error is
 * swallowed.
 *
 * <p>Same wire format as NetworkLock 1.0.1's UsageStats:
 * {@code POST https://api.kori.my/<plugin>/v1/ping} with
 * {@code {"id":"…","version":"…","platform":"…","side":"proxy|server"}}.
 */
public final class KoriUsageStats {

    static final String API = "https://api.kori.my/";
    static final long FIRST_DELAY_MINUTES = 5;

    private final String plugin;
    private final URI endpoint;
    private final String version;
    private final String platform;
    private final String side;
    private final Supplier<String> id;
    private final BooleanSupplier enabled;
    private ScheduledExecutorService executor;

    /**
     * @param plugin   the plugin's slug on kori.my, lowercase, e.g. "flowgate-lite"
     * @param version  the plugin version, e.g. from the plugin description
     * @param platform e.g. "bungeecord", "velocity", "paper", "folia", "spigot", "sponge"
     * @param side     "proxy" or "server"
     * @param id       the anonymous id, or null while unknown (no ping then); see {@link #persistentId}
     * @param enabled  read before every ping, normally {@code metrics.enabled} from config.yml
     */
    public KoriUsageStats(String plugin, String version, String platform, String side, Supplier<String> id,
            BooleanSupplier enabled) {
        if (!plugin.matches("[a-z0-9-]{1,40}")) throw new IllegalArgumentException("plugin slug: " + plugin);
        this.plugin = plugin;
        this.endpoint = URI.create(API + plugin + "/v1/ping");
        this.version = version;
        this.platform = platform;
        this.side = side;
        this.id = id;
        this.enabled = enabled;
    }

    /** Pings 5 minutes after start, then once a day. Call from the plugin's enable. */
    public synchronized void start() {
        stop();
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, plugin + "-usage-stats");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::ping, FIRST_DELAY_MINUTES, TimeUnit.DAYS.toMinutes(1), TimeUnit.MINUTES);
    }

    /** Call from the plugin's disable. */
    public synchronized void stop() {
        if (executor != null) executor.shutdownNow();
        executor = null;
    }

    void ping() {
        try {
            if (!enabled.getAsBoolean()) return;
            String serverId = id.get();
            if (serverId == null) return;
            send(payload(serverId).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            // offline, API down, config not loaded: try again tomorrow; never affect the server
        }
    }

    private void send(byte[] body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) endpoint.toURL().openConnection();
        try {
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(false);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", plugin + "/" + safe(version));
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            conn.getResponseCode(); // the response is ignored
        } finally {
            conn.disconnect();
        }
    }

    URI endpoint() {
        return endpoint;
    }

    String payload(String serverId) {
        return "{\"id\":\"" + safe(serverId) + "\",\"version\":\"" + safe(version) + "\",\"platform\":\""
                + safe(platform) + "\",\"side\":\"" + safe(side) + "\"}";
    }

    private static String safe(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z0-9._+-]", "");
    }

    /**
     * A stable anonymous id for this server: a random value kept in {@code <dataFolder>/.usage-id}
     * (created on first use), hashed with the plugin slug. Returns null if the folder cannot be
     * written, so no ping is sent. Plugins with a natural per-install secret may use
     * {@link #anonymousId} on that instead.
     */
    public static String persistentId(String plugin, Path dataFolder) {
        try {
            Path file = dataFolder.resolve(".usage-id");
            String raw = Files.exists(file) ? new String(Files.readAllBytes(file), StandardCharsets.US_ASCII).trim() : "";
            if (!raw.matches("[0-9a-f]{32}")) {
                byte[] r = new byte[16];
                new SecureRandom().nextBytes(r);
                raw = hex(r, r.length);
                Files.createDirectories(dataFolder);
                Files.write(file, raw.getBytes(StandardCharsets.US_ASCII));
            }
            return anonymousId(plugin, raw);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The first 16 bytes of SHA-256 over a fixed label, the plugin slug and the given parts, as hex. */
    public static String anonymousId(String plugin, String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(("KORI/usage-id/v1/" + plugin).getBytes(StandardCharsets.UTF_8));
            for (String p : parts) {
                sha.update((byte) 0);
                sha.update(p.getBytes(StandardCharsets.UTF_8));
            }
            return hex(sha.digest(), 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) sb.append(String.format("%02x", b[i] & 0xff));
        return sb.toString();
    }
}
