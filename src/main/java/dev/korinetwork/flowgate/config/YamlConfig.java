package dev.korinetwork.flowgate.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Platform-neutral YAML section with BungeeCord-style dotted-path getters.
 * Uses the SnakeYAML that BungeeCord, its forks and Velocity all ship at runtime.
 */
public final class YamlConfig {

    private final Map<String, Object> values;

    public YamlConfig() { this(new LinkedHashMap<>()); }

    private YamlConfig(Map<String, Object> values) { this.values = values; }

    public static YamlConfig load(InputStream in) {
        Object root = new Yaml().load(new InputStreamReader(in, StandardCharsets.UTF_8));
        if (root != null && !(root instanceof Map)) throw new IllegalArgumentException("YAML root must be a mapping");
        return new YamlConfig(normalize((Map<?, ?>) root));
    }

    public static YamlConfig load(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) { return load(in); }
    }

    public void save(Path file) throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new Yaml(options).dump(values, out);
        }
    }

    // Maps become nested LinkedHashMaps with String keys so sections behave like BungeeCord's.
    private static Map<String, Object> normalize(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw == null) return result;
        raw.forEach((k, v) -> result.put(String.valueOf(k), v instanceof Map ? normalize((Map<?, ?>) v) : v));
        return result;
    }

    @SuppressWarnings("unchecked")
    public Object get(String path) {
        Object current = values;
        for (String part : path.split("\\.", -1)) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(part);
        }
        return current instanceof Map ? new YamlConfig((Map<String, Object>) current) : current;
    }

    @SuppressWarnings("unchecked")
    public void set(String path, Object value) {
        String[] parts = path.split("\\.", -1);
        Map<String, Object> current = values;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                current.put(parts[i], next);
            }
            current = (Map<String, Object>) next;
        }
        current.put(parts[parts.length - 1], value instanceof YamlConfig ? ((YamlConfig) value).values : value);
    }

    public boolean contains(String path)            { return get(path) != null; }
    public Collection<String> getKeys()             { return Set.copyOf(values.keySet()); }
    public String getString(String path)            { return getString(path, ""); }

    public YamlConfig getSection(String path) {
        Object value = get(path);
        return value instanceof YamlConfig ? (YamlConfig) value : new YamlConfig();
    }

    public String getString(String path, String def) {
        Object value = get(path);
        return value instanceof String ? (String) value : def;
    }

    public boolean getBoolean(String path, boolean def) {
        Object value = get(path);
        return value instanceof Boolean ? (Boolean) value : def;
    }

    public int getInt(String path, int def) {
        Object value = get(path);
        return value instanceof Number ? ((Number) value).intValue() : def;
    }

    public long getLong(String path, long def) {
        Object value = get(path);
        return value instanceof Number ? ((Number) value).longValue() : def;
    }

    public List<String> getStringList(String path) {
        List<String> result = new ArrayList<>();
        if (get(path) instanceof List) {
            for (Object o : (List<?>) get(path)) if (o instanceof String) result.add((String) o);
        }
        return result;
    }
}
