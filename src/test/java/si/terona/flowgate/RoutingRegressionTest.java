package si.terona.flowgate;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.*;
import net.md_5.bungee.api.event.*;
import net.md_5.bungee.config.Configuration;
import org.junit.jupiter.api.Test;
import si.terona.flowgate.config.PluginConfig;
import si.terona.flowgate.listener.*;
import si.terona.flowgate.service.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoutingRegressionTest {
    @Test void rejectsMessagesFromPreviousBackendAndBothChannelNames() {
        FlowGate plugin = mock(FlowGate.class);
        ProxiedPlayer player = mock(ProxiedPlayer.class);
        Server old = mock(Server.class);
        when(player.getServer()).thenReturn(mock(Server.class));
        PluginMessageListener listener = new PluginMessageListener(plugin);
        for (String tag : List.of("BungeeCord", "bungeecord:main")) {
            PluginMessageEvent event = new PluginMessageEvent(old, player, tag, new byte[0]);
            listener.onPluginMessage(event);
            assertTrue(event.isCancelled());
        }
    }
    @Test void malformedBackendMessageIsCancelled() {
        FlowGate plugin = mock(FlowGate.class);
        ProxiedPlayer player = mock(ProxiedPlayer.class);
        Server source = mock(Server.class);
        when(player.getServer()).thenReturn(source);
        PluginMessageEvent event = new PluginMessageEvent(source, player, "BungeeCord", new byte[]{0,10,1});
        new PluginMessageListener(plugin).onPluginMessage(event);
        assertTrue(event.isCancelled());
    }
    @Test void serverSwitchRemovesLimboStateAfterSuccessfulConnection() {
        FlowGate plugin = mock(FlowGate.class);
        ProxiedPlayer player = mock(ProxiedPlayer.class);
        Server server = mock(Server.class);
        ServerInfo info = mock(ServerInfo.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.getServer()).thenReturn(server);
        when(server.getInfo()).thenReturn(info);
        when(info.getName()).thenReturn("hub");
        Set<UUID> searching = new HashSet<>(Set.of(id));
        when(plugin.getSearchingPlayers()).thenReturn(searching);
        RouterService router = mock(RouterService.class);
        PlayerSessionService sessions = mock(PlayerSessionService.class);
        when(plugin.getRouterService()).thenReturn(router);
        when(plugin.getPlayerSessionService()).thenReturn(sessions);
        new LimboRouterListener(plugin).onServerConnected(new ServerSwitchEvent(player, null));
        assertFalse(searching.contains(id));
        verify(sessions).markConnected(id,"hub");
        verify(router).releaseReservation(id);
    }
    @Test void allowListsUseLocaleIndependentNames() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            Configuration raw = new Configuration();
            raw.set("plugin-messages.allowed-source-servers", List.of("MINIGAME"));
            PluginConfig cfg = new PluginConfig(raw);
            assertTrue(cfg.getPluginMessageAllowedSourceServers().contains("minigame"));
        } finally { Locale.setDefault(before); }
    }

    @Test void repeatedBackendRejectionsAreBoundedUntilSuccessfulConnection() {
        si.terona.flowgate.model.PlayerSession session = new si.terona.flowgate.model.PlayerSession(UUID.randomUUID(), "tester");
        assertTrue(session.recordFailedServer("Hub"));
        assertTrue(session.recordFailedServer("limbo"));
        assertFalse(session.recordFailedServer("HUB"));
        session.clearFailedServers();
        assertTrue(session.recordFailedServer("hub"));
    }
    @Test void concurrentPluginMessagesCannotExceedThePerPlayerWindow() throws Exception {
        PluginMessageListener listener = new PluginMessageListener(mock(FlowGate.class));
        java.lang.reflect.Method rate = PluginMessageListener.class.getDeclaredMethod("isRateLimited", UUID.class);
        rate.setAccessible(true);
        UUID id = UUID.randomUUID();
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.stream.IntStream.range(0, 100).parallel().forEach(i -> {
            try { if (!(boolean) rate.invoke(listener, id)) accepted.incrementAndGet(); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        assertEquals(10, accepted.get());
    }

    @Test void restrictedHubsCannotBeReservedOrSelectedWithoutAccess() {
        Configuration raw = new Configuration(); raw.set("hubs", List.of("hub"));
        PluginConfig cfg = new PluginConfig(raw);
        HealthMonitorService health = mock(HealthMonitorService.class);
        when(health.getHealth("hub")).thenReturn(si.terona.flowgate.model.ServerHealth.online("hub", 1));
        when(health.isHealthy("hub")).thenReturn(true);
        ProxyServer proxy = mock(ProxyServer.class);
        ServerInfo hub = mock(ServerInfo.class);
        when(proxy.getServerInfo("hub")).thenReturn(hub);
        when(hub.getName()).thenReturn("hub");
        when(hub.getPlayers()).thenReturn(List.of());
        ProxiedPlayer player = mock(ProxiedPlayer.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        try (org.mockito.MockedStatic<ProxyServer> statics = mockStatic(ProxyServer.class)) {
            statics.when(ProxyServer::getInstance).thenReturn(proxy);
            RouterService router = new RouterService(health, cfg);
            assertFalse(router.reserveHub(player,"hub"));
            assertFalse(router.findBestHub(player).isSuccess());
            when(hub.canAccess(player)).thenReturn(true);
            assertTrue(router.reserveHub(player,"hub"));
        }
    }

    @Test void aFullHubStillRoutesInitialJoinToFallback() {
        FlowGate plugin = mock(FlowGate.class);
        PluginConfig cfg = mock(PluginConfig.class);
        when(cfg.isRoutingEnabled()).thenReturn(true); when(cfg.isFallbackEnabled()).thenReturn(true);
        when(plugin.getPluginConfig()).thenReturn(cfg);
        ProxiedPlayer player = mock(ProxiedPlayer.class);
        UUID id = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id);
        RouterService router = mock(RouterService.class);
        PlayerSessionService sessions = mock(PlayerSessionService.class);
        var session = new si.terona.flowgate.model.PlayerSession(id, "tester");
        when(sessions.getOrCreate(player)).thenReturn(session);
        when(router.reserveBestHub(player, session)).thenReturn(si.terona.flowgate.model.RoutingResult.failure("full"));
        when(router.routeToLimbo(player)).thenReturn(si.terona.flowgate.model.RoutingResult.success("limbo"));
        when(plugin.getRouterService()).thenReturn(router); when(plugin.getPlayerSessionService()).thenReturn(sessions);
        when(plugin.getSearchingPlayers()).thenReturn(new HashSet<>());
        ProxyServer proxy = mock(ProxyServer.class); when(plugin.getProxy()).thenReturn(proxy);
        ServerInfo fallback = mock(ServerInfo.class); when(proxy.getServerInfo("limbo")).thenReturn(fallback);
        ServerConnectEvent event = mock(ServerConnectEvent.class);
        when(event.getPlayer()).thenReturn(player); when(event.getTarget()).thenReturn(mock(ServerInfo.class));
        when(event.getReason()).thenReturn(ServerConnectEvent.Reason.JOIN_PROXY);
        new ServerConnectBlockerListener(plugin).onServerConnect(event);
        verify(event).setTarget(fallback);
        assertTrue(plugin.getSearchingPlayers().contains(id));
    }
}
