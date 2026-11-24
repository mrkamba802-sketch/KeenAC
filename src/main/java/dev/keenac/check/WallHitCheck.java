package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class WallHitCheck implements Listener {
    private final KeenAC plugin;
    private final Map<UUID, Integer> violations = new HashMap<>();
    private final Map<UUID, Long> lastAlertMillis = new HashMap<>();

    public WallHitCheck(KeenAC plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        if (!plugin.getConfig().getBoolean("checks.wall-hit.enabled", true)) {
            return;
        }

        Entity target = event.getEntity();
        if (attacker.getWorld() != target.getWorld() || attacker.hasLineOfSight(target)) {
            return;
        }

        event.setCancelled(true);
        UUID id = attacker.getUniqueId();
        int vl = violations.merge(id, 1, Integer::sum);

        long now = System.currentTimeMillis();
        long cooldown = Math.max(3L,
                plugin.getConfig().getLong("alerts.delay-seconds", 3L)) * 1000L;
        long previous = lastAlertMillis.getOrDefault(id, 0L);
        if (now - previous >= cooldown) {
            plugin.getAlertManager().send("WallHit", attacker, vl);
            lastAlertMillis.put(id, now);
        }
    }
}
