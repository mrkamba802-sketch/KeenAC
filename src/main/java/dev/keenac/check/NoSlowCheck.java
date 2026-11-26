package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Location;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Set;

public final class NoSlowCheck implements Listener {
    private static final long MAX_USE_TICKS = 80L;
    private final KeenAC plugin;
    private final Map<UUID, UseData> usingItems = new HashMap<>();
    private final Map<UUID, Integer> violations = new HashMap<>();
    private final Map<UUID, Long> lastViolationNanos = new HashMap<>();
    private final Map<UUID, Material> pendingConsumeCancellation = new HashMap<>();
    private final Map<UUID, Long> lastInterruptionAlertNanos = new HashMap<>();
    private final Map<UUID, Long> lastAirborneNanos = new HashMap<>();
    private final Map<UUID, Long> pendingUsePackets = new ConcurrentHashMap<>();
    private final Set<UUID> pendingUseReleases = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ConcurrentLinkedQueue<PacketMovement>> packetMovements =
            new ConcurrentHashMap<>();

    public NoSlowCheck(KeenAC plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::decayViolations, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::interruptFlaggedUse, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::processPacketInputs, 1L, 1L);
    }

    /** PacketEvents callbacks only enqueue primitive data; this method runs synchronously. */
    public void recordUseIntent(UUID id) {
        if (id != null && plugin.getConfig().getBoolean("checks.no-slow.enabled", true)) {
            pendingUsePackets.put(id, System.nanoTime());
        }
    }

    public void recordUseRelease(UUID id) {
        if (id != null) pendingUseReleases.add(id);
    }

    public void recordPacketMovement(UUID id, double x, double y, double z) {
        if (id == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return;
        ConcurrentLinkedQueue<PacketMovement> queue =
                packetMovements.computeIfAbsent(id, ignored -> new ConcurrentLinkedQueue<>());
        queue.add(new PacketMovement(x, y, z, System.nanoTime()));
        while (queue.size() > 128) {
            if (queue.poll() == null) break;
        }
    }

    private void processPacketInputs() {
        if (!plugin.getConfig().getBoolean("checks.no-slow.enabled", true)) {
            pendingUsePackets.clear();
            pendingUseReleases.clear();
            packetMovements.values().forEach(ConcurrentLinkedQueue::clear);
            usingItems.clear();
            return;
        }

        long now = System.nanoTime();
        for (Map.Entry<UUID, Long> entry : pendingUsePackets.entrySet()) {
            UUID id = entry.getKey();
            long at = entry.getValue();
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline() || now - at > 500_000_000L) {
                pendingUsePackets.remove(id, at);
                continue;
            }
            if (player.hasActiveItem() && isTrackedConsumable(player.getActiveItem())) {
                beginUse(player, at);
                pendingUsePackets.remove(id, at);
            }
        }

        for (UUID id : pendingUseReleases) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.hasActiveItem()) {
                if (!pendingConsumeCancellation.containsKey(id)) usingItems.remove(id);
                pendingUseReleases.remove(id);
            }
        }

        for (Map.Entry<UUID, ConcurrentLinkedQueue<PacketMovement>> entry : packetMovements.entrySet()) {
            UUID id = entry.getKey();
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                entry.getValue().clear();
                continue;
            }
            PacketMovement sample;
            while ((sample = entry.getValue().poll()) != null) {
                Location to = new Location(player.getWorld(), sample.x, sample.y, sample.z);
                handlePacketMovement(player, to, sample.atNanos);
            }
        }
    }

    private void beginUse(Player player, long now) {
        UUID id = player.getUniqueId();
        UseData data = usingItems.get(id);
        Location current = player.getLocation().clone();
        if (data == null) {
            data = new UseData(current, now, 0);
            usingItems.put(id, data);
        } else {
            data.lastLocation = current.clone();
            data.lastSampleNanos = now;
            data.lastUseAttemptNanos = now;
            data.samples = 0;
            data.normalSamples = 0;
            data.suspiciousDistance = 0.0;
            data.lastSuspiciousNanos = 0L;
            data.lastValidLocation = current.clone();
        }
        plugin.getMovementSetbackManager().recordValidPosition(player, current);
        ConcurrentLinkedQueue<PacketMovement> queue = packetMovements.get(id);
        if (queue != null) queue.clear();
    }

    private void handlePacketMovement(Player player, Location to, long sampleNanos) {
        if (!plugin.getConfig().getBoolean("checks.no-slow.enabled", true)) return;
        UUID id = player.getUniqueId();
        if (!player.hasActiveItem() || !isTrackedConsumable(player.getActiveItem())) {
            if (!pendingConsumeCancellation.containsKey(id)) usingItems.remove(id);
            return;
        }
        UseData data = usingItems.get(id);
        if (data == null) return;

        long elapsedNanos = sampleNanos - data.lastSampleNanos;
        if (elapsedNanos < 35_000_000L) return;
        if (elapsedNanos > MAX_USE_TICKS * 50_000_000L) {
            usingItems.remove(id);
            return;
        }

        if (isExempt(player, to.getBlock())) {
            data.lastLocation = to.clone();
            data.lastValidLocation = to.clone();
            data.lastSampleNanos = sampleNanos;
            data.samples = 0;
            data.normalSamples = 0;
            data.suspiciousDistance = 0.0;
            data.lastSuspiciousNanos = 0L;
            plugin.getMovementSetbackManager().recordValidPosition(player, to);
            pendingConsumeCancellation.remove(id);
            return;
        }

        double dx = to.getX() - data.lastLocation.getX();
        double dz = to.getZ() - data.lastLocation.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double seconds = Math.min(elapsedNanos / 1_000_000_000.0, 0.25);
        boolean inWater = player.isInWater() || player.isSwimming();
        double allowed = getAllowedItemUseSpeed(player, inWater) * seconds * 1.45
                + (inWater ? 0.012 : 0.02);
        boolean suspicious = horizontal > allowed && horizontal > (inWater ? 0.035 : 0.09);

        data.lastLocation = to.clone();
        data.lastSampleNanos = sampleNanos;
        if (suspicious) {
            data.samples++;
            data.suspiciousDistance += horizontal;
            data.normalSamples = 0;
            data.lastSuspiciousNanos = sampleNanos;
        } else {
            data.samples = Math.max(0, data.samples - 1);
            data.suspiciousDistance = 0.0;
            data.normalSamples++;
            data.lastSuspiciousNanos = 0L;
            data.lastValidLocation = to.clone();
            plugin.getMovementSetbackManager().recordValidPosition(player, to);
            if (data.normalSamples >= 3) {
                violations.remove(id);
                lastViolationNanos.remove(id);
                pendingConsumeCancellation.remove(id);
                data.samples = 0;
                data.normalSamples = 0;
            }
        }

        int required = Math.max(3, plugin.getConfig().getInt("checks.no-slow.required-samples", 6));
        int minimumSetbackSamples = Math.max(2, Math.min(required,
                plugin.getConfig().getInt("checks.no-slow.setback.minimum-samples", 2)));
        double setbackDistance = Math.max(0.10, Math.min(0.30,
                plugin.getConfig().getDouble("checks.no-slow.setback.threshold-distance", 0.18)));
        boolean distanceConfirmed = data.samples >= minimumSetbackSamples
                && data.suspiciousDistance >= setbackDistance;

        if (data.samples >= required || distanceConfirmed) {
            recordViolation(player);

            // NoSlow's food/consume interruption logic remains independent and unchanged.
            if (plugin.getConfig().getBoolean("checks.no-slow.setback.enabled", true)) {
                plugin.getMovementSetbackManager().setback(player, data.lastValidLocation, "NoSlow");
            }

            data.samples = 0;
            data.suspiciousDistance = 0.0;
            data.normalSamples = 0;
            data.lastSampleNanos = System.nanoTime();
            data.lastLocation = player.getLocation().clone();
            data.lastValidLocation = data.lastLocation.clone();

            ConcurrentLinkedQueue<PacketMovement> queued = packetMovements.get(id);
            if (queued != null) queued.clear();

            int currentVl = violations.getOrDefault(id, 0);
            if (plugin.getConfig().getBoolean("checks.no-slow.cancel-consume", true) && currentVl >= 3) {
                Material used = getTrackedItemType(player);
                if (used != null) pendingConsumeCancellation.put(id, used);
            }
        }
    }


    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Material pendingType = pendingConsumeCancellation.get(id);
        UseData data = usingItems.get(id);
        long now = System.nanoTime();
        boolean recentlySuspicious = data != null && data.lastSuspiciousNanos > 0L
                && data.lastSuspiciousNanos >= data.lastUseAttemptNanos
                && now - data.lastSuspiciousNanos <= 750_000_000L;
        if (pendingType != null && recentlySuspicious
                && event.getItem().getType() == pendingType
                && shouldCancelConsume(pendingType)) {
            event.setCancelled(true);
            event.getPlayer().clearActiveItem();
            // Keep the check active after a cancelled consume. Each renewed,
            // freshly confirmed NoSlow attempt can produce the next VL alert.
            recordInterruptionViolation(event.getPlayer());
            return;
        }
        pendingConsumeCancellation.remove(id);
        usingItems.remove(id);
        lastInterruptionAlertNanos.remove(id);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    public void clear(UUID id) {
        usingItems.remove(id);
        violations.remove(id);
        lastViolationNanos.remove(id);
        pendingConsumeCancellation.remove(id);
        lastInterruptionAlertNanos.remove(id);
        lastAirborneNanos.remove(id);
        pendingUsePackets.remove(id);
        pendingUseReleases.remove(id);
        ConcurrentLinkedQueue<PacketMovement> queue = packetMovements.remove(id);
        if (queue != null) queue.clear();
    }

    public void clearAll() {
        usingItems.clear();
        violations.clear();
        lastViolationNanos.clear();
        pendingConsumeCancellation.clear();
        lastInterruptionAlertNanos.clear();
        lastAirborneNanos.clear();
        pendingUsePackets.clear();
        pendingUseReleases.clear();
        packetMovements.values().forEach(ConcurrentLinkedQueue::clear);
        packetMovements.clear();
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
            if (player.hasActiveItem() && isTrackedConsumable(player.getActiveItem())
                    && player.getActiveItem().getType() == tracked
                    && data != null
                    && data.lastSuspiciousNanos >= data.lastUseAttemptNanos
                    && data.lastSuspiciousNanos > 0L
                    && now - data.lastSuspiciousNanos <= 750_000_000L) {
                player.clearActiveItem();
                recordInterruptionViolation(player);
            }
        }
    }

    private void recordViolation(Player player) {
        UUID id = player.getUniqueId();
        int vl = violations.merge(id, 1, Integer::sum);
        lastViolationNanos.put(id, System.nanoTime());
        plugin.getAlertManager().send("NoSlow", player, vl,
                "Movement or item-use timing did not match the expected slowdown while using an item.");
    }

    private void recordInterruptionViolation(Player player) {
        UUID id = player.getUniqueId();
        long now = System.nanoTime();
        long last = lastInterruptionAlertNanos.getOrDefault(id, 0L);
        // Guard against the consume event and the 1-tick enforcement task
        // reporting the same interruption twice.
        if (now - last < 250_000_000L) return;
        lastInterruptionAlertNanos.put(id, now);
        recordViolation(player);
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

    /**
     * Estimates vanilla horizontal movement while an item is being used.
     * Ground and water have different movement speeds; water additionally
     * accounts for sprint-swimming, Depth Strider, Dolphin's Grace and
     * movement-speed potion/attribute modifiers.
     */
    private double getAllowedItemUseSpeed(Player player, boolean inWater) {
        double movementMultiplier = 1.0;
        if (player.getAttribute(Attribute.MOVEMENT_SPEED) != null) {
            Attribute movement = Attribute.MOVEMENT_SPEED;
            double base = player.getAttribute(movement).getBaseValue();
            double value = player.getAttribute(movement).getValue();
            if (base > 0.0) {
                movementMultiplier = Math.max(0.75, Math.min(4.0, value / base));
            }
        }

        if (inWater) {
            int depthStrider = 0;
            ItemStack boots = player.getInventory().getBoots();
            if (boots != null) {
                depthStrider = boots.getEnchantmentLevel(Enchantment.DEPTH_STRIDER);
            }

            double fullWaterSpeed;
            if (player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE)) {
                // Java Edition Dolphin's Grace combines with Depth Strider.
                fullWaterSpeed = 9.8 + 8.983 * depthStrider;
            } else if (player.isSwimming() && player.isSprinting()) {
                // Sprint-swimming speed; Depth Strider III reaches about 5.3 blocks/sec.
                fullWaterSpeed = 3.918 * (1.0 + 0.118 * depthStrider);
            } else if (player.isSwimming()) {
                fullWaterSpeed = 2.20 * (1.0 + depthStrider / 3.0);
            } else {
                fullWaterSpeed = 1.97 * (1.0 + depthStrider / 3.0);
            }
            return fullWaterSpeed * movementMultiplier * 0.2;
        }

        // Normal walking speed is approximately 4.317 blocks/sec at the
        // default movement attribute. Sprinting increases it by about 30%.
        double fullGroundSpeed = 4.317 * movementMultiplier;
        if (player.isSprinting()) {
            fullGroundSpeed *= 1.3;
        }
        return fullGroundSpeed * 0.2;
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
        // Floor-friction exemptions are for land movement. Applying them to
        // submerged players can silently disable NoSlow throughout the water.
        if (!plugin.getConfig().getBoolean("checks.no-slow.block-friction", true) || inWater) return false;
        Material under = block.getRelative(0, -1, 0).getType();
        return under == Material.ICE || under == Material.PACKED_ICE
                || under == Material.BLUE_ICE || under == Material.FROSTED_ICE
                || under == Material.SLIME_BLOCK || under == Material.HONEY_BLOCK
                || under == Material.SOUL_SAND || under == Material.SOUL_SOIL
                || under == Material.MUD;
    }

    private record PacketMovement(double x, double y, double z, long atNanos) { }

    private static final class UseData {
        private org.bukkit.Location lastLocation;
        private org.bukkit.Location lastValidLocation;
        private long lastSampleNanos;
        private int samples;
        private int normalSamples;
        private double suspiciousDistance;
        private long lastSuspiciousNanos;
        private long lastUseAttemptNanos;

        private UseData(org.bukkit.Location location, long now, int samples) {
            this.lastLocation = location.clone();
            this.lastValidLocation = location.clone();
            this.lastSampleNanos = now;
            this.lastUseAttemptNanos = now;
            this.samples = samples;
        }
    }
}