package dev.korinetwork.flowgate.usage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KoriUsageStatsTest {

    @Test
    void endpointAndPayloadMatchTheApi() {
        KoriUsageStats s = new KoriUsageStats("flowgate-lite", "1.0.8", "bungeecord", "proxy", () -> "ab", () -> true);
        assertEquals("https://api.kori.my/flowgate-lite/v1/ping", s.endpoint().toString());
        assertEquals("{\"id\":\"ab\",\"version\":\"1.0.8\",\"platform\":\"bungeecord\",\"side\":\"proxy\"}", s.payload("ab"));
        // nothing outside the safe alphabet reaches the JSON
        assertEquals("{\"id\":\"ab\",\"version\":\"1.0.8\",\"platform\":\"bungeecord\",\"side\":\"proxy\"}", s.payload("a\"b"));
    }

    @Test
    void rejectsBadSlugs() {
        assertThrows(IllegalArgumentException.class, () -> new KoriUsageStats("Flow Gate", "1", "p", "proxy", () -> "x", () -> true));
    }

    @Test
    void persistentIdIsStableHexAndPerPlugin(@TempDir Path dir) throws Exception {
        String a = KoriUsageStats.persistentId("flowgate-lite", dir);
        assertNotNull(a);
        assertTrue(a.matches("[0-9a-f]{32}"));
        assertEquals(a, KoriUsageStats.persistentId("flowgate-lite", dir));
        assertNotEquals(a, KoriUsageStats.persistentId("networklock", dir));
        String raw = new String(Files.readAllBytes(dir.resolve(".usage-id"))).trim();
        assertNotEquals(raw, a, "the stored random value is never sent as is");
    }

    @Test
    void disabledOrUnknownIdSendsNothing() {
        AtomicInteger asked = new AtomicInteger();
        new KoriUsageStats("x", "1", "p", "proxy", () -> { asked.incrementAndGet(); return "id"; }, () -> false).ping();
        assertEquals(0, asked.get(), "the id is not even computed when stats are off");
        new KoriUsageStats("x", "1", "p", "proxy", () -> null, () -> true).ping(); // no exception, no request
    }
}
