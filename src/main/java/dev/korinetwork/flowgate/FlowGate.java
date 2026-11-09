package dev.korinetwork.flowgate;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import dev.korinetwork.flowgate.command.HubCommand;
import dev.korinetwork.flowgate.command.SlashServerCommand;
import dev.korinetwork.flowgate.command.TFBCommand;
import dev.korinetwork.flowgate.config.ConfigBundle;
import dev.korinetwork.flowgate.config.PluginConfig;
import dev.korinetwork.flowgate.config.YamlConfig;
import dev.korinetwork.flowgate.listener.FallbackListener;
import dev.korinetwork.flowgate.listener.LimboRouterListener;
import dev.korinetwork.flowgate.listener.MotdListener;
import dev.korinetwork.flowgate.listener.PluginMessageListener;
import dev.korinetwork.flowgate.listener.ServerConnectBlockerListener;
import dev.korinetwork.flowgate.model.ConnectionState;
import dev.korinetwork.flowgate.model.PlayerSession;
import dev.korinetwork.flowgate.model.RoutingResult;
import dev.korinetwork.flowgate.service.HealthMonitorService;
import dev.korinetwork.flowgate.service.MetricsService;
import dev.korinetwork.flowgate.usage.FlowGateUsage;
import dev.korinetwork.flowgate.usage.KoriUsageStats;
import dev.korinetwork.flowgate.service.PlayerSessionService;
import dev.korinetwork.flowgate.service.RouterService;
import dev.korinetwork.flowgate.title.TitleManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** BungeeCord-family entry point (BungeeCord, Waterfall, FlameCord and other BungeeCord API forks). */
public final class FlowGate extends Plugin {

    private static FlowGate instance;

    // Configuration
    private volatile YamlConfig config;
    private volatile YamlConfig messages;
    private volatile YamlConfig motd;
    private volatile PluginConfig pluginConfig;

    // Services
    private MetricsService       metricsService;
    private KoriUsageStats       usageStats;
    private HealthMonitorService healthMonitorService;
    private RouterService        routerService;
    private PlayerSessionService playerSessionService;
    private PluginMessageListener pluginMessageListener;

    // Runtime
    private volatile boolean shuttingDown = false;
    private final Set<UUID> searchingPlayers = ConcurrentHashMap.newKeySet();
    private final List<Command> registeredSlashCommands = new ArrayList<>();
    private final List<Command> registeredHubCommands   = new ArrayList<>();
    private final Map<String, String> slashServers = new ConcurrentHashMap<>();
    private ScheduledTask fallbackTask;

    // Enable / Disable

    @Override
    public void onEnable() {
        instance = this;
        shuttingDown = false;
        if (!startup()) {
            abort();
        }
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        teardown();
        instance = null;
        getLogger().info("[FlowGate-Lite] Disabled.");
    }

    // Startup / Shutdown internals

    private ConfigBundle loadBundle() {
        return ConfigBundle.load(getDataFolder().toPath(), this::getResourceAsStream,
                name -> getProxy().getServerInfo(name) != null, getLogger());
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

        BungeePlatform platform = new BungeePlatform(getProxy());
        metricsService       = new MetricsService();
        healthMonitorService = new HealthMonitorService(getLogger(), pluginConfig, metricsService, platform);
        routerService        = new RouterService(healthMonitorService, pluginConfig, platform);
        playerSessionService = new PlayerSessionService(pluginConfig);

        getProxy().registerChannel("BungeeCord");
        getProxy().getPluginManager().registerListener(this, new FallbackListener(this));
        getProxy().getPluginManager().registerListener(this, new ServerConnectBlockerListener(this));
        pluginMessageListener = new PluginMessageListener(this);
        getProxy().getPluginManager().registerListener(this, pluginMessageListener);
        getProxy().getPluginManager().registerListener(this, new LimboRouterListener(this));
        getProxy().getPluginManager().registerListener(this, new MotdListener(this));

        getProxy().getPluginManager().registerCommand(this, new TFBCommand(this));
        registerHubCommandsInternal();
        registerSlashServersInternal();

        healthMonitorService.start();
        startFallbackScheduler();

        // anonymous daily usage ping to api.kori.my; metrics.enabled: false turns it off (read on every ping)
        usageStats = FlowGateUsage.create(FlowGateUsage.BUNGEECORD, getVersion(), getDataFolder().toPath(), () -> config);
        usageStats.start();

        getLogger().info("[FlowGate-Lite] v" + getVersion() + " enabled on " + getProxy().getName()
                + " " + getProxy().getVersion() + ".");
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
        if (healthMonitorService != null) {
            healthMonitorService.shutdown();
        }
        if (metricsService != null && pluginConfig != null && pluginConfig.isDebugMode()) {
            getLogger().info("[FlowGate-Lite] " + metricsService.getSummary());
        }
        if (playerSessionService != null) {
            playerSessionService.clearAll();
        }
        searchingPlayers.clear();
        pluginMessageListener = null;
        TitleManager.shutdown();
        getProxy().getPluginManager().unregisterListeners(this);
        getProxy().getPluginManager().unregisterCommands(this);
    }

