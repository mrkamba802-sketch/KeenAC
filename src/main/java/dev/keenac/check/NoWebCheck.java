package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.event.entity.EntityToggleGlideEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Set;

public final class NoWebCheck implements Listener {
    private static final long VIOLATION_DECAY_NANOS = 5_000_000_000L;

    private final KeenAC plugin;
    private final Map<UUID, PlayerState> states = new HashMap<>();
    private final Map<UUID, Long> elytraGraceUntilNanos = new HashMap<>();
    private final Map<UUID, Long> pearlGraceUntilNanos = new HashMap<>();
    private final Map<UUID, Long> velocityGraceUntilNanos = new HashMap<>();
    private final Set<UUID> pearlBaselinePending = new java.util.HashSet<>();
    private final Map<UUID, ConcurrentLinkedQueue<PacketPosition>> packetPositions =
            new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastPacketLocations = new HashMap<>();
    private final Set<UUID> applyingCorrection = ConcurrentHashMap.newKeySet();
    private final Set<UUID> correctionThisSample = new java.util.HashSet<>();
    private long serverTick;

    public NoWebCheck(KeenAC plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            serverTick++;
            processPacketMovements();
            if (serverTick % 20L == 0L) {
                decayStates();
            }
            states.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
            lastPacketLocations.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
            pearlGraceUntilNanos.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
            velocityGraceUntilNanos.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        }, 1L, 1L);
    }

    /**
     * PacketEvents callback: queues immutable movement data only.
     * World queries and player corrections are processed by the sync tick task.
     */
    public void recordPacketPosition(UUID id, double x, double y, double z) {
        if (id == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return;
        }
        ConcurrentLinkedQueue<PacketPosition> queue =
                packetPositions.computeIfAbsent(id, ignored -> new ConcurrentLinkedQueue<>());
        queue.add(new PacketPosition(x, y, z, System.nanoTime()));
        while (queue.size() > 128) {
            if (queue.poll() == null) break;
        }
    }

    private void processPacketMovements() {
        for (Map.Entry<UUID, ConcurrentLinkedQueue<PacketPosition>> entry : packetPositions.entrySet()) {
            UUID id = entry.getKey();
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                entry.getValue().clear();
                continue;
            }

            PacketPosition sample;
            while ((sample = entry.getValue().poll()) != null) {
                Location from = lastPacketLocations.get(id);
                if (from == null || from.getWorld() != player.getWorld()) {
                    from = player.getLocation().clone();
                }
                Location serverLocation = player.getLocation();
                Location to = new Location(player.getWorld(), sample.x, sample.y, sample.z,
                        serverLocation.getYaw(), serverLocation.getPitch());
                processPacketMovement(player, from, to, sample.atNanos);
                if (correctionThisSample.remove(id)) {
                    entry.getValue().clear();
                    break;
                }
                lastPacketLocations.put(id, to.clone());
            }
        }
    }

    private void processPacketMovement(Player player, Location from, Location to, long now) {
        UUID id = player.getUniqueId();

        if (!plugin.getConfig().getBoolean("checks.no-web.enabled", true)) {
            states.remove(id);
            return;
        }

        if (player.isGliding() || player.isFlying()
                || player.getGameMode() == org.bukkit.GameMode.CREATIVE
                || player.getGameMode() == org.bukkit.GameMode.SPECTATOR
                || isInsideElytraGrace(id, now)) {
            states.remove(id);
            return;
        }

        if (to == null || from == null || from.getWorld() != to.getWorld()) {
            states.remove(id);
            return;
        }

        // Ender Pearl teleports can place a player directly into a cobweb.
        // Ignore packet displacement during the post-teleport settling window
        // and keep the packet baseline at the destination to avoid stale deltas.
        if (isInsidePearlGrace(id, System.nanoTime())) {
            pearlBaselinePending.remove(id);
            states.remove(id);
            lastPacketLocations.put(id, to.clone());
            return;
        }
        if (pearlBaselinePending.remove(id)) {
            states.remove(id);
            lastPacketLocations.put(id, to.clone());
            return;
        }

        // Server-applied knockback and plugin velocity changes can legitimately
        // carry a player through a web. Re-baseline during the short impulse window.
        if (isInsideVelocityGrace(id, now)) {
            states.remove(id);
            lastPacketLocations.put(id, to.clone());
            return;
        }

        // These movement modes have additional physics that this web-specific
        // horizontal/vertical envelope does not model reliably.
        if (player.isInsideVehicle() || player.isInWater() || player.isSwimming()
                || overlapsAlternativePhysicsBlock(player, from)
                || overlapsAlternativePhysicsBlock(player, to)) {
            // Powder snow, berry bushes, ladders, vines and scaffolding have
            // movement rules that can override or replace cobweb slowdown.
            states.remove(id);
            lastPacketLocations.put(id, to.clone());
            return;
        }

        boolean fromInWeb = intersectsCobweb(player, from);
        boolean toInWeb = intersectsCobweb(player, to);
        PlayerState state = states.get(id);

        // A long packet stall is not a reliable speed sample. Re-anchor instead
        // of turning several delayed client updates into one false violation.
        if (state != null && state.lastMovementNanos > 0L
                && now - state.lastMovementNanos > 300_000_000L) {
            state.lastMovementNanos = now;
            state.lastSafe = to.clone();
            return;
        }

        double elapsedTicks = getElapsedTicks(state, now);
        double maxHorizontalDistance = getAllowedMoveDistance(player, elapsedTicks);
        // NoWeb evaluates horizontal displacement only. Vertical movement inside
        // cobweb depends on entry momentum, gravity and potion physics; a fixed
        // vertical cap is too crude and creates false positives for legitimate falls.
        if (state != null) {
            state.lastMovementNanos = now;
        }

        if (state != null && state.locked) {
            if (!toInWeb) {
                state.locked = false;
                state.anchor = null;
                state.normalMovementSamples = 0;
                state.lastSafe = to.clone();
                if (state.violationLevel > 0) {
                    states.put(id, state);
                } else {
                    states.remove(id);
                }
                return;
            }
            if (state.anchor == null || state.anchor.getWorld() != from.getWorld()) {
                states.remove(id);
                return;
            }

            double moved = from.distance(to);
            if (exceedsWebMovement(from, to, maxHorizontalDistance)) {
                state.normalMovementSamples = 0;
                returnToAnchor(player, to, state);
                countViolation(player, state);
                return;
            }

            state.lastSafe = to.clone();
            state.anchor = to.clone();
            if (moved > 0.005) {
                state.normalMovementSamples++;
                if (state.normalMovementSamples >= 3) {
                    state.locked = false;
                    state.normalMovementSamples = 0;
                }
            }
            return;
        }

        if (!fromInWeb || !toInWeb) {
            if (!fromInWeb && !toInWeb) {
                if (state == null || state.violationLevel == 0) states.remove(id);
            } else if (toInWeb) {
                if (state == null) state = new PlayerState(to, now);
                state.lastSafe = to.clone();
                states.put(id, state);
            } else {
                if (state != null && state.violationLevel > 0) {
                    state.locked = false;
                    state.anchor = null;
                    state.normalMovementSamples = 0;
                    state.lastSafe = to.clone();
                    states.put(id, state);
                } else {
                    states.remove(id);
                }
            }
            return;
        }

        if (state == null) {
            // The first packet after a reset is a baseline, not evidence. This
            // avoids comparing a queued packet to a later server-side location.
            state = new PlayerState(to, now);
            states.put(id, state);
            return;
        }

        if (exceedsWebMovement(from, to, maxHorizontalDistance)) {
            countViolation(player, state);
            if (state.violationLevel >= 2) {
                state.locked = true;
                state.anchor = state.lastSafe == null ? from.clone() : state.lastSafe.clone();
                returnToAnchor(player, to, state);
            }
            return;
        }

        state.lastSafe = to.clone();
    }

    private double getElapsedTicks(PlayerState state, long now) {
        if (state == null || state.lastMovementNanos <= 0L) {
            return 1.0;
        }
        double elapsed = (now - state.lastMovementNanos) / 50_000_000.0;
        // Normalize sub-tick packet spacing; cap long stalls to avoid a
        // large one-off allowance after a delayed client update.
        return Math.max(0.5, Math.min(4.0, elapsed));
    }

    private boolean exceedsWebMovement(Location from, Location to,
                                       double maxHorizontalDistance) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return Math.hypot(dx, dz) > maxHorizontalDistance;
    }

    private boolean isInsideElytraGrace(UUID id, long now) {
        Long until = elytraGraceUntilNanos.get(id);
        if (until == null) return false;
        if (now <= until) return true;
        elytraGraceUntilNanos.remove(id, until);
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (applyingCorrection.contains(id)) return;
        states.remove(id);
        elytraGraceUntilNanos.remove(id);
        lastPacketLocations.remove(id);

        if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            long graceMillis = Math.max(250L, Math.min(5_000L,
                    plugin.getConfig().getLong("checks.no-web.pearl-grace-ms", 1500L)));
            pearlGraceUntilNanos.put(id,
                    System.nanoTime() + graceMillis * 1_000_000L);
            pearlBaselinePending.add(id);
            Location destination = event.getTo();
            if (destination != null) {
                lastPacketLocations.put(id, destination.clone());
            }
        } else {
            pearlGraceUntilNanos.remove(id);
            pearlBaselinePending.remove(id);
        }

        ConcurrentLinkedQueue<PacketPosition> queue = packetPositions.get(id);
        if (queue != null) queue.clear();
    }

    private boolean isInsidePearlGrace(UUID id, long now) {
        Long until = pearlGraceUntilNanos.get(id);
        if (until == null) return false;
        if (now <= until) return true;
        pearlGraceUntilNanos.remove(id, until);
        return false;
    }

    private boolean isInsideVelocityGrace(UUID id, long now) {
        Long until = velocityGraceUntilNanos.get(id);
        if (until == null) return false;
        if (now <= until) return true;
        velocityGraceUntilNanos.remove(id, until);
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (applyingCorrection.contains(id)) return;
        long graceMillis = Math.max(0L, Math.min(2_000L,
                plugin.getConfig().getLong("checks.no-web.velocity-grace-ms", 500L)));
        velocityGraceUntilNanos.put(id,
                System.nanoTime() + graceMillis * 1_000_000L);
        states.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGlideToggle(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        UUID id = player.getUniqueId();
        long graceMillis = Math.max(0L,
                Math.min(10_000L, plugin.getConfig().getLong("checks.no-web.elytra-grace-ms", 2500L)));
        elytraGraceUntilNanos.put(id, System.nanoTime() + graceMillis * 1_000_000L);
        // Do not let an earlier web violation/anchor carry over into Elytra flight.
        states.remove(id);
    }

    private void decayStates() {
        long now = System.nanoTime();
        Iterator<Map.Entry<UUID, PlayerState>> iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PlayerState> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                iterator.remove();
                continue;
            }

            PlayerState state = entry.getValue();
            // Preserve the previous VL while the player is outside cobwebs.
            // Decay only resumes once the player is back inside the check area.
            boolean inCobweb = intersectsCobweb(player, player.getLocation());
            if (!inCobweb) {
                if (state.violationLevel == 0 && !state.locked) {
                    iterator.remove();
                }
                continue;
            }
            if (state.violationLevel > 0
                    && now - state.lastViolationNanos >= VIOLATION_DECAY_NANOS) {
                state.violationLevel--;
                state.lastViolationNanos = now;
                if (state.violationLevel < 2) {
                    state.locked = false;
                }
            }

            if (state.violationLevel == 0 && !state.locked) {
                iterator.remove();
            }
        }
    }

    /**
     * Cobweb movement is roughly one quarter of normal walking speed, but the
     * player's effective movement attribute still changes with sprinting,
     * Speed effects and server-side attribute modifiers. PlayerMoveEvent can
     * also represent more than one tick after packet delay, so scale the
     * allowance by the observed interval instead of using one fixed packet
     * distance for every player and every connection.
     */
    private double getAllowedMoveDistance(Player player, double elapsedTicks) {
        // The configured value is a per-50-ms baseline, not a per-packet
        // allowance. This keeps the limit consistent across packet batching.
        // 25% of the normal 4.317 blocks/sec walking speed is about 0.054
        // blocks per tick. Clamp legacy config values to a sane vanilla range.
        double configuredPerTick = Math.max(0.052, Math.min(0.075,
                plugin.getConfig().getDouble("checks.no-web.max-move-distance", 0.054)));
        double bufferPerTick = Math.max(0.0, Math.min(0.002,
                plugin.getConfig().getDouble("checks.no-web.movement-buffer", 0.0015)));
        double strictMultiplier = Math.max(1.0, Math.min(1.08,
                plugin.getConfig().getDouble("checks.no-web.speed-multiplier", 1.035)));

        double attributeMultiplier = 1.0;
        AttributeInstance movementSpeed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (movementSpeed != null && movementSpeed.getBaseValue() > 0.0) {
            attributeMultiplier = movementSpeed.getValue() / movementSpeed.getBaseValue();
        }

        double potionMultiplier = 1.0;
        PotionEffect speed = player.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null) {
            potionMultiplier *= 1.0 + 0.2 * (speed.getAmplifier() + 1);
        }
        PotionEffect slowness = player.getPotionEffect(PotionEffectType.SLOWNESS);
        if (slowness != null) {
            potionMultiplier *= Math.max(0.05, 1.0 - 0.15 * (slowness.getAmplifier() + 1));
        }
        double sprintMultiplier = player.isSprinting() ? 1.3 : 1.0;
        double speedMultiplier = Math.max(attributeMultiplier, potionMultiplier * sprintMultiplier);
        speedMultiplier = Math.max(0.1, Math.min(4.0, speedMultiplier));

        double walkMultiplier = Math.max(0.1, Math.min(5.0, player.getWalkSpeed() / 0.2));
        var boots = player.getInventory().getBoots();
        int swiftSneak = boots == null ? 0 : boots.getEnchantmentLevel(Enchantment.SWIFT_SNEAK);
        double stanceMultiplier = player.isSneaking()
                ? Math.min(0.75, 0.30 + 0.15 * swiftSneak) : 1.0;
        // In vanilla, a cobweb permits roughly 25% of normal movement speed;
        // the Weaving effect raises this to about 50%. Read the effect by key
        // so the check remains compatible with API versions around its addition.
        double webMultiplier = 1.0;
        for (PotionEffect effect : player.getActivePotionEffects()) {
            if ("weaving".equals(effect.getType().getKey().getKey())) {
                webMultiplier = 2.0;
                break;
            }
        }

        // Scale the tolerance with stance too; otherwise blocking/sneaking
        // would inherit the full standing-player packet buffer.
        double stanceBufferMultiplier = Math.max(0.20, stanceMultiplier);
        return ((configuredPerTick * speedMultiplier * walkMultiplier * stanceMultiplier
                * webMultiplier * strictMultiplier)
                + bufferPerTick * stanceBufferMultiplier) * elapsedTicks;
    }

    private void returnToAnchor(Player player, Location requested, PlayerState state) {
        Location correction = state.anchor.clone();
        if (requested != null) {
            correction.setYaw(requested.getYaw());
            correction.setPitch(requested.getPitch());
        }
        UUID id = player.getUniqueId();
        applyingCorrection.add(id);
        try {
            player.teleport(correction,
                    PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            applyingCorrection.remove(id);
        }
        lastPacketLocations.put(id, correction.clone());
        correctionThisSample.add(id);
    }

    private void countViolation(Player player, PlayerState state) {
        // A movement packet can trigger more than one event in a tick. Count
        // at most once per tick, while still checking and correcting every move.
        if (state.lastViolationTick == serverTick) return;
        state.violationLevel++;
        state.lastViolationTick = serverTick;
        state.lastViolationNanos = System.nanoTime();
        plugin.getAlertManager().send("NoWeb", player, state.violationLevel,
                "Player displacement exceeded the allowed movement while intersecting cobweb.");
    }

    private boolean intersectsCobweb(Player player, Location location) {
        World world = location.getWorld();
        if (world == null) return false;

        // Inspect every block overlapped by the player's current pose-aware
        // bounding box, shifted to the packet position.
        // The epsilon excludes blocks that only touch the box at an exact edge.
        BoundingBox box = player.getBoundingBox();
        Location current = player.getLocation();
        double offsetX = location.getX() - current.getX();
        double offsetY = location.getY() - current.getY();
        double offsetZ = location.getZ() - current.getZ();
        double epsilon = 1.0E-7;
        int minX = floor(box.getMinX() + offsetX + epsilon);
        int maxX = floor(box.getMaxX() + offsetX - epsilon);
        int minY = floor(box.getMinY() + offsetY + epsilon);
        int maxY = floor(box.getMaxY() + offsetY - epsilon);
        int minZ = floor(box.getMinZ() + offsetZ + epsilon);
        int maxZ = floor(box.getMaxZ() + offsetZ - epsilon);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (world.getBlockAt(x, y, z).getType() == Material.COBWEB) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean overlapsAlternativePhysicsBlock(Player player, Location location) {
        World world = location.getWorld();
        if (world == null) return false;

        // Use Bukkit's pose-aware hitbox and shift it to the packet position.
        // The epsilon excludes blocks that only touch the box at an exact edge.
        BoundingBox box = player.getBoundingBox();
        Location current = player.getLocation();
        double offsetX = location.getX() - current.getX();
        double offsetY = location.getY() - current.getY();
        double offsetZ = location.getZ() - current.getZ();
        double epsilon = 1.0E-7;
        int minX = floor(box.getMinX() + offsetX + epsilon);
        int maxX = floor(box.getMaxX() + offsetX - epsilon);
        int minY = floor(box.getMinY() + offsetY + epsilon);
        int maxY = floor(box.getMaxY() + offsetY - epsilon);
        int minZ = floor(box.getMinZ() + offsetZ + epsilon);
        int maxZ = floor(box.getMaxZ() + offsetZ - epsilon);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Material type = world.getBlockAt(x, y, z).getType();
                    String name = type.name();
                    if (type == Material.POWDER_SNOW
                            || type == Material.SWEET_BERRY_BUSH
                            || type == Material.SCAFFOLDING
                            || type == Material.BUBBLE_COLUMN
                            || type == Material.WATER || type == Material.LAVA
                            || name.contains("LADDER") || name.contains("VINE")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private int floor(double value) {
        return (int) Math.floor(value);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        states.remove(id);
        elytraGraceUntilNanos.remove(id);
        pearlGraceUntilNanos.remove(id);
        velocityGraceUntilNanos.remove(id);
        pearlBaselinePending.remove(id);
        lastPacketLocations.remove(id);
        packetPositions.remove(id);
    }

    public void clearAll() {
        states.clear();
        elytraGraceUntilNanos.clear();
        pearlGraceUntilNanos.clear();
        velocityGraceUntilNanos.clear();
        pearlBaselinePending.clear();
        lastPacketLocations.clear();
        packetPositions.clear();
        applyingCorrection.clear();
        correctionThisSample.clear();
    }

    private record PacketPosition(double x, double y, double z, long atNanos) { }

    private static final class PlayerState {
        private Location lastSafe;
        private Location anchor;
        private int violationLevel;
        private long lastViolationTick = Long.MIN_VALUE;
        private long lastViolationNanos;
        private long lastMovementNanos;
        private int normalMovementSamples;
        private boolean locked;

        private PlayerState(Location initialLocation, long initialMovementNanos) {
            this.lastSafe = initialLocation.clone();
            this.lastMovementNanos = initialMovementNanos;
        }
    }
}
