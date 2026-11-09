package si.terona.flowgate.velocity;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import si.terona.flowgate.config.PluginConfig;
import si.terona.flowgate.config.YamlConfig;
import si.terona.flowgate.model.PlayerSession;
import si.terona.flowgate.model.RoutingResult;
import si.terona.flowgate.service.MetricsService;
import si.terona.flowgate.service.PlayerSessionService;
import si.terona.flowgate.service.RouterService;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VelocityRoutingTest {

    private final UUID id = UUID.randomUUID();
    private final FlowGateVelocity plugin = mock(FlowGateVelocity.class);
    private final ProxyServer proxy = mock(ProxyServer.class);
    private final RouterService router = mock(RouterService.class);
    private final PlayerSessionService sessions = mock(PlayerSessionService.class);
    private final Player player = mock(Player.class);
    private final PlayerSession session = new PlayerSession(id, "tester");
    private final Set<UUID> searching = new HashSet<>();

    @BeforeEach
    void wire() {
        PluginConfig cfg = mock(PluginConfig.class);
        when(cfg.isRoutingEnabled()).thenReturn(true);
        when(cfg.isFallbackEnabled()).thenReturn(true);
        when(plugin.getPluginConfig()).thenReturn(cfg);
        when(plugin.getProxy()).thenReturn(proxy);
        when(plugin.getRouterService()).thenReturn(router);
        when(plugin.getPlayerSessionService()).thenReturn(sessions);
        when(plugin.getMetricsService()).thenReturn(new MetricsService());
        when(plugin.getSearchingPlayers()).thenReturn(searching);
        when(plugin.getTitles()).thenReturn(mock(VelocityTitles.class));
        when(player.getUniqueId()).thenReturn(id);
        when(player.getUsername()).thenReturn("tester");
        when(sessions.getOrCreate(id, "tester")).thenReturn(session);
    }

    private RegisteredServer server(String name) {
        RegisteredServer server = mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(new ServerInfo(name, InetSocketAddress.createUnresolved("localhost", 25566)));
        when(proxy.getServer(name)).thenReturn(Optional.of(server));
        return server;
    }

    private ServerConnection onServer(String name) {
        ServerConnection connection = mock(ServerConnection.class);
        RegisteredServer server = server(name);
        ServerInfo info = server.getServerInfo();
        when(connection.getServerInfo()).thenReturn(info);
        when(connection.getServer()).thenReturn(server);
        when(player.getCurrentServer()).thenReturn(Optional.of(connection));
        return connection;
    }

    private static byte[] connect(String target) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Connect");
        out.writeUTF(target);
        return out.toByteArray();
    }

    @Test void clientForgedBungeeMessagesAreDroppedAndNeverRouted() {
        PluginMessageEvent event = new PluginMessageEvent(player, mock(ServerConnection.class),
                VelocityListeners.LEGACY_BUNGEE, connect("hub"));
        new VelocityListeners(plugin).onPluginMessage(event);
        assertFalse(event.getResult().isAllowed());
        verify(player, never()).createConnectionRequest(any());
    }

    @Test void messagesFromAPreviousBackendCannotMoveThePlayer() {
        onServer("hub");
        PluginMessageEvent event = new PluginMessageEvent(mock(ServerConnection.class), player,
                VelocityListeners.MODERN_BUNGEE, connect("hub"));
        new VelocityListeners(plugin).onPluginMessage(event);
        assertFalse(event.getResult().isAllowed());
        verify(player, never()).createConnectionRequest(any());
    }

    @Test void allowedConnectMessageReservesAndConnects() {
        ServerConnection source = onServer("lobby");
        RegisteredServer hub = server("hub");
        when(router.isPluginMessageDestinationAllowed("hub")).thenReturn(true);
        when(router.isPluginMessageSourceAllowed("lobby")).thenReturn(true);
        when(router.isHub("hub")).thenReturn(true);
        when(router.reserveHub(id, "hub")).thenReturn(true);
        ConnectionRequestBuilder request = mock(ConnectionRequestBuilder.class);
        when(player.createConnectionRequest(hub)).thenReturn(request);

        PluginMessageEvent event = new PluginMessageEvent(source, player, VelocityListeners.MODERN_BUNGEE, connect("hub"));
        new VelocityListeners(plugin).onPluginMessage(event);

        assertFalse(event.getResult().isAllowed());
        verify(request).fireAndForget();
    }

    @Test void failedSwitchKeepsPlayerOnCurrentServer() {
        onServer("hub");
        KickedFromServerEvent.ServerKickResult original = KickedFromServerEvent.Notify.create(Component.empty());
        KickedFromServerEvent event = new KickedFromServerEvent(player, server("minigame"), Component.empty(), true, original);
        new VelocityListeners(plugin).onKickedFromServer(event);
        assertSame(original, event.getResult());
        verifyNoInteractions(router);
    }

    @Test void kickFromHubRedirectsToAnotherHub() {
        onServer("hub1");
        RegisteredServer hub2 = server("hub2");
        when(router.reserveFallback(id, "hub1", "hub1")).thenReturn(RoutingResult.success("hub2"));
        when(router.isHub("hub2")).thenReturn(true);
        KickedFromServerEvent event = new KickedFromServerEvent(player, server("hub1"), Component.empty(), false,
                KickedFromServerEvent.DisconnectPlayer.create(Component.empty()));
        new VelocityListeners(plugin).onKickedFromServer(event);
        KickedFromServerEvent.RedirectPlayer redirect = assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, event.getResult());
        assertSame(hub2, redirect.getServer());
    }

    @Test void fullHubsSendInitialJoinToLimbo() {
        RegisteredServer limbo = server("limbo");
        when(router.reserveBestHub(id, null, session)).thenReturn(RoutingResult.failure("full"));
        when(router.routeToLimbo(id, null)).thenReturn(RoutingResult.success("limbo"));
        PlayerChooseInitialServerEvent event = new PlayerChooseInitialServerEvent(player, server("hub"));
        new VelocityListeners(plugin).onChooseInitialServer(event);
        assertSame(limbo, event.getInitialServer().orElseThrow());
        assertTrue(searching.contains(id));
    }

    @Test void legacyAndHexColoursAreParsed() {
        Component text = FlowGateVelocity.text("&#ff0000Red &aGreen");
        assertEquals("Red Green", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(text));
        assertNotNull(new YamlConfig());
    }

    @Test void detectsWhenVelocityAnswersBungeeMessagesItself(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path toml = dir.resolve("velocity.toml");
        assertFalse(FlowGateVelocity.velocityHandlesBungeeChannel(toml), "missing file");
        java.nio.file.Files.writeString(toml, "[advanced]\n  bungee-plugin-message-channel = true\n");
        assertTrue(FlowGateVelocity.velocityHandlesBungeeChannel(toml));
        java.nio.file.Files.writeString(toml, "bungee-plugin-message-channel = false\n# bungee-plugin-message-channel = true\n");
        assertFalse(FlowGateVelocity.velocityHandlesBungeeChannel(toml));
    }
}
