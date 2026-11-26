package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WallInteractCheck implements Listener {
    private final KeenAC plugin;
    private final Map<UUID, Integer> violations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAlertMillis = new ConcurrentHashMap<>();

    public WallInteractCheck(KeenAC plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockInteract(PlayerInteractEvent event) {
        if (!enabled() || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getWorld() != player.getWorld()) {
            return;
        }

        // Soft mode: avoid checking close-range clicks and only cancel when
        // the player is clearly aiming toward a target beyond a solid occluder.
        if (player.getEyeLocation().distance(clicked.getLocation().add(0.5, 0.5, 0.5))
                < minCheckDistance()) {
            return;
        }
        Block obstacle = findOccluder(player, clicked);
        if (obstacle != null && isSolidOccluder(obstacle)) {
            if (cancelActions()) {
                event.setCancelled(true);
            }
            report(player, "Attempted to interact with " + clicked.getType()
                    + " while Line of Sight was blocked by " + obstacle.getType() + ".");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (!enabled()) {
            return;
        }

        Player player = event.getPlayer();
        Entity target = event.getRightClicked();
        if (target.getWorld() != player.getWorld()
                || player.getEyeLocation().distance(target.getLocation()) > maxDistance()) {
            return;
        }
        if (!canSeeAnyPart(player, target)) {
            if (cancelActions()) {
                event.setCancelled(true);
            }
            report(player, "Attempted to interact with entity " + target.getType()
                    + " while Line of Sight was blocked by a solid block.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        violations.remove(id);
        lastAlertMillis.remove(id);
    }

    public void clearAll() {
        violations.clear();
        lastAlertMillis.clear();
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("checks.wall-interact.enabled", true);
    }

    private boolean cancelActions() {
        return plugin.getConfig().getBoolean("checks.wall-interact.cancel-actions", true);
    }

    private double maxDistance() {
        return Math.max(4.0,
                plugin.getConfig().getDouble("checks.wall-interact.max-distance", 7.0));
    }

    private double minCheckDistance() {
        return Math.max(2.5,
                plugin.getConfig().getDouble("checks.wall-interact.min-check-distance", 4.5));
    }

    private boolean isSolidOccluder(Block block) {
        // Require a genuinely occluding, simple collision shape. This skips
        // slabs, stairs, fences, panes and other partial geometry.
        return block.getType().isSolid()
                && block.getType().isOccluding()
                && block.getCollisionShape().getBoundingBoxes().size() == 1;
    }

    private Block findOccluder(Player player, Block target) {
        Location eye = player.getEyeLocation();
        Location targetCenter = target.getLocation().add(0.5, 0.5, 0.5);
        Vector direction = targetCenter.toVector().subtract(eye.toVector());
        double targetDistance = direction.length();

        // Only evaluate clear, longer-range interactions. Aim the ray at the
        // clicked block itself instead of extending the player's view ray past it.
        if (targetDistance < minCheckDistance() || targetDistance > maxDistance()
                || targetDistance < 0.05) {
            return null;
        }

        direction.multiply(1.0 / targetDistance);
        var trace = player.getWorld().rayTraceBlocks(eye, direction,
                Math.max(0.05, targetDistance - 0.05), FluidCollisionMode.NEVER, true);
        if (trace == null || trace.getHitBlock() == null
                || trace.getHitBlock().equals(target)) {
            return null;
        }
        return trace.getHitBlock();
    }

    private void report(Player player, String reason) {
        UUID id = player.getUniqueId();
        int level = violations.merge(id, 1, (current, one) ->
                current == Integer.MAX_VALUE ? Integer.MAX_VALUE : current + 1);
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0L,
                plugin.getConfig().getLong("alerts.delay-seconds", 3L)) * 1000L;
        long previous = lastAlertMillis.getOrDefault(id, 0L);
        if (now - previous >= cooldown) {
            plugin.getAlertManager().send("WallInteract", player, level, reason);
            lastAlertMillis.put(id, now);
        }
    }

    /**
     * A target with even one clearly exposed hitbox sample remains interactable.
     * This avoids blocking legitimate clicks on entities partly visible around
     * the edge of a wall or doorway.

     * Block breaking is intentionally not handled here: BlockBreakEvent can be
     * delayed relative to the player's last rotation update, which makes a
     * server-side ray aimed from the stale rotation produce false positives.
     * Right-click block interactions are checked at PlayerInteractEvent instead.
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
            if (distance > maxDistance()) continue;
            if (attacker.getWorld().rayTraceBlocks(eye, delta.multiply(1.0 / distance),
                    distance, FluidCollisionMode.NEVER, true) == null) {
                return true;
            }
        }
        return false;
    }
}
