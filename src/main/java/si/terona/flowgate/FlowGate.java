package si.terona.flowgate;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import si.terona.flowgate.command.HubCommand;
import si.terona.flowgate.command.SlashServerCommand;
import si.terona.flowgate.command.TFBCommand;
import si.terona.flowgate.config.ConfigValidator;
import si.terona.flowgate.config.PluginConfig;
import si.terona.flowgate.listener.FallbackListener;
import si.terona.flowgate.listener.LimboRouterListener;
import si.terona.flowgate.listener.MotdListener;
import si.terona.flowgate.listener.PluginMessageListener;
import si.terona.flowgate.listener.ServerConnectBlockerListener;
import si.terona.flowgate.model.ConnectionState;
import si.terona.flowgate.model.PlayerSession;
import si.terona.flowgate.model.RoutingResult;
import si.terona.flowgate.service.HealthMonitorService;
import si.terona.flowgate.service.MetricsService;
import si.terona.flowgate.service.PlayerSessionService;
import si.terona.flowgate.service.RouterService;
import si.terona.flowgate.title.TitleManager;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class FlowGate extends Plugin {

    private static FlowGate instance;

    // Configuration
    private Configuration config;
    private Configuration messages;
    private Configuration motd;
    private PluginConfig pluginConfig;

    // Services
    private MetricsService       metricsService;
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
        if (!startup()) {
            abort();
        }
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        teardown();
        getLogger().info("[FlowGate-Lite] Disabled.");
    }

    // Startup / Shutdown internals

    private boolean startup() {
        if (!loadConfig() || !loadMessages() || !loadMotd()) {
            getLogger().severe("[FlowGate-Lite] Failed to load config files.");
            return false;
        }

        List<String> errors = ConfigValidator.validate(config, getProxy());
        boolean fatal = false;
        for (String err : errors) {
            if (err.startsWith("'hubs'") || err.startsWith("'fallback'")) {
                getLogger().severe("[FlowGate-Lite] Config error: " + err);
                fatal = true;
            } else {
                getLogger().warning("[FlowGate-Lite] Config warning: " + err);
            }
        }
        if (fatal) return false;

        if (!validateMessages()) {
            getLogger().severe("[FlowGate-Lite] Invalid messages.yml");
            return false;
        }
        if (!validateMotd()) {
            getLogger().severe("[FlowGate-Lite] Invalid motd.yml");
            return false;
        }

        pluginConfig = new PluginConfig(config);

        metricsService       = new MetricsService();
        healthMonitorService = new HealthMonitorService(getLogger(), pluginConfig, metricsService);
        routerService        = new RouterService(healthMonitorService, pluginConfig);
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

        getLogger().info("[FlowGate-Lite] v2.0.0 enabled.");
        return true;
    }

    private void teardown() {
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
        teardown();
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

            PlayerSession session = playerSessionService.getOrCreate(player);
            if (!session.tryTransition(ConnectionState.LIMBO, ConnectionState.ROUTING)) continue;

            RoutingResult result = routerService.reserveBestHub(player, session);
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

    public boolean reloadAll() {
        Configuration previousConfig = config;
        Configuration previousMessages = messages;
        Configuration previousMotd = motd;
        if (!loadConfig() || !loadMessages() || !loadMotd()) {
            restoreConfiguration(previousConfig, previousMessages, previousMotd);
            getLogger().severe("[FlowGate-Lite] Reload failed: cannot parse config files.");
            return false;
        }

        List<String> errors = ConfigValidator.validate(config, getProxy());
        for (String err : errors) {
            if (err.startsWith("'hubs'") || err.startsWith("'fallback'")) {
                restoreConfiguration(previousConfig, previousMessages, previousMotd);
                getLogger().severe("[FlowGate-Lite] Reload failed: " + err);
                return false;
            }
            getLogger().warning("[FlowGate-Lite] Config warning: " + err);
        }

        if (!validateMessages() || !validateMotd()) {
            restoreConfiguration(previousConfig, previousMessages, previousMotd);
            getLogger().severe("[FlowGate-Lite] Reload failed: invalid messages.yml or motd.yml.");
            return false;
        }

        PluginConfig reloadedConfig = new PluginConfig(config);
        pluginConfig = reloadedConfig;
        routerService.reload(reloadedConfig);
        playerSessionService.reload(reloadedConfig);
        healthMonitorService.reload(reloadedConfig);

        getProxy().getPluginManager().unregisterCommands(this);
        registeredSlashCommands.clear();
        registeredHubCommands.clear();
        slashServers.clear();

        getProxy().getPluginManager().registerCommand(this, new TFBCommand(this));
        registerHubCommandsInternal();
        registerSlashServersInternal();

        startFallbackScheduler();

        if (pluginConfig.isTitlesEnabled()) {
            String fallbackName = pluginConfig.getFallbackServer();
            for (ProxiedPlayer player : getProxy().getPlayers()) {
                if (player.getServer() != null &&
                        player.getServer().getInfo().getName().equalsIgnoreCase(fallbackName)) {
                    TitleManager.start(player);
                }
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
        if (!(config.get("slash-servers") instanceof Configuration)) return;

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
            playerSessionService.markLimbo(player);
            if (pluginConfig.isTitlesEnabled()) TitleManager.start(player);
            sendMessage(player, "routing.searching");
        }
    }

    // Config loading

    private boolean loadConfig() {
        try {
            ensureDataFolder();
            File file = copyDefaultIfAbsent("config.yml");
            config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(file);
            mergeDefaults(config, "config.yml", file);
            return true;
        } catch (Exception e) {
            getLogger().severe("[FlowGate-Lite] Failed to load config.yml: " + e.getMessage());
            return false;
        }
    }

    private boolean loadMessages() {
        try {
            ensureDataFolder();
            File file = copyDefaultIfAbsent("messages.yml");
            messages = ConfigurationProvider.getProvider(YamlConfiguration.class).load(file);
            mergeDefaults(messages, "messages.yml", file);
            return true;
        } catch (Exception e) {
            getLogger().severe("[FlowGate-Lite] Failed to load messages.yml: " + e.getMessage());
            return false;
        }
    }

    private boolean loadMotd() {
        try {
            ensureDataFolder();
            File file = copyDefaultIfAbsent("motd.yml");
            motd = ConfigurationProvider.getProvider(YamlConfiguration.class).load(file);
            mergeDefaults(motd, "motd.yml", file);
            return true;
        } catch (Exception e) {
            getLogger().severe("[FlowGate-Lite] Failed to load motd.yml: " + e.getMessage());
            return false;
        }
    }

    private void ensureDataFolder() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
    }

    private File copyDefaultIfAbsent(String name) throws Exception {
        File file = new File(getDataFolder(), name);
        if (!file.exists()) {
            try (InputStream in = getResourceAsStream(name)) {
                if (in != null) Files.copy(in, file.toPath());
            }
        }
        return file;
    }

    private void mergeDefaults(Configuration target, String resource, File file) throws Exception {
        try (InputStream in = getResourceAsStream(resource)) {
            if (in == null) return;
            Configuration defaults = ConfigurationProvider.getProvider(YamlConfiguration.class).load(in);
            boolean changed = false;
            changed = mergeMissingDefaults(target, defaults);
            if (changed) ConfigurationProvider.getProvider(YamlConfiguration.class).save(target, file);
        }
    }

    private static boolean mergeMissingDefaults(Configuration target, Configuration defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys()) {
            Object defaultValue = defaults.get(key);
            Object currentValue = target.get(key);
            if (currentValue == null) {
                target.set(key, defaultValue);
                changed = true;
            } else if (defaultValue instanceof Configuration && currentValue instanceof Configuration) {
                changed |= mergeMissingDefaults((Configuration) currentValue, (Configuration) defaultValue);
            }
        }
        return changed;
    }

    private void restoreConfiguration(Configuration previousConfig, Configuration previousMessages, Configuration previousMotd) {
        config = previousConfig;
        messages = previousMessages;
        motd = previousMotd;
    }

    // Validation

    private boolean validateMessages() {
        if (messages == null) return false;
        for (String key : new String[]{
                "commands.reload", "commands.usage", "commands.unknown",
                "titles.waiting.title", "titles.waiting.animation"
        }) {
            if (!messages.contains(key)) {
                getLogger().severe("[FlowGate-Lite] messages.yml missing key: " + key);
                return false;
            }
        }
        return true;
    }

    private boolean validateMotd() {
        if (motd == null) return false;
        if (!motd.contains("motd")) { getLogger().severe("[FlowGate-Lite] motd.yml missing 'motd' key"); return false; }
        String active = motd.getString("motd");
        if (!motd.contains("motds." + active)) { getLogger().severe("[FlowGate-Lite] motd.yml: active profile '" + active + "' not found"); return false; }
        if (!motd.contains("motds." + active + ".lines")) { getLogger().severe("[FlowGate-Lite] motd.yml: missing 'lines' for profile '" + active + "'"); return false; }
        return true;
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
    public Configuration        getConfig()                { return config; }
    public Configuration        getMessages()              { return messages; }
    public Configuration        getMotd()                  { return motd; }
}