    private void abort() {
        getLogger().severe("[FlowGate-Lite] Startup failed – cleaning up partially initialized state.");
        shuttingDown = true;
        teardown();
        instance = null;
    }

    // Limbo routing scheduler

    private void startFallbackScheduler() {
        if (fallbackTask != null) fallbackTask.cancel();

        int interval = pluginConfig.getPingIntervalSeconds();
        fallbackTask = getProxy().getScheduler().schedule(
                this, this::tickLimboPlayers, interval, interval, TimeUnit.SECONDS
        );
    }

    private void tickLimboPlayers() {
        if (shuttingDown || !pluginConfig.isRoutingEnabled()) return;

        for (UUID uuid : new HashSet<>(searchingPlayers)) {
            ProxiedPlayer player = getProxy().getPlayer(uuid);
            if (player == null || !player.isConnected()) {
                searchingPlayers.remove(uuid);
                playerSessionService.remove(uuid);
                continue;
            }
            if (!isOnFallback(player)) {
                searchingPlayers.remove(uuid);
                TitleManager.clear(player);
                continue;
            }
            if (!playerSessionService.canRetry(uuid)) continue;

            PlayerSession session = playerSessionService.getOrCreate(uuid, player.getName());
            if (!session.tryTransition(ConnectionState.LIMBO, ConnectionState.ROUTING)) continue;

            RoutingResult result = routerService.reserveBestHub(uuid, BungeePlatform.currentServer(player), session);
            if (!result.isSuccess()) {
                session.setState(ConnectionState.LIMBO);
                metricsService.recordRoutingFailure(result.getReason());
                continue;
            }

            ServerInfo target = getProxy().getServerInfo(result.getServerName());
            if (target == null) {
                routerService.releaseReservation(uuid);
                session.setState(ConnectionState.LIMBO);
                continue;
            }

            playerSessionService.recordRoutingAttempt(uuid);
            sendMessage(player, "routing.found");

            player.connect(target, (success, error) -> {
                routerService.releaseReservation(uuid);
                if (success) {
                    searchingPlayers.remove(uuid);
                    playerSessionService.markConnected(uuid, result.getServerName());
                    TitleManager.clear(player);
                    metricsService.recordRoutingSuccess(result.getServerName());
                    debug("Routed " + player.getName() + " -> " + result.getServerName());
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
            getLogger().severe("[FlowGate-Lite] Reload failed: previous configuration kept.");
            return false;
        }
        apply(bundle);
        routerService.reload(pluginConfig);
        playerSessionService.reload(pluginConfig);
        healthMonitorService.reload(pluginConfig);

        getProxy().getPluginManager().unregisterCommands(this);
        registeredSlashCommands.clear();
        registeredHubCommands.clear();
        slashServers.clear();

        getProxy().getPluginManager().registerCommand(this, new TFBCommand(this));
        registerHubCommandsInternal();
        registerSlashServersInternal();

        startFallbackScheduler();

        if (pluginConfig.isTitlesEnabled()) {
            for (ProxiedPlayer player : getProxy().getPlayers()) {
                if (isOnFallback(player)) TitleManager.start(player);
            }
        } else {
            TitleManager.shutdown();
        }

        return true;
    }

    // Command registration

    private void registerHubCommandsInternal() {
        for (Command cmd : registeredHubCommands) getProxy().getPluginManager().unregisterCommand(cmd);
        registeredHubCommands.clear();
        if (!config.contains("hub-commands")) return;

        List<String> names = config.getStringList("hub-commands");
        if (names.size() > 3) getLogger().warning("[FlowGate-Lite] More than 3 hub-commands – extras ignored.");

        for (int i = 0; i < Math.min(3, names.size()); i++) {
            String name = names.get(i);
            if (name == null || name.trim().isEmpty()) continue;
            Command cmd = new HubCommand(name, this);
            getProxy().getPluginManager().registerCommand(this, cmd);
            registeredHubCommands.add(cmd);
            debug("Registered hub command: /" + name);
        }
    }

    private void registerSlashServersInternal() {
        for (Command cmd : registeredSlashCommands) getProxy().getPluginManager().unregisterCommand(cmd);
        registeredSlashCommands.clear();
        slashServers.clear();
        if (!(config.get("slash-servers") instanceof YamlConfig)) return;

        for (String cmdName : config.getSection("slash-servers").getKeys()) {
            String target = config.getString("slash-servers." + cmdName);
            if (cmdName == null || cmdName.isEmpty() || target == null || target.isEmpty()) continue;
            if (getProxy().getServerInfo(target) == null) {
                getLogger().warning("[FlowGate-Lite] Slash-server '/" + cmdName + "' -> '" + target + "' not found – skipped.");
                continue;
            }

            slashServers.put(cmdName, target);
            Command cmd = new SlashServerCommand(cmdName, target, this);
            getProxy().getPluginManager().registerCommand(this, cmd);
            registeredSlashCommands.add(cmd);
            debug("Registered slash-server: /" + cmdName + " -> " + target);
        }
    }

    // Fallback entry

    public void enterFallback(ProxiedPlayer player) {
        if (player == null || !player.isConnected()) return;
        if (!pluginConfig.isFallbackEnabled() || !pluginConfig.isRoutingEnabled()) return;

        ServerInfo fallback = getProxy().getServerInfo(pluginConfig.getFallbackServer());
        if (fallback == null) return;

        if (player.getServer() == null ||
                !player.getServer().getInfo().getName().equalsIgnoreCase(fallback.getName())) {
            player.connect(fallback);
        }

        if (searchingPlayers.add(player.getUniqueId())) {
            playerSessionService.markLimbo(player.getUniqueId(), player.getName());
            if (pluginConfig.isTitlesEnabled()) TitleManager.start(player);
            sendMessage(player, "routing.searching");
        }
    }

    // Messaging

    public void sendMessage(ProxiedPlayer player, String path) {
        if (messages == null || player == null) return;
        String prefix = messages.getString("prefix", "");
        String msg    = messages.getString(path, null);
        if (msg == null || msg.isEmpty()) return;
        String text = ChatColor.translateAlternateColorCodes('&', prefix + " " + msg);
        player.sendMessage(new TextComponent(text));
    }

    public void sendFallbackSearching(ProxiedPlayer player) {
        sendMessage(player, "routing.searching");
    }

    public void debug(String message) {
        if (pluginConfig != null && pluginConfig.isDebugMode()) {
            getLogger().info("[FlowGate-Lite] " + message);
        }
    }

    // Helpers

    public boolean isOnFallback(ProxiedPlayer player) {
        if (player == null || player.getServer() == null) return false;
        String fallback = pluginConfig != null
                ? pluginConfig.getFallbackServer()
                : config.getString("fallback.fallback-server", "limbo-wait");
        return player.getServer().getInfo().getName().equalsIgnoreCase(fallback);
    }

    public boolean isShuttingDown() { return shuttingDown; }

    public String getVersion() { return getDescription().getVersion(); }

    public void clearPluginMessageRateLimit(UUID playerId) {
        if (pluginMessageListener != null) pluginMessageListener.removeRateLimit(playerId);
    }

    // Getters

    public static FlowGate get()                          { return instance; }
    public PluginConfig        getPluginConfig()           { return pluginConfig; }
    public HealthMonitorService getHealthMonitorService()  { return healthMonitorService; }
    public RouterService        getRouterService()         { return routerService; }
    public PlayerSessionService getPlayerSessionService()  { return playerSessionService; }
    public MetricsService       getMetricsService()        { return metricsService; }
    public Set<UUID>            getSearchingPlayers()      { return searchingPlayers; }
    public YamlConfig           getConfig()                { return config; }
    public YamlConfig           getMessages()              { return messages; }
    public YamlConfig           getMotd()                  { return motd; }
}
