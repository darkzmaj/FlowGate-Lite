package si.terona.flowgate.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoadingTest {

    private static YamlConfig yaml(String text) {
        return YamlConfig.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void gettersFollowBungeeCordSemantics() {
        YamlConfig cfg = yaml("a:\n  b: 3\n  list: [x, 1, y]\nflag: true\n");
        assertEquals(3, cfg.getInt("a.b", 0));
        assertEquals(3L, cfg.getLong("a.b", 0));
        assertEquals(List.of("x", "y"), cfg.getStringList("a.list"));
        assertTrue(cfg.getBoolean("flag", false));
        assertEquals("", cfg.getString("missing"));
        assertInstanceOf(YamlConfig.class, cfg.get("a"));
        assertEquals(java.util.Set.of("b", "list"), java.util.Set.copyOf(cfg.getSection("a").getKeys()));
    }

    @Test void mergeAddsMissingDefaultsWithoutOverwriting(@TempDir Path dir) throws Exception {
        YamlConfig target = yaml("routing:\n  enabled: false\n");
        YamlConfig defaults = yaml("routing:\n  enabled: true\n  retry-cooldown-ms: 5000\nnew-key: v\n");
        assertTrue(ConfigBundle.mergeMissingDefaults(target, defaults));
        assertFalse(target.getBoolean("routing.enabled", true));
        assertEquals(5000, target.getInt("routing.retry-cooldown-ms", 0));
        target.save(dir.resolve("out.yml"));
        assertEquals("v", YamlConfig.load(dir.resolve("out.yml")).getString("new-key"));
        assertFalse(ConfigBundle.mergeMissingDefaults(target, defaults));
    }

    @Test void deletedExampleEntriesInUserSectionsDoNotComeBack() {
        YamlConfig target = yaml("slash-servers:\n  survival: survival\n");
        YamlConfig defaults = yaml("slash-servers:\n  anarchy: anarchy-server\nserver-names:\n  servername1: Hub 1\n");
        assertTrue(ConfigBundle.mergeMissingDefaults(target, defaults)); // missing server-names section is still added
        assertEquals(java.util.Set.of("survival"), java.util.Set.copyOf(target.getSection("slash-servers").getKeys()));
    }

    @Test void bundledDefaultsLoadAndUnknownServersAreFatal(@TempDir Path dir) {
        Logger log = Logger.getLogger("test");
        ConfigBundle ok = ConfigBundle.load(dir, name -> getClass().getClassLoader().getResourceAsStream(name), n -> true, log);
        assertNotNull(ok);
        assertTrue(Files.exists(dir.resolve("motd.yml")));
        assertEquals(List.of("servername1", "servername2", "servername3"), ok.pluginConfig.getHubServers());
        assertNull(ConfigBundle.load(dir, name -> getClass().getClassLoader().getResourceAsStream(name), n -> false, log));
    }
}
