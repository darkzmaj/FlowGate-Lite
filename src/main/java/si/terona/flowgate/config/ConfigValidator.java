package si.terona.flowgate.config;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.config.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates the plugin configuration at startup and reload time.
 * Returns a list of human-readable error strings; an empty list means the config is valid.
 */
public final class ConfigValidator {

    private ConfigValidator() {}

    /**
     * Validates the given {@link Configuration} against the proxy's registered server list.
     *
     * @param config the loaded plugin configuration
     * @param proxy  the proxy server instance
     * @return a list of validation error messages. Empty if config is valid.
     */
    public static List<String> validate(Configuration config, ProxyServer proxy) {
        List<String> errors = new ArrayList<>();

        // Hubs
        if (!config.contains("hubs") || config.getStringList("hubs").isEmpty()) {
            errors.add("'hubs' list is missing or empty");
        } else {
            for (String hub : config.getStringList("hubs")) {
                if (hub == null || hub.trim().isEmpty()) {
                    errors.add("'hubs' contains a blank entry");
                } else if (proxy.getServerInfo(hub) == null) {
                    errors.add("Hub server '" + hub + "' is not registered in the proxy config");
                }
            }
        }

        // Fallback server
        if (!config.contains("fallback.fallback-server")) {
            errors.add("'fallback.fallback-server' is missing");
        } else {
            String fallback = config.getString("fallback.fallback-server", "");
            if (fallback.isEmpty()) {
                errors.add("'fallback.fallback-server' must not be empty");
            } else if (proxy.getServerInfo(fallback) == null) {
                errors.add("Fallback server '" + fallback + "' is not registered in the proxy config");
            }
        }

        // Required booleans
        validateBoolean(config, "routing.enabled", errors);
        validateBoolean(config, "fallback.enabled", errors);
        validateBoolean(config, "titles.enabled", errors);

        if (config.contains("debug.enabled")) {
            validateBoolean(config, "debug.enabled", errors);
        }

        // Numeric ranges
        if (config.contains("check-interval-seconds")) {
            int interval = config.getInt("check-interval-seconds", -1);
            if (interval < 1) {
                errors.add("'check-interval-seconds' must be >= 1, got: " + interval);
            }
        }

        if (config.contains("players.hub-max-players")) {
            int max = config.getInt("players.hub-max-players", -1);
            if (max < 1) {
                errors.add("'players.hub-max-players' must be >= 1, got: " + max);
            }
        }

        // Hub command limit
        if (config.contains("hub-commands")) {
            int count = config.getStringList("hub-commands").size();
            if (count > 3) {
                errors.add("'hub-commands' allows at most 3 entries, found: " + count);
            }
        }

        // Slash servers: validate referenced servers exist
        if (config.contains("slash-servers")) {
            if (!(config.get("slash-servers") instanceof Configuration)) {
                errors.add("'slash-servers' must be a section");
            } else {
            for (String cmd : config.getSection("slash-servers").getKeys()) {
                String target = config.getString("slash-servers." + cmd, "");
                if (target.isEmpty()) {
                    errors.add("slash-server '/" + cmd + "' has an empty target server");
                } else if (proxy.getServerInfo(target) == null) {
                    errors.add("slash-server '/" + cmd + "' → server '" + target
                            + "' is not registered in the proxy config (will be skipped)");
                }
            }
            }
        }

        if (config.contains("server-weights") && !(config.get("server-weights") instanceof Configuration)) {
            errors.add("'server-weights' must be a section");
        }
        validateStringList(config, "plugin-messages.allowed-connect-servers", errors);
        validateStringList(config, "plugin-messages.allowed-source-servers", errors);

        return errors;
    }

    private static void validateBoolean(Configuration config, String key, List<String> errors) {
        if (config.contains(key) && !(config.get(key) instanceof Boolean)) {
            errors.add("'" + key + "' must be true or false, got: " + config.get(key));
        }
    }

    private static void validateStringList(Configuration config, String key, List<String> errors) {
        if (config.contains(key) && !(config.get(key) instanceof List)) {
            errors.add("'" + key + "' must be a list of server names");
        }
    }
}

