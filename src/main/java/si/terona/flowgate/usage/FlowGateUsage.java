package si.terona.flowgate.usage;

import si.terona.flowgate.config.YamlConfig;

import java.nio.file.Path;
import java.util.function.Supplier;

/** FlowGate Lite's settings for the KORI usage ping, shared by the BungeeCord and Velocity entry points. */
public final class FlowGateUsage {

    public static final String SLUG = "flowgate-lite";
    public static final String BUNGEECORD = "bungeecord";
    public static final String VELOCITY = "velocity";

    private FlowGateUsage() {}

    /**
     * @param platform   {@link #BUNGEECORD} or {@link #VELOCITY}
     * @param dataFolder the plugin's data folder; keeps the random {@code .usage-id}
     * @param config     the current config.yml, read before every ping ({@code metrics.enabled}, default true)
     */
    public static KoriUsageStats create(String platform, String version, Path dataFolder, Supplier<YamlConfig> config) {
        return new KoriUsageStats(SLUG, version, platform, "proxy",
                () -> KoriUsageStats.persistentId(SLUG, dataFolder),
                () -> {
                    YamlConfig c = config.get();
                    return c != null && c.getBoolean("metrics.enabled", true);
                });
    }
}
