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
}
