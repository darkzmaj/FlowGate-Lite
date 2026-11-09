package dev.korinetwork.flowgate.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import dev.korinetwork.flowgate.config.ConfigBundle;
import dev.korinetwork.flowgate.config.PluginConfig;
import dev.korinetwork.flowgate.config.YamlConfig;
import dev.korinetwork.flowgate.model.ConnectionState;
import dev.korinetwork.flowgate.model.PlayerSession;
import dev.korinetwork.flowgate.model.RoutingResult;
import dev.korinetwork.flowgate.service.HealthMonitorService;
import dev.korinetwork.flowgate.service.MetricsService;
import dev.korinetwork.flowgate.service.PlayerSessionService;
import dev.korinetwork.flowgate.service.RouterService;
import dev.korinetwork.flowgate.usage.FlowGateUsage;
import dev.korinetwork.flowgate.usage.KoriUsageStats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Velocity entry point (Velocity 3.x/4.x and forks that keep the Velocity plugin API). */
public final class FlowGateVelocity {

    private static final Pattern BUNGEE_CHANNEL_ON = Pattern.compile("(?m)^\\s*bungee-plugin-message-channel\\s*=\\s*true\\b");

    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();

    private final ProxyServer proxy;
    private final Path dataFolder;
    private final Logger logger = Logger.getLogger("FlowGate-Lite");

    private volatile YamlConfig config;
    private volatile YamlConfig messages;
    private volatile YamlConfig motd;
    private volatile PluginConfig pluginConfig;

    private MetricsService metricsService;
    private KoriUsageStats usageStats;
    private HealthMonitorService healthMonitorService;
    private RouterService routerService;
    private PlayerSessionService playerSessionService;
    private VelocityListeners listeners;
    private final VelocityTitles titles = new VelocityTitles(this);

    private volatile boolean shuttingDown = false;
    private final Set<UUID> searchingPlayers = ConcurrentHashMap.newKeySet();
    private final List<CommandMeta> registeredCommands = new ArrayList<>();
    private ScheduledTask fallbackTask;

