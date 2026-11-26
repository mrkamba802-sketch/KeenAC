package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class WallHitCheck implements Listener {
    private final KeenAC plugin;
    private final Map<UUID, Integer> violations = new HashMap<>();
    private final Map<UUID, Long> lastAlertMillis = new HashMap<>();

    public WallHitCheck(KeenAC plugin) { this.plugin = plugin; }

    /**
     * The violation signal comes from a serverbound attack packet. This Bukkit
     * event is retained only as a final server-side hit-cancellation safeguard.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!plugin.getConfig().getBoolean("checks.wall-hit.enabled", true)
                || !plugin.getConfig().getBoolean("checks.wall-hit.cancel-hits", true)) return;
        if (attacker.getWorld() != event.getEntity().getWorld()
                || !canSeeAnyPart(attacker, event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** Called on the primary thread after a PacketEvents ATTACK packet is decoded. */
    public void handlePacketAttack(UUID attackerId, int targetEntityId) {
        if (!plugin.getConfig().getBoolean("checks.wall-hit.enabled", true)) return;
        Player attacker = org.bukkit.Bukkit.getPlayer(attackerId);
        if (attacker == null || !attacker.isOnline() || attacker.isDead()) return;

        Entity target = null;
        for (Entity nearby : attacker.getNearbyEntities(8.0, 8.0, 8.0)) {
            if (nearby.getEntityId() == targetEntityId) {
                target = nearby;
                break;
            }
        }
        if (target == null || target.getWorld() != attacker.getWorld()
                || canSeeAnyPart(attacker, target)) {
            return;
        }

        int vl = violations.merge(attackerId, 1, (current, one) ->
                current == Integer.MAX_VALUE ? Integer.MAX_VALUE : current + 1);
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0L,
                plugin.getConfig().getLong("alerts.delay-seconds", 3L)) * 1000L;
        long previous = lastAlertMillis.getOrDefault(attackerId, 0L);
        if (now - previous >= cooldown) {
            plugin.getAlertManager().send("WallHit", attacker, vl);
            lastAlertMillis.put(attackerId, now);
        }
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        violations.remove(id);
        lastAlertMillis.remove(id);
    }

    public void clearAll() {
        violations.clear();
        lastAlertMillis.clear();
    }

    /*
     * Test several points inside the target hitbox. A single clear ray is
     * enough: partially exposed entities must remain hittable.
     */
    private boolean canSeeAnyPart(Player attacker, Entity target) {
        Location eye = attacker.getEyeLocation();
        BoundingBox box = target.getBoundingBox();
        double inset = 0.04;
        double minX = Math.min(box.getMaxX(), box.getMinX() + inset);
        double maxX = Math.max(box.getMinX(), box.getMaxX() - inset);
        double minY = Math.min(box.getMaxY(), box.getMinY() + inset);
        double maxY = Math.max(box.getMinY(), box.getMaxY() - inset);
        double minZ = Math.min(box.getMaxZ(), box.getMinZ() + inset);
        double maxZ = Math.max(box.getMinZ(), box.getMaxZ() - inset);

        double[][] points = {
                { (minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2 },
                { minX, minY, minZ }, { minX, minY, maxZ },
                { minX, maxY, minZ }, { minX, maxY, maxZ },
                { maxX, minY, minZ }, { maxX, minY, maxZ },
                { maxX, maxY, minZ }, { maxX, maxY, maxZ },
                { (minX + maxX) / 2, minY, (minZ + maxZ) / 2 },
                { (minX + maxX) / 2, maxY, (minZ + maxZ) / 2 }
        };

        for (double[] point : points) {
            Vector delta = new Vector(point[0] - eye.getX(),
                    point[1] - eye.getY(), point[2] - eye.getZ());
            double distance = delta.length();
            if (distance < 0.001) return true;
            if (attacker.getWorld().rayTraceBlocks(eye, delta.multiply(1.0 / distance),
                    distance, FluidCollisionMode.NEVER, true) == null) {
                return true;
            }
        }
        return false;
    }
}
