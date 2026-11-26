package dev.keenac.alert;

import dev.keenac.KeenAC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AlertManager {
    private static final String CHECK_MARKER = "\u0001KEENAC_CHECK\u0001";

    private final KeenAC plugin;
    private final Set<UUID> enabled = new HashSet<>();
    private final Map<AlertKey, Integer> alertLevels = new ConcurrentHashMap<>();
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();

    public AlertManager(KeenAC plugin) {
        this.plugin = plugin;
    }

    public boolean toggle(Player player) {
        UUID id = player.getUniqueId();
        if (enabled.remove(id)) return false;
        enabled.add(id);
        return true;
    }

    public void enableForStaff(Player player) {
        if (plugin.getConfig().getBoolean("alerts.auto-enable-for-staff", true)
                && player.hasPermission(plugin.getConfig().getString(
                        "commands.alerts-permission", "keenac.alerts"))) {
            enabled.add(player.getUniqueId());
        }
    }

    public boolean isEnabled(Player player) {
        return enabled.contains(player.getUniqueId());
    }

    public void send(String checkName, Player suspect, int violationLevel) {
        send(checkName, suspect, violationLevel, "Confirmed check violation.");
    }

    public void send(String checkName, Player suspect, int violationLevel, String reason) {
        if (!plugin.getConfig().getBoolean("alerts.enabled", true)) return;

        // Display the count of alerts actually emitted for this player/check.
        // Check-local counters may decay or advance while a cooldown suppresses
        // delivery, so they must not control the visible xN sequence.
        AlertKey key = new AlertKey(suspect.getUniqueId(), checkName);
        int alertLevel = alertLevels.merge(key, 1, (current, one) ->
                current == Integer.MAX_VALUE ? Integer.MAX_VALUE : current + 1);

        String prefix = plugin.getConfig().getString("alerts.prefix", "&bKeenAC &8»");
        String format = plugin.getConfig().getString(
                "alerts.format", "{prefix} &f{player} &7failed &c{check} &8(x{vl})");
        String safeReason = reason == null || reason.isBlank()
                ? "No additional reason was recorded." : reason;
        int ping = suspect.getPing();
        double[] recentTps = Bukkit.getTPS();
        double tps = recentTps.length == 0 ? 20.0 : recentTps[0];
        String tpsText = Double.isFinite(tps)
                ? String.format(Locale.ROOT, "%.2f", tps) : "unknown";

        String templated = format
                .replace("{prefix}", prefix)
                .replace("{player}", suspect.getName())
                .replace("{check}", CHECK_MARKER)
                .replace("{vl}", Integer.toString(alertLevel))
                .replace("{reason}", safeReason)
                .replace("{ping}", Integer.toString(ping))
                .replace("{tps}", tpsText);

        String plainMessage = templated.replace(CHECK_MARKER, checkName);
        if (plugin.getConfig().getBoolean("alerts.console", true)) {
            Bukkit.getConsoleSender().sendMessage(
                    ChatColor.translateAlternateColorCodes('&', plainMessage));
        }

        String permission = plugin.getConfig().getString(
                "commands.alerts-permission", "keenac.alerts");
        int markerIndex = templated.indexOf(CHECK_MARKER);
        Component message;
        if (markerIndex >= 0) {
            String before = templated.substring(0, markerIndex);
            String after = templated.substring(markerIndex + CHECK_MARKER.length());
            Component details = legacy.deserialize(
                    "&bПроверка: &f" + checkName
                            + "\n&bИгрок: &f" + suspect.getName()
                            + "\n&bПричина: &f" + safeReason
                            + "\n&bПинг: &f" + ping + " ms"
                            + "\n&bTPS: &f" + tpsText
                            + "\n&bУровень: &fх" + alertLevel);
            Component check = legacy.deserialize("&c" + checkName)
                    .hoverEvent(HoverEvent.showText(details));
            message = legacy.deserialize(before)
                    .append(check)
                    .append(legacy.deserialize(after));
        } else {
            message = legacy.deserialize(plainMessage);
        }

        for (Player recipient : Bukkit.getOnlinePlayers()) {
            if (enabled.contains(recipient.getUniqueId())
                    && recipient.hasPermission(permission)) {
                recipient.sendMessage(message);
            }
        }
    }

    public void clear(Player player) {
        UUID id = player.getUniqueId();
        enabled.remove(id);
        alertLevels.keySet().removeIf(key -> key.playerId().equals(id));
    }

    public void clearAll() {
        enabled.clear();
        alertLevels.clear();
    }

    private record AlertKey(UUID playerId, String checkName) { }
}
