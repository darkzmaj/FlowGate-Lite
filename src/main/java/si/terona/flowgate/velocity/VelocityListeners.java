package si.terona.flowgate.velocity;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.LegacyChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import si.terona.flowgate.config.YamlConfig;
import si.terona.flowgate.model.ConnectionState;
import si.terona.flowgate.model.PlayerSession;
import si.terona.flowgate.model.RoutingResult;
import si.terona.flowgate.service.MessageRateLimiter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Velocity counterparts of the BungeeCord listeners: join routing, kick fallback, limbo tracking, MOTD, Connect messages. */
public final class VelocityListeners {

    static final ChannelIdentifier MODERN_BUNGEE = MinecraftChannelIdentifier.from("bungeecord:main");
    static final ChannelIdentifier LEGACY_BUNGEE = new LegacyChannelIdentifier("BungeeCord");

    // Server-list sample names are plain strings; encode colours the way vanilla clients render them.
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR).hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private final FlowGateVelocity plugin;
    private final MessageRateLimiter rateLimiter = new MessageRateLimiter(10);

    VelocityListeners(FlowGateVelocity plugin) { this.plugin = plugin; }

    /** Equivalent of BungeeCord's JOIN_PROXY routing: pick the best hub, or limbo when none is free. */
    @Subscribe(priority = Short.MIN_VALUE + 1) // last, like BungeeCord HIGHEST
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        if (plugin.isShuttingDown() || !plugin.getPluginConfig().isRoutingEnabled()) return;
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        PlayerSession session = plugin.getPlayerSessionService().getOrCreate(id, player.getUsername());

        RoutingResult result = plugin.getRouterService().reserveBestHub(id, null, session);
        if (result.isSuccess()) {
            plugin.getProxy().getServer(result.getServerName()).ifPresent(hub -> {
                event.setInitialServer(hub);
                plugin.debug("Initial server -> " + result.getServerName() + " [" + player.getUsername() + "]");
            });
        } else if (plugin.getPluginConfig().isFallbackEnabled()) {
            RoutingResult limbo = plugin.getRouterService().routeToLimbo(id, null);
            if (!limbo.isSuccess()) return;
            plugin.getProxy().getServer(limbo.getServerName()).ifPresent(server -> {
                event.setInitialServer(server);
                plugin.getSearchingPlayers().add(id);
                session.setState(ConnectionState.LIMBO);
                if (plugin.getPluginConfig().isTitlesEnabled()) plugin.getTitles().start(player);
                plugin.debug("Initial server -> limbo [" + player.getUsername() + "]");
            });
        }
    }

    @Subscribe(priority = Short.MIN_VALUE + 1) // last, like BungeeCord HIGHEST
    public void onKickedFromServer(KickedFromServerEvent event) {
        if (plugin.isShuttingDown() || !plugin.getPluginConfig().isFallbackEnabled()) return;
        Player player = event.getPlayer();
        if (plugin.isOnFallback(player)) return;

        String current = VelocityPlatform.currentServer(player);
        // A failed switch keeps the player on their current server, exactly like Velocity's default.
        if (event.kickedDuringServerConnect() && current != null) return;

        String kickedFrom = event.getServer().getServerInfo().getName();
        PlayerSession session = plugin.getPlayerSessionService().getOrCreate(player.getUniqueId(), player.getUsername());
        if (!session.recordFailedServer(kickedFrom)) {
            plugin.debug("Fallback stopped: repeated rejection by the same backend");
            return;
        }
        RoutingResult result = plugin.getRouterService().reserveFallback(player.getUniqueId(), current, kickedFrom);
        if (!result.isSuccess()) {
            plugin.debug("Fallback failed for " + player.getUsername() + ": " + result.getReason());
            return;
        }
        RegisteredServer target = plugin.getProxy().getServer(result.getServerName()).orElse(null);
        if (target == null) {
            plugin.getRouterService().releaseReservation(player.getUniqueId());
            return;
        }

        event.setResult(KickedFromServerEvent.RedirectPlayer.create(target));
        plugin.getMetricsService().recordFallback();
        plugin.debug("Kick-fallback: " + player.getUsername() + " (" + kickedFrom + ") -> " + result.getServerName());

        if (!plugin.getRouterService().isHub(result.getServerName()) && plugin.getSearchingPlayers().add(player.getUniqueId())) {
            session.setState(ConnectionState.LIMBO);
            if (plugin.getPluginConfig().isTitlesEnabled()) plugin.getTitles().start(player);
            plugin.sendKey(player, "routing.searching");
        }
    }

    /** Fires after the player's current server has changed (BungeeCord's ServerSwitchEvent). */
    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        if (plugin.isShuttingDown()) return;
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        plugin.getRouterService().releaseReservation(uuid);

        String current = VelocityPlatform.currentServer(player);
        if (!plugin.isOnFallback(player)) {
            plugin.getSearchingPlayers().remove(uuid);
            if (current != null) plugin.getPlayerSessionService().markConnected(uuid, current);
            plugin.getTitles().clear(player);
            return;
        }
        if (!plugin.getSearchingPlayers().add(uuid)) return;

        plugin.getPlayerSessionService().getOrCreate(uuid, player.getUsername()).setState(ConnectionState.LIMBO);
        if (plugin.getPluginConfig().isTitlesEnabled()) plugin.getTitles().start(player);
        plugin.sendKey(player, "routing.searching");
        plugin.getMetricsService().recordReconnect();
        plugin.debug(player.getUsername() + " entered limbo.");
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (plugin.isShuttingDown()) return;
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        plugin.getSearchingPlayers().remove(uuid);
        plugin.getRouterService().releaseReservation(uuid);
        rateLimiter.remove(uuid);
        plugin.getPlayerSessionService().remove(uuid);
        plugin.getTitles().clear(player);
    }

    @Subscribe
    public void onProxyPing(ProxyPingEvent event) {
        YamlConfig motd = plugin.getMotd();
        if (motd == null) return;
        String base = "motds." + motd.getString("motd", "main");
        if (!motd.contains(base)) return;

        ServerPing.Builder ping = event.getPing().asBuilder();
        List<String> lines = motd.getStringList(base + ".lines");
        if (!lines.isEmpty()) ping.description(FlowGateVelocity.text(String.join("\n", lines)));

        List<String> hover = motd.getStringList(base + ".hover");
        if (motd.getBoolean(base + ".enable-hover", true) && !hover.isEmpty() && event.getPing().getPlayers().isPresent()) {
            List<ServerPing.SamplePlayer> sample = new ArrayList<>();
            for (String line : hover) {
                sample.add(new ServerPing.SamplePlayer(SECTION.serialize(FlowGateVelocity.text(line)), UUID.randomUUID()));
            }
            ping.clearSamplePlayers().samplePlayers(sample);
        }
        event.setPing(ping.build());
    }

    /**
     * Backend "Connect" requests. Velocity answers BungeeCord-channel messages itself while
     * {@code bungee-plugin-message-channel = true}; with it set to false they reach this listener,
     * which applies the same destination/source/rate/capacity policy as the BungeeCord build.
     */
    @Subscribe(priority = Short.MAX_VALUE - 1) // first
    public void onPluginMessage(PluginMessageEvent event) {
        if (plugin.isShuttingDown() || !event.getResult().isAllowed()) return;
        if (!MODERN_BUNGEE.equals(event.getIdentifier()) && !LEGACY_BUNGEE.equals(event.getIdentifier())) return;

        // Never forward BungeeCord-channel messages to clients or let clients forge them for backends.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection) || !(event.getTarget() instanceof Player)) return;

        ServerConnection source = (ServerConnection) event.getSource();
        Player player = (Player) event.getTarget();
        // A delayed message from a previous backend must never control the current connection.
        if (player.getCurrentServer().orElse(null) != source || event.getData().length > 32766) return;

        String subchannel, targetName;
        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
            subchannel = in.readUTF();
            if (!subchannel.equals("Connect")) return;
            targetName = in.readUTF();
        } catch (Exception malformed) {
            return;
        }

        String sourceName = source.getServerInfo().getName();
        RegisteredServer target = plugin.getProxy().getServer(targetName).orElse(null);
        if (target == null || !plugin.getRouterService().isPluginMessageDestinationAllowed(targetName)) {
            plugin.debug("Plugin message blocked: disallowed destination '" + targetName + "'");
        } else if (!plugin.getRouterService().isPluginMessageSourceAllowed(sourceName)) {
            plugin.debug("Plugin message blocked: disallowed source '" + sourceName + "'");
        } else if (rateLimiter.limited(player.getUniqueId())) {
            plugin.debug("Plugin message rate-limited for: " + player.getUsername());
        } else if (plugin.getRouterService().isHub(targetName)
                && !plugin.getRouterService().reserveHub(player.getUniqueId(), targetName)) {
            plugin.debug("Plugin message blocked: hub unavailable or full '" + targetName + "'");
        } else {
            plugin.debug("Plugin message Connect: " + player.getUsername() + " -> " + targetName);
            player.createConnectionRequest(target).fireAndForget();
        }
    }
}
