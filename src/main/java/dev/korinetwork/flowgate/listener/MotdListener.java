package dev.korinetwork.flowgate.listener;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.event.ProxyPingEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import dev.korinetwork.flowgate.FlowGate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MotdListener implements Listener {

    private final FlowGate plugin;

    private static final Pattern HEX_PATTERN =
            Pattern.compile("&#([A-Fa-f0-9]{6})");

    public MotdListener(FlowGate plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onProxyPing(ProxyPingEvent event) {

        String profile = plugin.getMotd().getString("motd", "main");
        String basePath = "motds." + profile;

        if (!plugin.getMotd().contains(basePath)) return;

        ServerPing ping = event.getResponse();

        List<String> lines = plugin.getMotd().getStringList(basePath + ".lines");
        if (!lines.isEmpty()) {
            ping.setDescriptionComponent(
                    new TextComponent(color(String.join("\n", lines)))
            );
        }

        // Hover (guarded)
        boolean hoverEnabled = plugin.getMotd()
                .getBoolean(basePath + ".enable-hover", true);

        if (!hoverEnabled || ping.getPlayers() == null) return;

        List<String> hover = plugin.getMotd().getStringList(basePath + ".hover");
        if (hover.isEmpty()) return;

        List<ServerPing.PlayerInfo> sample = new ArrayList<>();
        for (String line : hover) {
            sample.add(new ServerPing.PlayerInfo(color(line), UUID.randomUUID()));
        }

        ping.getPlayers().setSample(
                sample.toArray(new ServerPing.PlayerInfo[0])
        );
    }

    private String color(String text) {
        Matcher matcher = HEX_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            matcher.appendReplacement(
                    buffer,
                    ChatColor.of("#" + matcher.group(1)).toString()
            );
        }
        matcher.appendTail(buffer);

        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }
}
