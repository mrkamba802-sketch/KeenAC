package dev.keenac.command;

import dev.keenac.KeenAC;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public final class KeenACCommand implements CommandExecutor, TabCompleter {
    private final KeenAC plugin;

    public KeenACCommand(KeenAC plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            sender.sendMessage(color("&bKeenAC &8» &fИспользование: &e/keenac <alerts|reload>"));
            return true;
        }

        if (args[0].equalsIgnoreCase("alerts")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(color("&bKeenAC &8» &cЭта команда доступна только игроку."));
                return true;
            }
            boolean enabled = plugin.getAlertManager().toggle(player);
            sender.sendMessage(color("&bKeenAC &8» &fАлерты: "
                    + (enabled ? "&aвключены" : "&cвыключены")));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("keenac.reload")) {
                sender.sendMessage(color("&bKeenAC &8» &cНедостаточно прав."));
                return true;
            }
            plugin.reloadConfig();
            sender.sendMessage(color("&bKeenAC &8» &aКонфигурация перезагружена."));
            plugin.getLogger().info(sender.getName() + " reloaded the configuration.");
            return true;
        }

        sender.sendMessage(color("&bKeenAC &8» &fИспользование: &e/keenac <alerts|reload>"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("alerts", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
