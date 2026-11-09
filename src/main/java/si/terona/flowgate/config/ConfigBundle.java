package si.terona.flowgate.config;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;

/** config.yml, messages.yml and motd.yml loaded and validated together, shared by every proxy platform. */
public final class ConfigBundle {

    public final YamlConfig config;
    public final YamlConfig messages;
    public final YamlConfig motd;
    public final PluginConfig pluginConfig;

    private ConfigBundle(YamlConfig config, YamlConfig messages, YamlConfig motd) {
        this.config = config;
        this.messages = messages;
        this.motd = motd;
        this.pluginConfig = new PluginConfig(config);
    }

    /**
     * Loads all files (copying defaults and merging new default keys), then validates them.
     * Returns null after logging the reason when anything is invalid, so callers keep their current state.
     */
    public static ConfigBundle load(Path dataFolder, Function<String, InputStream> resources,
                                    Predicate<String> serverExists, Logger logger) {
        YamlConfig config, messages, motd;
        try {
            Files.createDirectories(dataFolder);
            config = loadFile(dataFolder, "config.yml", resources);
            messages = loadFile(dataFolder, "messages.yml", resources);
            motd = loadFile(dataFolder, "motd.yml", resources);
        } catch (Exception e) {
            logger.severe("[FlowGate-Lite] Failed to load config files: " + e.getMessage());
            return null;
        }

        boolean fatal = false;
        for (String err : ConfigValidator.validate(config, serverExists)) {
            if (err.endsWith("(will be skipped)")) {
                logger.warning("[FlowGate-Lite] Config warning: " + err);
            } else {
                logger.severe("[FlowGate-Lite] Config error: " + err);
                fatal = true;
            }
        }
        if (fatal || !validateMessages(messages, logger) || !validateMotd(motd, logger)) return null;
        return new ConfigBundle(config, messages, motd);
    }

    private static YamlConfig loadFile(Path dataFolder, String name, Function<String, InputStream> resources) throws Exception {
        Path file = dataFolder.resolve(name);
        if (!Files.exists(file)) {
            try (InputStream in = resources.apply(name)) {
                if (in != null) Files.copy(in, file);
            }
        }
        YamlConfig loaded = YamlConfig.load(file);
        try (InputStream in = resources.apply(name)) {
            if (in != null && mergeMissingDefaults(loaded, YamlConfig.load(in))) loaded.save(file);
        }
        return loaded;
    }

    // Sections whose entries belong to the admin; default examples must not reappear after deletion.
    private static final Set<String> USER_SECTIONS = Set.of("slash-servers", "server-weights", "server-names", "motds");

    static boolean mergeMissingDefaults(YamlConfig target, YamlConfig defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys()) {
            Object defaultValue = defaults.get(key);
            Object currentValue = target.get(key);
            if (currentValue == null) {
                target.set(key, defaultValue);
                changed = true;
            } else if (defaultValue instanceof YamlConfig && currentValue instanceof YamlConfig
                    && !USER_SECTIONS.contains(key)) {
                changed |= mergeMissingDefaults((YamlConfig) currentValue, (YamlConfig) defaultValue);
            }
        }
        return changed;
    }

    private static boolean validateMessages(YamlConfig messages, Logger logger) {
        for (String key : List.of("commands.reload", "commands.usage", "commands.unknown",
                "titles.waiting.title", "titles.waiting.animation")) {
            if (!messages.contains(key)) {
                logger.severe("[FlowGate-Lite] messages.yml missing key: " + key);
                return false;
            }
        }
        return true;
    }

    private static boolean validateMotd(YamlConfig motd, Logger logger) {
        if (!motd.contains("motd")) { logger.severe("[FlowGate-Lite] motd.yml missing 'motd' key"); return false; }
        String active = motd.getString("motd");
        if (!motd.contains("motds." + active)) { logger.severe("[FlowGate-Lite] motd.yml: active profile '" + active + "' not found"); return false; }
        if (!motd.contains("motds." + active + ".lines")) { logger.severe("[FlowGate-Lite] motd.yml: missing 'lines' for profile '" + active + "'"); return false; }
        return true;
    }
}
