package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.attribute.Attribute;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class NoSlowCheck implements Listener {
    private static final long MAX_USE_TICKS = 80L;
    private final KeenAC plugin;
    private final Map<UUID, UseData> usingItems = new HashMap<>();
    private final Map<UUID, Integer> violations = new HashMap<>();
    private final Map<UUID, Long> lastViolationNanos = new HashMap<>();
    private final Map<UUID, Material> pendingConsumeCancellation = new HashMap<>();
    private final Map<UUID, Long> lastAirborneNanos = new HashMap<>();

    public NoSlowCheck(KeenAC plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::decayViolations, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::interruptFlaggedUse, 1L, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!plugin.getConfig().getBoolean("checks.no-slow.enabled", true)) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (isTrackedConsumable(player.getInventory().getItemInMainHand())
                || isTrackedConsumable(player.getInventory().getItemInOffHand())) {
            UUID id = player.getUniqueId();
            long now = System.nanoTime();
            UseData data = usingItems.get(id);
            if (data == null) {
                usingItems.put(id, new UseData(player.getLocation().clone(), now, 0));
            } else {
                // A forced interruption can be followed by another use attempt.
                // Preserve normal-speed evidence across those attempts.
                data.lastLocation = player.getLocation().clone();
                data.lastSampleNanos = now;
                data.samples = 0;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("checks.no-slow.enabled", true)) return;
        if (event.getTo() == null) return;
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (!player.isHandRaised()) {
            // The check itself may have interrupted item use. Keep the pending
            // state and samples so we can resume measuring when the player tries again.
            if (!pendingConsumeCancellation.containsKey(id)) {
                usingItems.remove(id);
            }
            return;
        }
        UseData data = usingItems.get(id);
        if (data == null) return;

        long elapsedNanos = System.nanoTime() - data.lastSampleNanos;
        if (elapsedNanos < 35_000_000L) return;
        if (elapsedNanos > MAX_USE_TICKS * 50_000_000L) {
            usingItems.remove(id);
            return;
        }

        if (isExempt(player, event.getTo().getBlock())) {
            data.lastLocation = event.getTo().clone();
            data.lastSampleNanos = System.nanoTime();
            data.samples = 0;
            data.normalSamples = 0;
            data.lastSuspiciousNanos = 0L;
            pendingConsumeCancellation.remove(id);
            return;
        }

        double dx = event.getTo().getX() - data.lastLocation.getX();
        double dz = event.getTo().getZ() - data.lastLocation.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double seconds = elapsedNanos / 1_000_000_000.0;
        double movementAttribute = player.getAttribute(Attribute.MOVEMENT_SPEED) == null
                ? 0.1 : player.getAttribute(Attribute.MOVEMENT_SPEED).getValue();
        // Vanilla item-use movement is heavily reduced. Allow a generous margin for
        // latency, collision resolution, and attribute modifiers to avoid false flags.
        double allowed = movementAttribute * 0.2 * 20.0 * seconds + 0.035;
        boolean suspicious = horizontal > allowed * 2.6 && horizontal > 0.09;

        data.lastLocation = event.getTo().clone();
        data.lastSampleNanos = System.nanoTime();
        if (suspicious) {
            data.samples++;
            data.normalSamples = 0;
            data.lastSuspiciousNanos = System.nanoTime();
        } else {
            data.samples = Math.max(0, data.samples - 1);
            data.normalSamples++;
            // The first normal-speed sample immediately stops repeated interruptions.
            // Three consecutive normal samples also clear the old VL completely.
            data.lastSuspiciousNanos = 0L;
            if (data.normalSamples >= 3) {
                violations.remove(id);
                lastViolationNanos.remove(id);
                pendingConsumeCancellation.remove(id);
                data.samples = 0;
                data.normalSamples = 0;
            }
        }

        int required = Math.max(3, plugin.getConfig().getInt("checks.no-slow.required-samples", 6));
        if (data.samples >= required) {
            int vl = violations.merge(id, 1, Integer::sum);
            lastViolationNanos.put(id, System.nanoTime());
            plugin.getAlertManager().send("NoSlow", player, vl);
            data.samples = 0;
            if (plugin.getConfig().getBoolean("checks.no-slow.cancel-consume", true) && vl >= 3) {
                Material used = getTrackedItemType(player);
                if (used != null) pendingConsumeCancellation.put(id, used);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Material pendingType = pendingConsumeCancellation.get(id);
        if (pendingType != null && event.getItem().getType() == pendingType
                && shouldCancelConsume(pendingType)) {
            event.setCancelled(true);
            event.getPlayer().clearActiveItem();
        }
        pendingConsumeCancellation.remove(id);
        usingItems.remove(id);
    }

    public void clear(UUID id) {
        usingItems.remove(id);
        violations.remove(id);
        lastViolationNanos.remove(id);
        pendingConsumeCancellation.remove(id);
        lastAirborneNanos.remove(id);
    }

    public void clearAll() {
        usingItems.clear();
        violations.clear();
        lastViolationNanos.clear();
        pendingConsumeCancellation.clear();
        lastAirborneNanos.clear();
    }

    private void interruptFlaggedUse() {
        if (!plugin.getConfig().getBoolean("checks.no-slow.enabled", true)
                || !plugin.getConfig().getBoolean("checks.no-slow.cancel-consume", true)) return;
        Iterator<Map.Entry<UUID, Material>> iterator = pendingConsumeCancellation.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Material> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            UUID id = entry.getKey();
            if (player == null || !player.isOnline()) {
                iterator.remove();
                continue;
            }
            Material tracked = entry.getValue();
            if (!shouldCancelConsume(tracked)) {
                iterator.remove();
                continue;
            }
            boolean stillHolding = player.getInventory().getItemInMainHand().getType() == tracked
                    || player.getInventory().getItemInOffHand().getType() == tracked;
            if (!stillHolding) {
                iterator.remove();
                continue;
            }
            UseData data = usingItems.get(id);
            long now = System.nanoTime();
            // Keep interrupting while recent movement samples still prove NoSlow.
            // As soon as normal movement is observed, lastSuspiciousNanos is cleared
            // and the player can continue using the item without being interrupted.
            if (player.isHandRaised() && data != null
                    && data.lastSuspiciousNanos > 0L
                    && now - data.lastSuspiciousNanos <= 750_000_000L) {
                player.clearActiveItem();
            }
        }
    }

    private void decayViolations() {
        long now = System.nanoTime();
        Iterator<Map.Entry<UUID, Integer>> iterator = violations.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            UUID id = entry.getKey();
            long lastViolation = lastViolationNanos.getOrDefault(id, now);
            if (now - lastViolation < 20_000_000_000L) continue;

            int reducedVl = entry.getValue() - 1;
            if (reducedVl <= 0) {
                iterator.remove();
                lastViolationNanos.remove(id);
            } else {
                entry.setValue(reducedVl);
                lastViolationNanos.put(id, now);
            }
        }
    }

    private boolean shouldCancelConsume(Material material) {
        if (!plugin.getConfig().getBoolean("checks.no-slow.cancel-consume", true)) return false;
        return !material.isEdible()
                || plugin.getConfig().getBoolean("checks.no-slow.reset-food", true);
    }

    private Material getTrackedItemType(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (isTrackedConsumable(main)) return main.getType();
        ItemStack off = player.getInventory().getItemInOffHand();
        return isTrackedConsumable(off) ? off.getType() : null;
    }

    private boolean isTrackedConsumable(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        Material type = item.getType();
        return type.isEdible() || type == Material.POTION || type == Material.MILK_BUCKET;
    }

    private boolean isExempt(Player player, Block block) {
        UUID id = player.getUniqueId();
        long now = System.nanoTime();
        // In water, players are normally not grounded. Do not apply the airborne
        // sprint-jump exemption there: NoSlow must continue sampling swimming movement.
        boolean inWater = player.isInWater() || player.isSwimming();
        if (!inWater && (!player.isOnGround() || player.getVelocity().getY() > 0.08)) {
            lastAirborneNanos.put(id, now);
            return true;
        }
        if (!inWater && now - lastAirborneNanos.getOrDefault(id, 0L) < 600_000_000L) return true;

        if (player.isDead() || !player.isValid() || player.isInsideVehicle()
                || player.isGliding() || player.isFlying() || player.isClimbing()
                || player.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                || player.hasPotionEffect(PotionEffectType.LEVITATION)) return true;
        if (!plugin.getConfig().getBoolean("checks.no-slow.block-friction", true)) return false;
        Material under = block.getRelative(0, -1, 0).getType();
        return under == Material.ICE || under == Material.PACKED_ICE
                || under == Material.BLUE_ICE || under == Material.FROSTED_ICE
                || under == Material.SLIME_BLOCK || under == Material.HONEY_BLOCK
                || under == Material.SOUL_SAND || under == Material.SOUL_SOIL
                || under == Material.MUD;
    }

    private static final class UseData {
        private org.bukkit.Location lastLocation;
        private long lastSampleNanos;
        private int samples;
        private int normalSamples;
        private long lastSuspiciousNanos;

        private UseData(org.bukkit.Location location, long now, int samples) {
            this.lastLocation = location;
            this.lastSampleNanos = now;
            this.samples = samples;
        }
    }
}