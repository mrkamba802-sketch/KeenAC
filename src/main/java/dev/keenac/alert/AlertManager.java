package dev.keenac.alert;

import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class AlertManager {
    private final KeenAC plugin;
    private final Set<UUID> enabled = new HashSet<>();

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
        if (!plugin.getConfig().getBoolean("alerts.enabled", true)) return;

        String prefix = plugin.getConfig().getString("alerts.prefix", "&bKeenAC &8»");
        String format = plugin.getConfig().getString(
                "alerts.format", "{prefix} &f{player} &7failed &c{check} &8(x{vl})");
        String message = format
                .replace("{prefix}", prefix)
                .replace("{player}", suspect.getName())
                .replace("{check}", checkName)
                .replace("{vl}", Integer.toString(violationLevel));
        message = ChatColor.translateAlternateColorCodes('&', message);

        if (plugin.getConfig().getBoolean("alerts.console", true)) {
            Bukkit.getConsoleSender().sendMessage(message);
        }
        String permission = plugin.getConfig().getString(
                "commands.alerts-permission", "keenac.alerts");
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            if (enabled.contains(recipient.getUniqueId())
                    && recipient.hasPermission(permission)) {
                recipient.sendMessage(message);
            }
        }
    }

    public void clear(Player player) { enabled.remove(player.getUniqueId()); }
    public void clearAll() { enabled.clear(); }
}
