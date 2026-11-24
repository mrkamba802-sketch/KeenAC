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

    public NoSlowCheck(KeenAC plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::decayViolations, 20L, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!plugin.getConfig().getBoolean("checks.no-slow.enabled", true)) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (isTrackedConsumable(player.getInventory().getItemInMainHand())
                || isTrackedConsumable(player.getInventory().getItemInOffHand())) {
            usingItems.put(player.getUniqueId(), new UseData(player.getLocation().clone(), System.nanoTime(), 0));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("checks.no-slow.enabled", true)) return;
        if (event.getTo() == null) return;
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        UseData data = usingItems.get(id);
        if (data == null) return;
        if (!player.isHandRaised()) {
            usingItems.remove(id);
            return;
        }

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
        boolean suspicious = horizontal > allowed * 2.2 && horizontal > 0.075;

        data.lastLocation = event.getTo().clone();
        data.lastSampleNanos = System.nanoTime();
        data.samples = suspicious ? data.samples + 1 : Math.max(0, data.samples - 1);

        int required = Math.max(3, plugin.getConfig().getInt("checks.no-slow.required-samples", 5));
        if (data.samples >= required) {
            int vl = violations.merge(id, 1, Integer::sum);
            lastViolationNanos.put(id, System.nanoTime());
            plugin.getAlertManager().send("NoSlow", player, vl);
            data.samples = 0;
            if (plugin.getConfig().getBoolean("checks.no-slow.cancel-consume", true) && vl >= 3) {
                data.flagged = true;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        UseData data = usingItems.remove(id);
        if (data != null && data.flagged) {
            event.setCancelled(true);
            event.getPlayer().clearActiveItem();
        }
    }

    public void clear(UUID id) {
        usingItems.remove(id);
        violations.remove(id);
        lastViolationNanos.remove(id);
    }

    public void clearAll() {
        usingItems.clear();
        violations.clear();
        lastViolationNanos.clear();
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

    private boolean isTrackedConsumable(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        Material type = item.getType();
        return type.isEdible() || type == Material.POTION || type == Material.MILK_BUCKET;
    }

    private boolean isExempt(Player player, Block block) {
        if (player.isDead() || !player.isValid() || player.isInsideVehicle()
                || player.isGliding() || player.isSwimming() || player.isFlying()
                || player.isInWater() || player.isClimbing()
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
        private boolean flagged;

        private UseData(org.bukkit.Location location, long now, int samples) {
            this.lastLocation = location;
            this.lastSampleNanos = now;
            this.samples = samples;
        }
    }
}