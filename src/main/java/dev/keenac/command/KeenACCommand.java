package dev.keenac.command;

import dev.keenac.KeenAC;
import dev.keenac.context.PlayerContextSnapshot;
import dev.keenac.evidence.EvidenceRecord;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class KeenACCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM HH:mm:ss", Locale.ROOT)
                    .withZone(ZoneId.systemDefault());

    private final KeenAC plugin;

    public KeenACCommand(KeenAC plugin) {
        this.plugin = plugin;
    }

    private String permission(String path, String fallback) {
        return plugin.getConfig().getString(path, fallback);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("review")) {
            review(sender, args[1]);
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(color("&bKeenAC &8» &fИспользование: &e/keenac <alerts|review <игрок>|reload>"));
            return true;
        }

        if (args[0].equalsIgnoreCase("alerts")) {
            if (!sender.hasPermission(permission("commands.alerts-permission", "keenac.alerts"))) {
                sender.sendMessage(color("&bKeenAC &8» &cНедостаточно прав."));
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(color("&bKeenAC &8» &cЭта команда доступна только игроку."));
                return true;
            }
            boolean enabled = plugin.getAlertManager().toggle(player);
            sender.sendMessage(color("&bKeenAC &8» &fАлерты: " + (enabled ? "&aвключены" : "&cвыключены")));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(permission("commands.reload-permission", "keenac.reload"))) {
                sender.sendMessage(color("&bKeenAC &8» &cНедостаточно прав."));
                return true;
            }
            plugin.reloadConfig();
            plugin.reloadChecks();
            sender.sendMessage(color("&bKeenAC &8» &aКонфигурация перезагружена."));
            plugin.getLogger().info(sender.getName() + " reloaded the configuration.");
            return true;
        }

        sender.sendMessage(color("&bKeenAC &8» &cНеизвестная команда."));
        return true;
    }

    private void review(CommandSender sender, String playerName) {
        if (!sender.hasPermission(permission("commands.review-permission", "keenac.review"))) {
            sender.sendMessage(color("&bKeenAC &8» &cНедостаточно прав."));
            return;
        }

        Player online = Bukkit.getPlayerExact(playerName);
        UUID playerId = online != null
                ? online.getUniqueId()
                : plugin.getEvidenceManager().findPlayerId(playerName);
        if (playerId == null) {
            sender.sendMessage(color("&bKeenAC &8» &cИстория игрока не найдена."));
            return;
        }

        List<EvidenceRecord> records = plugin.getEvidenceManager().recent(playerId, 10);
        if (records.isEmpty()) {
            sender.sendMessage(color("&bKeenAC &8» &eДля этого игрока пока нет сохранённых доказательств."));
            return;
        }

        sender.sendMessage(color("&bKeenAC &8» &fПоследние срабатывания: &e"
                + records.get(0).playerName() + " &8(" + records.size() + ")"));
        for (EvidenceRecord record : records) {
            String time = TIME_FORMAT.format(Instant.ofEpochMilli(record.timestampMillis()));
            sender.sendMessage(color("&8[" + time + "] &c" + record.checkName()
                    + " &7x" + record.violationLevel() + " &8- &f" + record.reason()));

            PlayerContextSnapshot context = record.context();
            if (context == null) continue;
            String state = context.gameMode()
                    + (context.onGround() ? ", ground" : ", air")
                    + (context.sprinting() ? ", sprinting" : "")
                    + (context.sneaking() ? ", sneaking" : "")
                    + (context.swimming() ? ", swimming" : "")
                    + (context.inWater() ? ", water" : "")
                    + (context.flying() ? ", flying" : "")
                    + (context.gliding() ? ", gliding" : "")
                    + (context.inVehicle() ? ", vehicle" : "");
            String position = String.format(Locale.ROOT, "%.2f, %.2f, %.2f",
                    context.x(), context.y(), context.z());
            String tps = String.format(Locale.ROOT, "%.2f", context.tps());
            sender.sendMessage(color("&8  Ping: &f" + context.pingMs() + "ms"
                    + "  &8TPS: &f" + tps + "  &8State: &f" + state));
            sender.sendMessage(color("&8  World: &f" + context.world()
                    + "  &8Pos: &f" + position
                    + "  &8Teleport: &f" + age(context.teleportAgeMs())
                    + "  &8Velocity: &f" + age(context.velocityAgeMs())));
            if (!context.potionEffects().isBlank()) {
                sender.sendMessage(color("&8  Effects: &f" + context.potionEffects()));
            }
        }
    }

    private String age(long milliseconds) {
        return milliseconds < 0L ? "n/a" : milliseconds + "ms";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            if (sender.hasPermission(permission("commands.alerts-permission", "keenac.alerts"))
                    && "alerts".startsWith(prefix)) suggestions.add("alerts");
            if (sender.hasPermission(permission("commands.reload-permission", "keenac.reload"))
                    && "reload".startsWith(prefix)) suggestions.add("reload");
            if (sender.hasPermission(permission("commands.review-permission", "keenac.review"))
                    && "review".startsWith(prefix)) suggestions.add("review");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("review")
                && sender.hasPermission(permission("commands.review-permission", "keenac.review"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    suggestions.add(player.getName());
                }
            }
        }
        return suggestions;
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
