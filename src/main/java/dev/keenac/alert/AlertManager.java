package dev.keenac.alert;

import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
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
        if (enabled.remove(id)) {
            return false;
        }
        enabled.add(id);
        return true;
    }

    public void send(String checkName, Player suspect, int violationLevel) {
        String format = plugin.getConfig().getString(
                "alerts.format",
                "&bKeenAC &8» &f%s &7failed &e%s &8(VL: %d)");
        String message = ChatColor.translateAlternateColorCodes('&',
                String.format(format, suspect.getName(), checkName, violationLevel));

        Bukkit.getConsoleSender().sendMessage(message);
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            if (enabled.contains(recipient.getUniqueId())) {
                recipient.sendMessage(message);
            }
        }
    }

    public void clear(Player player) {
        enabled.remove(player.getUniqueId());
    }

    public void clearAll() {
        enabled.clear();
    }
}
