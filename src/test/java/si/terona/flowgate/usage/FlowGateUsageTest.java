package si.terona.flowgate.usage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import si.terona.flowgate.config.YamlConfig;

class FlowGateUsageTest {

    private static YamlConfig yaml(String text) {
        return YamlConfig.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void eachProxyFamilyReportsItsOwnPlatformAsAProxy(@TempDir Path dir) {
        KoriUsageStats velocity = FlowGateUsage.create(FlowGateUsage.VELOCITY, "1.0.8", dir, () -> null);
        KoriUsageStats bungee = FlowGateUsage.create(FlowGateUsage.BUNGEECORD, "1.0.8", dir, () -> null);
        assertEquals("https://api.kori.my/flowgate-lite/v1/ping", velocity.endpoint().toString());
        assertEquals("{\"id\":\"x\",\"version\":\"1.0.8\",\"platform\":\"velocity\",\"side\":\"proxy\"}", velocity.payload("x"));
        assertEquals("{\"id\":\"x\",\"version\":\"1.0.8\",\"platform\":\"bungeecord\",\"side\":\"proxy\"}", bungee.payload("x"));
    }

    @Test
    void metricsEnabledIsReadFromTheCurrentConfigBeforeEachPing(@TempDir Path dir) {
        AtomicReference<YamlConfig> config = new AtomicReference<>(yaml("metrics:\n  enabled: false\n"));
        FlowGateUsage.create(FlowGateUsage.VELOCITY, "1.0.8", dir, config::get).ping();
        assertFalse(Files.exists(dir.resolve(".usage-id")), "no id is created while stats are off");

        config.set(null); // config not loaded yet: no ping either
        FlowGateUsage.create(FlowGateUsage.VELOCITY, "1.0.8", dir, config::get).ping();
        assertFalse(Files.exists(dir.resolve(".usage-id")));
    }

    @Test
    void idLivesInTheGivenDataFolder(@TempDir Path dir) throws Exception {
        String id = KoriUsageStats.persistentId(FlowGateUsage.SLUG, dir);
        assertTrue(id.matches("[0-9a-f]{32}"));
        assertTrue(Files.exists(dir.resolve(".usage-id")));
    }

    @Test
    void bothPluginDescriptorsCarryTheBuildVersion() throws Exception {
        String version = System.getProperty("flowgate.version");
        assertNotNull(version, "set by build.gradle");
        try (InputStream bungee = getClass().getClassLoader().getResourceAsStream("bungee.yml");
             InputStream velocity = getClass().getClassLoader().getResourceAsStream("velocity-plugin.json")) {
            assertEquals(version, YamlConfig.load(bungee).getString("version"));
            String json = new String(velocity.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"version\": \"" + version + "\""), json);
            assertTrue(json.contains("\"main\": \"si.terona.flowgate.velocity.FlowGateVelocity\""), json);
        }
    }
}