    @Inject
    public FlowGateVelocity(ProxyServer proxy, @DataDirectory Path dataFolder) {
        this.proxy = proxy;
        this.dataFolder = dataFolder;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        shuttingDown = false;
        if (!startup()) {
            logger.severe("[FlowGate-Lite] Startup failed – cleaning up partially initialized state.");
            shuttingDown = true;
            teardown();
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        shuttingDown = true;
        teardown();
        logger.info("[FlowGate-Lite] Disabled.");
    }

    // Startup / shutdown

    private ConfigBundle loadBundle() {
        return ConfigBundle.load(dataFolder, name -> FlowGateVelocity.class.getClassLoader().getResourceAsStream(name),
                name -> proxy.getServer(name).isPresent(), logger);
    }

    private void apply(ConfigBundle bundle) {
        config = bundle.config;
        messages = bundle.messages;
        motd = bundle.motd;
        pluginConfig = bundle.pluginConfig;
    }

    private boolean startup() {
        ConfigBundle bundle = loadBundle();
        if (bundle == null) return false;
        apply(bundle);

        VelocityPlatform platform = new VelocityPlatform(proxy);
        metricsService = new MetricsService();
        healthMonitorService = new HealthMonitorService(logger, pluginConfig, metricsService, platform);
        routerService = new RouterService(healthMonitorService, pluginConfig, platform);
        playerSessionService = new PlayerSessionService(pluginConfig);

        proxy.getChannelRegistrar().register(VelocityListeners.MODERN_BUNGEE, VelocityListeners.LEGACY_BUNGEE);
        listeners = new VelocityListeners(this);
        proxy.getEventManager().register(this, listeners);
        registerCommands();

        healthMonitorService.start();
        startFallbackScheduler();

        // anonymous daily usage ping to api.kori.my; metrics.enabled: false turns it off (read on every ping)
        usageStats = FlowGateUsage.create(FlowGateUsage.VELOCITY, getVersion(), dataFolder, () -> config);
        usageStats.start();

        if (velocityHandlesBungeeChannel(Path.of("velocity.toml"))) {
            logger.info("[FlowGate-Lite] velocity.toml has bungee-plugin-message-channel = true: Velocity answers backend "
                    + "Connect messages itself and FlowGate's plugin-messages policy does not apply. Set it to false to use it.");
        }
        logger.info("[FlowGate-Lite] v" + getVersion() + " enabled on " + proxy.getVersion().getName()
                + " " + proxy.getVersion().getVersion() + ".");
        return true;
    }

    private void teardown() {
        if (usageStats != null) {
            usageStats.stop();
            usageStats = null;
        }
        if (fallbackTask != null) {
            fallbackTask.cancel();
            fallbackTask = null;
        }
        if (healthMonitorService != null) healthMonitorService.shutdown();
        if (metricsService != null && pluginConfig != null && pluginConfig.isDebugMode()) {
            logger.info("[FlowGate-Lite] " + metricsService.getSummary());
        }
        if (playerSessionService != null) playerSessionService.clearAll();
        searchingPlayers.clear();
        titles.shutdown();
        unregisterCommands();
        if (listeners != null) {
            proxy.getEventManager().unregisterListener(this, listeners);
            proxy.getChannelRegistrar().unregister(VelocityListeners.MODERN_BUNGEE, VelocityListeners.LEGACY_BUNGEE);
            listeners = null;
        }
    }

    // Limbo routing scheduler

    private void startFallbackScheduler() {
        if (fallbackTask != null) fallbackTask.cancel();
        long interval = pluginConfig.getPingIntervalSeconds();
        fallbackTask = proxy.getScheduler().buildTask(this, this::tickLimboPlayers)
                .delay(interval, TimeUnit.SECONDS).repeat(interval, TimeUnit.SECONDS).schedule();
    }

    private void tickLimboPlayers() {
        if (shuttingDown || !pluginConfig.isRoutingEnabled()) return;

        for (UUID uuid : new HashSet<>(searchingPlayers)) {
            Player player = proxy.getPlayer(uuid).orElse(null);
            if (player == null || !player.isActive()) {
                searchingPlayers.remove(uuid);
                playerSessionService.remove(uuid);
                continue;
            }
            if (!isOnFallback(player)) {
                searchingPlayers.remove(uuid);
                titles.clear(player);
                continue;
            }
            if (!playerSessionService.canRetry(uuid)) continue;

            PlayerSession session = playerSessionService.getOrCreate(uuid, player.getUsername());
            if (!session.tryTransition(ConnectionState.LIMBO, ConnectionState.ROUTING)) continue;

            RoutingResult result = routerService.reserveBestHub(uuid, VelocityPlatform.currentServer(player), session);
            if (!result.isSuccess()) {
                session.setState(ConnectionState.LIMBO);
                metricsService.recordRoutingFailure(result.getReason());
                continue;
            }
            RegisteredServer target = proxy.getServer(result.getServerName()).orElse(null);
            if (target == null) {
                routerService.releaseReservation(uuid);
                session.setState(ConnectionState.LIMBO);
                continue;
            }

            playerSessionService.recordRoutingAttempt(uuid);
            sendKey(player, "routing.found");

            player.createConnectionRequest(target).connect().whenComplete((r, error) -> {
                routerService.releaseReservation(uuid);
                if (r != null && r.isSuccessful()) {
                    searchingPlayers.remove(uuid);
                    playerSessionService.markConnected(uuid, result.getServerName());
                    titles.clear(player);
                    metricsService.recordRoutingSuccess(result.getServerName());
                    debug("Routed " + player.getUsername() + " -> " + result.getServerName());
                } else {
                    session.setState(ConnectionState.LIMBO);
                    metricsService.recordRoutingFailure("connect callback false");
                }
            });
        }
    }

    // Reload

    public synchronized boolean reloadAll() {
        ConfigBundle bundle = loadBundle();
        if (bundle == null) {
            logger.severe("[FlowGate-Lite] Reload failed: previous configuration kept.");
            return false;
        }
        apply(bundle);
        routerService.reload(pluginConfig);
        playerSessionService.reload(pluginConfig);
        healthMonitorService.reload(pluginConfig);
        unregisterCommands();
        registerCommands();
        startFallbackScheduler();

        if (pluginConfig.isTitlesEnabled()) {
            for (Player player : proxy.getAllPlayers()) {
                if (isOnFallback(player)) titles.start(player);
            }
        } else {
            titles.shutdown();
        }
        return true;
    }

    // Commands

    private void registerCommands() {
        register("flowgate", new VelocityCommands.Admin(this));

        List<String> hubs = config.getStringList("hub-commands");
        if (hubs.size() > 3) logger.warning("[FlowGate-Lite] More than 3 hub-commands – extras ignored.");
        for (String name : hubs.subList(0, Math.min(3, hubs.size()))) {
            if (name == null || name.trim().isEmpty()) continue;
            register(name, new VelocityCommands.Hub(this));
            debug("Registered hub command: /" + name);
        }

        if (!(config.get("slash-servers") instanceof YamlConfig)) return;
        for (String name : config.getSection("slash-servers").getKeys()) {
            String target = config.getString("slash-servers." + name);
            if (name.isEmpty() || target.isEmpty()) continue;
            if (proxy.getServer(target).isEmpty()) {
                logger.warning("[FlowGate-Lite] Slash-server '/" + name + "' -> '" + target + "' not found – skipped.");
                continue;
            }
            register(name, new VelocityCommands.Slash(this, target));
            debug("Registered slash-server: /" + name + " -> " + target);
        }
    }

    private void register(String name, SimpleCommand command) {
        CommandManager manager = proxy.getCommandManager();
        CommandMeta meta = manager.metaBuilder(name).plugin(this).build();
        manager.register(meta, command);
        registeredCommands.add(meta);
    }

    private void unregisterCommands() {
        for (CommandMeta meta : registeredCommands) proxy.getCommandManager().unregister(meta);
        registeredCommands.clear();
    }

    // Messaging

    static Component text(String legacy) {
        return legacy == null || legacy.isEmpty() ? Component.empty() : AMPERSAND.deserialize(legacy);
    }

    void sendKey(CommandSource target, String path) {
        if (messages == null || target == null) return;
        String msg = messages.getString(path, "");
        if (msg.isEmpty()) return;
        target.sendMessage(text(messages.getString("prefix", "") + " " + msg));
    }

    void debug(String message) {
        if (pluginConfig != null && pluginConfig.isDebugMode()) logger.info("[FlowGate-Lite] " + message);
    }

    boolean isOnFallback(Player player) {
        String current = VelocityPlatform.currentServer(player);
        return current != null && pluginConfig != null && current.equalsIgnoreCase(pluginConfig.getFallbackServer());
    }

    /** Velocity reads velocity.toml from its working directory; the API does not expose this setting. */
    static boolean velocityHandlesBungeeChannel(Path toml) {
        try {
            return BUNGEE_CHANNEL_ON.matcher(Files.readString(toml)).find();
        } catch (Exception unreadable) {
            return false;
        }
    }

    String getVersion() {
        return proxy.getPluginManager().fromInstance(this)
                .flatMap(c -> c.getDescription().getVersion()).orElse("unknown");
    }

    // Accessors

    ProxyServer getProxy()                         { return proxy; }
    boolean isShuttingDown()                       { return shuttingDown; }
    VelocityTitles getTitles()                     { return titles; }
    PluginConfig getPluginConfig()                 { return pluginConfig; }
    HealthMonitorService getHealthMonitorService() { return healthMonitorService; }
    RouterService getRouterService()               { return routerService; }
    PlayerSessionService getPlayerSessionService() { return playerSessionService; }
    MetricsService getMetricsService()             { return metricsService; }
    Set<UUID> getSearchingPlayers()                { return searchingPlayers; }
    YamlConfig getConfig()                         { return config; }
    YamlConfig getMessages()                       { return messages; }
    YamlConfig getMotd()                           { return motd; }
}
