package dev.keenac.check;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Collects movement context and aggregates externally supplied motion-suspicion
 * samples. At the configured threshold it returns the player to the latest
 * jump origin and prevents positional movement for a short review period.
 */
public final class MotionCheck implements Listener {
    private final KeenAC plugin;
    private final Map<UUID, Deque<Long>> suspicionTimes = new HashMap<>();
    private final Map<UUID, Long> reviewUntil = new HashMap<>();
    private final Map<UUID, Long> lastGlideChangeAt = new HashMap<>();
    private final Map<UUID, Location> lastJumpOrigin = new HashMap<>();
    private final Map<UUID, Long> lastJumpAt = new HashMap<>();
    private final Map<UUID, Long> frozenUntil = new HashMap<>();
    private final Map<UUID, Location> frozenPosition = new HashMap<>();
    private final Map<UUID, Location> lastSafePosition = new HashMap<>();
    private final Map<UUID, Long> lastSafeAt = new HashMap<>();
    private final Map<UUID, ElytraPacketState> elytraPacketStates = new ConcurrentHashMap<>();
    private final Set<UUID> elytraGlidingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> elytraPing = new ConcurrentHashMap<>();
    private volatile boolean elytraPacketEnabled = true;
    private volatile int elytraPacketMaxPing = 250;
    private volatile double elytraPacketMaxBps = 45.1;
    private volatile int elytraPacketConfirmSamples = 2;
    private final Map<UUID, Integer> elytraViolationLevels = new HashMap<>();
    private final Map<UUID, Location> samplePositions = new HashMap<>();
    private final Map<UUID, Integer> hoveringTicks = new HashMap<>();
    private final Map<UUID, MotionPacketProfile> motionPacketProfiles = new ConcurrentHashMap<>();
    private final Map<UUID, MotionPacketState> motionPacketStates = new ConcurrentHashMap<>();
    private volatile double motionSpeedMultiplier = 1.06;
    private volatile double motionSpeedBufferBps = 0.12;
    private volatile int motionSpeedConfirmSamples = 3;
    private volatile long motionSpeedWindowNanos = 500_000_000L;
    private volatile long motionSpeedMinimumWindowNanos = 350_000_000L;
    private volatile int motionPacketMaxPing = 250;
    private volatile long motionPacketCooldownNanos = 400_000_000L;
    private volatile long motionHoverConfirmNanos = 600_000_000L;
    private final Map<UUID, Integer> violationLevels = new HashMap<>();
    private final Map<UUID, Long> lastSignalTick = new HashMap<>();
    private final Set<UUID> pendingSetbacks = new HashSet<>();
    private final Set<UUID> pendingElytraSetbacks = new HashSet<>();
    private final Set<UUID> applyingSetbacks = new HashSet<>();
    private long serverTick;

    public MotionCheck(KeenAC plugin) {
        this.plugin = plugin;
    }

    /**
     * Called once per server tick. This is the missing input source for Motion:
     * it samples real server-side positions and submits sustained-hover
     * anomalies to the rolling suspicion aggregator.
     */
    public void tick() {
        serverTick++;
        boolean motionEnabled = plugin.getConfig().getBoolean("checks.motion.enabled", true);
        if (!motionEnabled) {
            clearMovementSamples();
        }

        refreshElytraPacketSettings();
        refreshMotionPacketSettings();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (isFrozen(id, System.currentTimeMillis())) {
                player.setGliding(false);
                player.setVelocity(new Vector(0.0, 0.0, 0.0));
            } else {
                recordSafeGroundPosition(player);
            }
            refreshElytraPacketEligibility(player);
            refreshMotionPacketProfile(player, motionEnabled);
        }

        Set<UUID> online = Bukkit.getOnlinePlayers().stream()
                .map(Player::getUniqueId)
                .collect(Collectors.toSet());
        samplePositions.keySet().removeIf(id -> !online.contains(id));
        hoveringTicks.keySet().removeIf(id -> !online.contains(id));
        elytraPacketStates.keySet().removeIf(id -> !online.contains(id));
        elytraGlidingPlayers.retainAll(online);
        elytraPing.keySet().removeIf(id -> !online.contains(id));
        motionPacketProfiles.keySet().removeIf(id -> !online.contains(id));
        motionPacketStates.keySet().removeIf(id -> !online.contains(id));
        lastSafePosition.keySet().removeIf(id -> !online.contains(id));
        lastSafeAt.keySet().removeIf(id -> !online.contains(id));
        lastGlideChangeAt.keySet().removeIf(id -> !online.contains(id));
    }

    private void recordSafeGroundPosition(Player player) {
        if (!player.isOnGround() || player.isInsideVehicle() || player.isDead()
                || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        // Do not move the rollback anchor forward while Motion evidence is
        // accumulating or a setback is waiting to run.
        if (pendingSetbacks.contains(id)
                || reviewUntil.getOrDefault(id, 0L) > now
                || hasRecentMotionEvidence(id, now)) {
            return;
        }
        lastSafePosition.put(id, player.getLocation().clone());
        lastSafeAt.put(id, now);
        plugin.getMovementSetbackManager().recordValidPosition(player, player.getLocation());
    }

    private boolean hasRecentMotionEvidence(UUID id, long now) {
        Deque<Long> samples = suspicionTimes.get(id);
        if (samples == null) {
            return false;
        }
        long window = Math.max(1L,
                plugin.getConfig().getLong("checks.motion.suspicion-window-ms", 3000L));
        while (!samples.isEmpty() && now - samples.peekFirst() > window) {
            samples.removeFirst();
        }
        if (samples.isEmpty()) {
            suspicionTimes.remove(id);
            return false;
        }
        return true;
    }

    /**
     * Refresh Bukkit-only player state on the main thread. Incoming position packets
     * are measured directly by PacketEvents using this lightweight eligibility cache.
     */
    private void refreshElytraPacketSettings() {
        elytraPacketEnabled = plugin.getConfig().getBoolean("checks.elytra-speed.enabled", true);
        elytraPacketMaxPing = Math.max(50,
                plugin.getConfig().getInt("checks.elytra-speed.max-ping-ms", 250));
        // 45.1 BPS is the requested hard ceiling, including when an older
        // server-side config.yml still contains the previous 85.0 default.
        elytraPacketMaxBps = Math.min(45.1, Math.max(1.0,
                plugin.getConfig().getDouble("checks.elytra-speed.max-bps", 45.1)));
        elytraPacketConfirmSamples = Math.max(1,
                plugin.getConfig().getInt("checks.elytra-speed.confirm-samples", 2));
    }

    private void refreshElytraPacketEligibility(Player player) {
        UUID id = player.getUniqueId();
        boolean eligible = elytraPacketEnabled
                && player.isGliding() && player.isOnline() && player.isValid()
                && !player.isInsideVehicle()
                && player.getGameMode() != GameMode.CREATIVE
                && player.getGameMode() != GameMode.SPECTATOR;
        if (!eligible) {
            elytraGlidingPlayers.remove(id);
            elytraPacketStates.remove(id);
            elytraPing.remove(id);
            return;
        }
        elytraGlidingPlayers.add(id);
        elytraPing.put(id, player.getPing());
    }

    /**
     * Called by PacketAirStuckListener for every incoming position-bearing movement
     * packet. Only primitive packet values and concurrent state are accessed here.
     */
    public void recordElytraPacketPosition(UUID id, double x, double y, double z) {
        if (!elytraPacketEnabled || !elytraGlidingPlayers.contains(id)) {
            return;
        }

        if (elytraPing.getOrDefault(id, elytraPacketMaxPing + 1) > elytraPacketMaxPing) {
            elytraPacketStates.remove(id);
            return;
        }

        long nowNanos = System.nanoTime();
        double threshold = elytraPacketMaxBps;
        int required = elytraPacketConfirmSamples;

        ElytraPacketState state = elytraPacketStates.computeIfAbsent(
                id, ignored -> new ElytraPacketState());
        double measuredBps = 0.0;
        boolean violation = false;
        synchronized (state) {
            if (state.positions.isEmpty()) {
                state.positions.addLast(new ElytraPositionSample(x, y, z, nowNanos));
                state.lastPacketNanos = nowNanos;
                return;
            }

            long packetGapNanos = nowNanos - state.lastPacketNanos;
            if (packetGapNanos <= 0L) {
                return;
            }
            if (packetGapNanos > 750_000_000L) {
                // A packet stall is not evidence of speed. Start a new clean window.
                state.positions.clear();
                state.positions.addLast(new ElytraPositionSample(x, y, z, nowNanos));
                state.lastPacketNanos = nowNanos;
                state.fastSamples = 0;
                return;
            }

            state.positions.addLast(new ElytraPositionSample(x, y, z, nowNanos));
            state.lastPacketNanos = nowNanos;

            // Keep the sample immediately before the 400 ms cutoff, then compare
            // it with the newest position. This averages out packet jitter/bursts
            // instead of treating one delayed packet interval as the player's speed.
            long cutoffNanos = nowNanos - 400_000_000L;
            while (state.positions.size() > 2) {
                java.util.Iterator<ElytraPositionSample> iterator = state.positions.iterator();
                iterator.next();
                ElytraPositionSample second = iterator.next();
                if (second.timestampNanos <= cutoffNanos) {
                    state.positions.removeFirst();
                } else {
                    break;
                }
            }

            ElytraPositionSample first = state.positions.peekFirst();
            ElytraPositionSample last = state.positions.peekLast();
            long windowNanos = last.timestampNanos - first.timestampNanos;
            if (windowNanos < 300_000_000L) {
                return;
            }

            double dx = last.x - first.x;
            double dy = last.y - first.y;
            double dz = last.z - first.z;
            measuredBps = Math.sqrt(dx * dx + dy * dy + dz * dz)
                    / (windowNanos / 1_000_000_000.0);

            if (measuredBps > threshold) {
                state.fastSamples++;
            } else {
                state.fastSamples = 0;
            }

            if (state.fastSamples >= required) {
                state.fastSamples = 0;
                violation = true;
            }
        }

        if (violation) {
            final double speed = measuredBps;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(id);
                if (player != null && player.isOnline()) {
                    recordElytraSpeedViolation(player, speed, threshold);
                }
            });
        }
    }

    private record ElytraPositionSample(double x, double y, double z, long timestampNanos) { }

    private static final class ElytraPacketState {
        private final Deque<ElytraPositionSample> positions = new ArrayDeque<>();
        private long lastPacketNanos;
        private int fastSamples;
    }

    private void recordElytraSpeedViolation(Player player, double bps, double threshold) {
        UUID id = player.getUniqueId();
        int vl = elytraViolationLevels.merge(id, 1, (current, one) ->
                current == Integer.MAX_VALUE ? Integer.MAX_VALUE : current + one);
        plugin.getAlertManager().send("ElytraSpeed", player, vl,
                String.format(java.util.Locale.ROOT,
                        "Average Elytra speed %.2f blocks/sec exceeded the configured limit %.2f.",
                        bps, threshold));
        if (plugin.getConfig().getBoolean("checks.elytra-speed.log-context", true)) {
            plugin.getLogger().warning(String.format(java.util.Locale.ROOT,
                    "ElytraSpeed review for %s (VL=%d, speed=%.2f BPS, limit=%.2f BPS)",
                    player.getName(), vl, bps, threshold));
        }

        if (plugin.getConfig().getBoolean("checks.elytra-speed.setback.enabled", true)) {
            applyElytraSpeedSetback(player);
        }
    }

    private void applyElytraSpeedSetback(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            UUID id = player.getUniqueId();
            if (pendingElytraSetbacks.add(id)) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    pendingElytraSetbacks.remove(id);
                    Player online = Bukkit.getPlayer(id);
                    if (online != null && online.isOnline()) {
                        applyElytraSpeedSetback(online);
                    }
                });
            }
            return;
        }

        UUID id = player.getUniqueId();
        player.setGliding(false);

        Location correction = lastSafePosition.get(id);
        long safeAt = lastSafeAt.getOrDefault(id, 0L);
        long maxAge = Math.max(1000L,
                plugin.getConfig().getLong("checks.elytra-speed.setback.safe-position-max-age-ms", 30000L));
        if (correction == null || correction.getWorld() != player.getWorld()
                || System.currentTimeMillis() - safeAt > maxAge) {
            correction = player.getLocation().clone();
        } else {
            correction = correction.clone();
            Location current = player.getLocation();
            correction.setYaw(current.getYaw());
            correction.setPitch(current.getPitch());
        }

        boolean applied = true;
        if (correction.getWorld() == player.getWorld()
                && correction.distanceSquared(player.getLocation()) > 0.0001) {
            applyingSetbacks.add(id);
            try {
                applied = player.teleport(correction, PlayerTeleportEvent.TeleportCause.PLUGIN);
            } finally {
                applyingSetbacks.remove(id);
            }
        }

        long freezeDuration = Math.max(500L,
                plugin.getConfig().getLong("checks.elytra-speed.setback.freeze-duration-ms", 2500L));
        frozenUntil.put(id, System.currentTimeMillis() + freezeDuration);
        frozenPosition.put(id, player.getLocation().clone());
        player.setGliding(false);
        player.setVelocity(new Vector(0.0, 0.0, 0.0));
        elytraPacketStates.remove(id);
        elytraGlidingPlayers.remove(id);
        elytraPing.remove(id);
        if (!applied) {
            plugin.getLogger().fine("ElytraSpeed rollback teleport was rejected for "
                    + player.getName() + "; glide disabled and movement frozen at current position.");
        }
    }

    /**
     * Cache Bukkit-only movement context on the main thread. Packet callbacks
     * consume this immutable profile and never query Bukkit world/player state.
     */
    private void refreshMotionPacketSettings() {
        // Clamp legacy config values too: an older config.yml may still carry
        // the previous, much more permissive 1.30 / 0.55 defaults.
        // Keep only a small margin above the estimated vanilla speed. Packet
        // samples are averaged over a window, so large legacy buffers are unsafe.
        motionSpeedMultiplier = Math.max(1.0,
                Math.min(1.04, plugin.getConfig().getDouble("checks.motion.speed.multiplier", 1.025)));
        motionSpeedBufferBps = Math.max(0.0,
                Math.min(0.06, plugin.getConfig().getDouble("checks.motion.speed.buffer-bps", 0.04)));
        motionSpeedConfirmSamples = Math.max(2,
                plugin.getConfig().getInt("checks.motion.speed.confirm-samples", 3));
        int sampleWindowMs = Math.max(350,
                Math.min(1_000, plugin.getConfig().getInt("checks.motion.speed.sample-window-ms", 500)));
        motionSpeedWindowNanos = sampleWindowMs * 1_000_000L;
        motionSpeedMinimumWindowNanos = Math.max(250_000_000L,
                motionSpeedWindowNanos * 2L / 3L);
        motionPacketMaxPing = Math.max(50,
                plugin.getConfig().getInt("checks.motion.max-ping-ms", 250));
        motionPacketCooldownNanos = Math.max(200L,
                plugin.getConfig().getLong("checks.motion.packet-signal-interval-ms", 400L))
                * 1_000_000L;
        int hoverTicks = Math.max(8,
                plugin.getConfig().getInt("checks.motion.hover.confirm-ticks", 12));
        motionHoverConfirmNanos = hoverTicks * 50_000_000L;
    }

    private void refreshMotionPacketProfile(Player player, boolean enabled) {
        UUID id = player.getUniqueId();
        Location location = player.getLocation();
        long now = System.currentTimeMillis();
        long velocityGrace = Math.max(0L,
                plugin.getConfig().getLong("checks.motion.velocity-grace-ms", 1200L));
        boolean recentVelocity = plugin.getPlayerContextManager()
                .isWithinVelocityGrace(id, System.nanoTime(), velocityGrace);

        boolean unsupportedSurface = isUnsupportedMovementSurface(location)
                || isUnsupportedMovementSurface(location.clone().subtract(0.0, 0.7, 0.0));
        double surfaceMultiplier = getSurfaceMovementMultiplier(player, location);
        boolean movementModeEligible = player.isOnline() && player.isValid()
                && !player.isInsideVehicle() && !player.isFlying() && !player.isGliding()
                && !player.isSwimming() && !player.isRiptiding()
                && !player.isInWater()
                && player.getGameMode() != GameMode.CREATIVE
                && player.getGameMode() != GameMode.SPECTATOR
                && player.getOpenInventory().getType() == InventoryType.CRAFTING
                && !player.hasPotionEffect(PotionEffectType.LEVITATION)
                && !player.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                && !unsupportedSurface && !recentVelocity;

        AttributeInstance movementAttribute = player.getAttribute(Attribute.MOVEMENT_SPEED);
        double base = movementAttribute == null ? 0.1 : Math.max(0.001, movementAttribute.getBaseValue());
        double value = movementAttribute == null ? base : movementAttribute.getValue();
        double attributeMultiplier = Math.max(0.5, Math.min(8.0, value / base));
        PotionEffect speedEffect = player.getPotionEffect(PotionEffectType.SPEED);
        double potionMultiplier = speedEffect == null ? 1.0
                : 1.0 + 0.2 * (speedEffect.getAmplifier() + 1);
        PotionEffect slownessEffect = player.getPotionEffect(PotionEffectType.SLOWNESS);
        if (slownessEffect != null) {
            potionMultiplier *= Math.max(0.05,
                    1.0 - 0.15 * (slownessEffect.getAmplifier() + 1));
        }
        double sprintMultiplier = player.isSprinting() ? 1.3 : 1.0;
        // The attribute value normally contains potion/sprint modifiers already.
        // The explicit estimate is a fallback for server implementations that do not.
        double movementMultiplier = Math.max(attributeMultiplier, potionMultiplier * sprintMultiplier);
        double walkMultiplier = Math.max(0.1, Math.min(5.0, player.getWalkSpeed() / 0.2));
        var boots = player.getInventory().getBoots();
        int swiftSneak = boots == null ? 0 : boots.getEnchantmentLevel(Enchantment.SWIFT_SNEAK);
        double stanceMultiplier = player.isSneaking()
                ? Math.min(0.75, 0.30 + 0.15 * swiftSneak) : 1.0;
        if (player.isBlocking()) {
            stanceMultiplier *= 0.20;
        }
        long jumpAt = lastJumpAt.getOrDefault(id, 0L);
        boolean recentJump = jumpAt > 0L && now - jumpAt <= 600L;
        // Sprint-jump gives a legitimate short horizontal impulse even without
        // a PlayerVelocityEvent. Give only that short window extra headroom.
        double airborneMultiplier = player.isOnGround() ? 1.0 : recentJump ? 1.22 : 1.08;
        double allowedHorizontalBps = 4.317 * movementMultiplier * walkMultiplier
                * stanceMultiplier * surfaceMultiplier * airborneMultiplier;

        boolean hoverEligible = movementModeEligible && !player.isOnGround()
                && !player.hasPotionEffect(PotionEffectType.JUMP_BOOST);
        motionPacketProfiles.put(id, new MotionPacketProfile(
                enabled && movementModeEligible,
                hoverEligible,
                player.getPing(),
                allowedHorizontalBps,
                player.getVelocity().getY()));
    }

    /**
     * Receives absolute client position samples from PacketEvents. Horizontal
     * speed covers both direct Speed movement and accelerated strafe/bunny-hop
     * movement; sustained near-zero vertical displacement remains the Hover signal.
     */
    public void recordPacketMovement(UUID id, double x, double y, double z, boolean packetOnGround) {
        MotionPacketProfile profile = motionPacketProfiles.get(id);
        if (profile == null || !profile.eligible || profile.ping > motionPacketMaxPing) {
            motionPacketStates.remove(id);
            return;
        }

        long nowNanos = System.nanoTime();
        MotionPacketState state = motionPacketStates.computeIfAbsent(
                id, ignored -> new MotionPacketState());
        String reason = null;
        synchronized (state) {
            if (state.lastPacketNanos == 0L || nowNanos - state.lastPacketNanos > 750_000_000L) {
                state.reset(x, y, z, nowNanos);
                return;
            }

            if (nowNanos - state.lastPacketNanos <= 0L) {
                return;
            }
            state.positions.addLast(new MotionPositionSample(x, y, z, nowNanos));
            state.lastPacketNanos = nowNanos;

            long cutoff = nowNanos - motionSpeedWindowNanos;
            while (state.positions.size() > 2) {
                java.util.Iterator<MotionPositionSample> iterator = state.positions.iterator();
                iterator.next();
                MotionPositionSample second = iterator.next();
                if (second.timestampNanos <= cutoff) {
                    state.positions.removeFirst();
                } else {
                    break;
                }
            }

            MotionPositionSample first = state.positions.peekFirst();
            MotionPositionSample last = state.positions.peekLast();
            long windowNanos = last.timestampNanos - first.timestampNanos;
            if (windowNanos >= motionSpeedMinimumWindowNanos) {
                double dx = last.x - first.x;
                double dz = last.z - first.z;
                double horizontalBps = Math.sqrt(dx * dx + dz * dz)
                        / (windowNanos / 1_000_000_000.0);
                double allowed = profile.allowedHorizontalBps * motionSpeedMultiplier
                        + motionSpeedBufferBps;
                if (horizontalBps > allowed) {
                    state.fastSamples++;
                } else if (horizontalBps < allowed * 0.90) {
                    state.fastSamples = 0;
                } else {
                    state.fastSamples = Math.max(0, state.fastSamples - 1);
                }

                if (state.fastSamples >= motionSpeedConfirmSamples
                        && nowNanos - state.lastSpeedSignalNanos >= motionPacketCooldownNanos) {
                    state.fastSamples = 0;
                    state.lastSpeedSignalNanos = nowNanos;
                    reason = "horizontal-speed-strafe";
                }
            }

            if (reason == null && profile.hoverEligible && !packetOnGround
                    && Math.abs(y - state.lastY) <= 0.015
                    && Math.abs(profile.verticalVelocity) <= 0.035) {
                if (state.hoverSinceNanos == 0L) {
                    state.hoverSinceNanos = nowNanos;
                } else if (nowNanos - state.hoverSinceNanos >= motionHoverConfirmNanos
                        && nowNanos - state.lastHoverSignalNanos >= motionPacketCooldownNanos) {
                    state.lastHoverSignalNanos = nowNanos;
                    reason = "sustained-midair-hover";
                }
            } else {
                state.hoverSinceNanos = 0L;
            }
            state.lastY = y;
        }

        if (reason != null) {
            String signal = reason;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(id);
                if (player != null && player.isOnline()) {
                    recordSuspicion(player, signal);
                }
            });
        }
    }

    private record MotionPacketProfile(
            boolean eligible,
            boolean hoverEligible,
            int ping,
            double allowedHorizontalBps,
            double verticalVelocity) { }

    private record MotionPositionSample(double x, double y, double z, long timestampNanos) { }

    private static final class MotionPacketState {
        private final Deque<MotionPositionSample> positions = new ArrayDeque<>();
        private long lastPacketNanos;
        private long lastSpeedSignalNanos;
        private long lastHoverSignalNanos;
        private long hoverSinceNanos;
        private double lastY;
        private int fastSamples;

        private void reset(double x, double y, double z, long nowNanos) {
            positions.clear();
            positions.addLast(new MotionPositionSample(x, y, z, nowNanos));
            lastPacketNanos = nowNanos;
            hoverSinceNanos = 0L;
            lastY = y;
            fastSamples = 0;
        }
    }

    private boolean isElytraMotionExempt(Player player) {
        if (player.isGliding()) {
            return true;
        }

        // Bukkit can report the glide toggle one tick later than the movement
        // update. Treat airborne players wearing an Elytra as flight movement,
        // even during that transition window.
        var chestplate = player.getInventory().getChestplate();
        if (chestplate != null && chestplate.getType() == Material.ELYTRA
                && !player.isOnGround()) {
            return true;
        }

        long glideGrace = Math.max(0L,
                plugin.getConfig().getLong("checks.motion.glide-grace-ms", 3000L));
        long glideChangedAt = lastGlideChangeAt.getOrDefault(player.getUniqueId(), 0L);
        return glideChangedAt > 0L
                && System.currentTimeMillis() - glideChangedAt <= glideGrace;
    }

    private boolean isUnsupportedMovementSurface(Location location) {
        Material feet = location.getBlock().getType();
        Material below = location.clone().subtract(0.0, 0.2, 0.0).getBlock().getType();
        return isUnsupportedMovementMaterial(feet) || isUnsupportedMovementMaterial(below);
    }

    private boolean isUnsupportedMovementMaterial(Material material) {
        String name = material.name();
        // These modes have dedicated vertical/fluid/climbing physics; they are
        // deliberately excluded instead of being judged by the ground-speed model.
        return material == Material.WATER || material == Material.LAVA
                || material == Material.COBWEB || material == Material.POWDER_SNOW
                || material == Material.SCAFFOLDING || material == Material.BUBBLE_COLUMN
                || name.contains("BERRY_BUSH")
                || name.contains("LADDER") || name.contains("VINE");
    }

    private double getSurfaceMovementMultiplier(Player player, Location location) {
        Material surface = location.clone().subtract(0.0, 0.2, 0.0).getBlock().getType();
        String name = surface.name();
        if (surface == Material.BLUE_ICE) {
            // Slippery blocks preserve horizontal momentum longer than ordinary ground.
            return 1.08;
        }
        if (name.contains("ICE")) {
            return 1.04;
        }
        if (name.contains("SLIME")) {
            // Preserve a modest allowance for momentum and bounce transitions.
            return player.isOnGround() ? 1.02 : 1.14;
        }
        if (name.contains("HONEY")) {
            return 0.58;
        }
        if (surface == Material.SOUL_SAND) {
            var boots = player.getInventory().getBoots();
            int soulSpeed = boots == null ? 0 : boots.getEnchantmentLevel(Enchantment.SOUL_SPEED);
            return soulSpeed > 0 ? 1.0 : 0.58;
        }
        if (surface == Material.SOUL_SOIL) {
            // Soul Soil is not slowed like Soul Sand; Soul Speed's attribute
            // modifier, when present, is already reflected in movementMultiplier.
            return 1.0;
        }
        return 1.0;
    }

    private void inspectHover(Player player, Location previous, Location current) {
        UUID id = player.getUniqueId();
        if (player.isOnGround()
                || player.hasPotionEffect(PotionEffectType.JUMP_BOOST)
                || player.isInsideVehicle()) {
            hoveringTicks.remove(id);
            return;
        }

        double verticalDelta = Math.abs(current.getY() - previous.getY());
        double verticalTolerance = Math.max(0.001,
                plugin.getConfig().getDouble("checks.motion.hover.vertical-tolerance", 0.015));
        double velocityTolerance = Math.max(0.001,
                plugin.getConfig().getDouble("checks.motion.hover.velocity-tolerance", 0.035));
        if (verticalDelta <= verticalTolerance
                && Math.abs(player.getVelocity().getY()) <= velocityTolerance) {
            int ticks = hoveringTicks.getOrDefault(id, 0) + 1;
            hoveringTicks.put(id, ticks);
            int requiredTicks = Math.max(8,
                    plugin.getConfig().getInt("checks.motion.hover.confirm-ticks", 12));
            if (ticks >= requiredTicks) {
                recordSuspicion(player, "sustained-midair-hover");
            }
        } else {
            hoveringTicks.remove(id);
        }
    }

    private void resetMovementSample(UUID id, Location location) {
        samplePositions.put(id, location.clone());
        hoveringTicks.remove(id);
    }

    private void clearMotionEvidence(UUID id) {
        suspicionTimes.remove(id);
        reviewUntil.remove(id);
        hoveringTicks.remove(id);
        lastSignalTick.remove(id);
    }

    private void clearMovementSamples() {
        samplePositions.clear();
        hoveringTicks.clear();
        motionPacketStates.clear();
    }

    /**
     * Submit one movement anomaly. Multiple signals in the same server tick
     * are collapsed into one sample so hover cannot be counted more than once.
     * Every completed review window raises the player's persistent Motion VL.
     */
    public void recordSuspicion(Player player, String reason) {
        if (player == null) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> recordSuspicion(player, reason));
            return;
        }
        if (!player.isOnline()
                || !plugin.getConfig().getBoolean("checks.motion.enabled", true)) {
            return;
        }

        UUID id = player.getUniqueId();
        if (isElytraMotionExempt(player)) {
            clearMotionEvidence(id);
            return;
        }
        long previousSignalTick = lastSignalTick.getOrDefault(id, Long.MIN_VALUE);
        if (previousSignalTick == serverTick) {
            return;
        }
        lastSignalTick.put(id, serverTick);

        long now = System.currentTimeMillis();
        long sampleWindow = Math.max(1L,
                plugin.getConfig().getLong("checks.motion.suspicion-window-ms", 3000L));
        int required = Math.max(1,
                plugin.getConfig().getInt("checks.motion.required-suspicions", 5));

        Deque<Long> samples = suspicionTimes.computeIfAbsent(id, ignored -> new ArrayDeque<>());
        while (!samples.isEmpty() && now - samples.peekFirst() > sampleWindow) {
            samples.removeFirst();
        }
        samples.addLast(now);

        if (samples.size() < required) {
            return;
        }

        // Re-review cadence follows the same configured real-time delay as
        // AlertManager, rather than a separate check-local alert cooldown.
        long reviewWindow = Math.max(0L,
                plugin.getConfig().getLong("alerts.delay-seconds", 3L)) * 1000L;
        long activeUntil = reviewUntil.getOrDefault(id, 0L);
        if (now < activeUntil) {
            return;
        }

        int violationLevel = violationLevels.merge(id, 1, (current, one) ->
                current == Integer.MAX_VALUE ? Integer.MAX_VALUE : current + one);

        // Consume the evidence used for this alert. A continuing violation must
        // collect a fresh set of samples before the next xN alert is emitted.
        samples.clear();
        reviewUntil.put(id, now + reviewWindow);

        plugin.getAlertManager().send("Motion", player, violationLevel,
                "Movement anomaly: " + sanitizeReason(reason));
        if (plugin.getConfig().getBoolean("checks.motion.log-context", true)) {
            plugin.getLogger().info("Motion review for " + player.getName()
                    + " (VL=" + violationLevel + ", reason="
                    + sanitizeReason(reason) + "): "
                    + describeMovementContext(player, now));
        }

        if (plugin.getConfig().getBoolean("checks.motion.setback.enabled", true)) {
            scheduleHardSetback(player);
        }
    }

    /**
     * Diagnostic snapshot for staff review.
     */
    public String describeMovementContext(Player player) {
        return describeMovementContext(player, System.currentTimeMillis());
    }

    private String describeMovementContext(Player player, long now) {
        AttributeInstance movementAttribute = player.getAttribute(Attribute.MOVEMENT_SPEED);
        String movementValue = movementAttribute == null
                ? "unavailable"
                : String.format(java.util.Locale.ROOT, "%.4f", movementAttribute.getValue());

        String effects = player.getActivePotionEffects().stream()
                .sorted(Comparator.comparing(effect -> effect.getType().getKey().toString()))
                .map(this::formatEffect)
                .collect(Collectors.joining(","));
        if (effects.isEmpty()) {
            effects = "none";
        }

        long velocityGrace = Math.max(0L,
                plugin.getConfig().getLong("checks.motion.velocity-grace-ms", 1200L));
        boolean recentVelocity = plugin.getPlayerContextManager()
                .isWithinVelocityGrace(player.getUniqueId(), System.nanoTime(), velocityGrace);
        Vector velocity = player.getVelocity();

        return "movementAttribute=" + movementValue
                + ", walkSpeed=" + String.format(java.util.Locale.ROOT, "%.3f", player.getWalkSpeed())
                + ", flySpeed=" + String.format(java.util.Locale.ROOT, "%.3f", player.getFlySpeed())
                + ", effects=[" + effects + "]"
                + ", velocity=" + String.format(java.util.Locale.ROOT, "(%.3f,%.3f,%.3f)",
                        velocity.getX(), velocity.getY(), velocity.getZ())
                + ", recentVelocityEvent=" + recentVelocity
                + ", onGround=" + player.isOnGround()
                + ", sprinting=" + player.isSprinting()
                + ", flying=" + player.isFlying()
                + ", gliding=" + player.isGliding()
                + ", swimming=" + player.isSwimming()
                + ", riptiding=" + player.isRiptiding()
                + ", inVehicle=" + player.isInsideVehicle()
                + ", gameMode=" + player.getGameMode()
                + ", fallDistance=" + String.format(java.util.Locale.ROOT, "%.2f", player.getFallDistance());
    }

    private String formatEffect(PotionEffect effect) {
        return effect.getType().getKey().getKey() + ":" + (effect.getAmplifier() + 1);
    }

    private String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unspecified";
        }
        String safe = reason.replace('\n', ' ').replace('\r', ' ').trim();
        return safe.length() > 120 ? safe.substring(0, 120) : safe;
    }

    /**
     * Paper reports the exact location the player jumped from. Preserve it
     * until the next jump or until the player's movement context is reset.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onJump(PlayerJumpEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (isFrozen(id, System.currentTimeMillis())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordJumpOrigin(PlayerJumpEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastJumpOrigin.put(id, event.getFrom().clone());
        lastJumpAt.put(id, System.currentTimeMillis());
    }

    /**
     * During the freeze, reject every positional delta, including sub-block
     * movement. Rotation is retained so the player can still look around.
     * Teleports are handled separately and are not treated as ordinary moves.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void enforceFreeze(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) {
            return;
        }

        UUID id = event.getPlayer().getUniqueId();
        if (!isFrozen(id, System.currentTimeMillis())) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()) {
            return;
        }

        Location locked = frozenPosition.getOrDefault(id, from).clone();
        if (locked.getWorld() != to.getWorld()) {
            locked = from.clone();
        }
        locked.setYaw(to.getYaw());
        locked.setPitch(to.getPitch());
        if (Double.compare(to.getX(), locked.getX()) != 0
                || Double.compare(to.getY(), locked.getY()) != 0
                || Double.compare(to.getZ(), locked.getZ()) != 0) {
            event.setTo(locked);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void resetMotionOnGlideChange(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        UUID id = player.getUniqueId();
        lastGlideChangeAt.put(id, System.currentTimeMillis());
        clearMotionEvidence(id);
        samplePositions.remove(id);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void preventGlidingWhileFrozen(EntityToggleGlideEvent event) {
        // Allow the player to stop gliding, but never allow a new glide to start
        // while a hard setback freeze is active.
        if (event.getEntity() instanceof Player player && event.isGliding()
                && isFrozen(player.getUniqueId(), System.currentTimeMillis())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void preventTeleportWhileFrozen(PlayerTeleportEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (applyingSetbacks.contains(id)) {
            return;
        }
        if (isFrozen(id, System.currentTimeMillis())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void preventVehicleEntryWhileFrozen(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player
                && isFrozen(player.getUniqueId(), System.currentTimeMillis())) {
            event.setCancelled(true);
        }
    }

    private boolean isFrozen(UUID id, long now) {
        Long until = frozenUntil.get(id);
        if (until == null) {
            return false;
        }
        if (now >= until) {
            frozenUntil.remove(id, until);
            frozenPosition.remove(id);
            return false;
        }
        return true;
    }

    private void scheduleHardSetback(Player player) {
        UUID id = player.getUniqueId();
        if (!pendingSetbacks.add(id)) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            pendingSetbacks.remove(id);
            Player online = Bukkit.getPlayer(id);
            if (online == null || !online.isOnline()
                    || !plugin.getConfig().getBoolean("checks.motion.enabled", true)
                    || !plugin.getConfig().getBoolean("checks.motion.setback.enabled", true)) {
                return;
            }

            // Do not fight expected server-side movement modes.
            if (isElytraMotionExempt(online)
                    || online.isInsideVehicle() || online.isFlying() || online.isGliding()
                    || online.isSwimming() || online.isRiptiding()
                    || online.getGameMode() == org.bukkit.GameMode.CREATIVE
                    || online.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                return;
            }

            long now = System.currentTimeMillis();
            long velocityGrace = Math.max(0L,
                    plugin.getConfig().getLong("checks.motion.velocity-grace-ms", 1200L));
            if (plugin.getPlayerContextManager()
                    .isWithinVelocityGrace(id, System.nanoTime(), velocityGrace)) {
                return;
            }

            Location correction = lastJumpOrigin.get(id);
            long jumpAt = lastJumpAt.getOrDefault(id, 0L);
            long jumpMaxAge = Math.max(1L,
                    plugin.getConfig().getLong("checks.motion.setback.jump-origin-max-age-ms", 3000L));
            if (correction == null || now - jumpAt > jumpMaxAge
                    || correction.getWorld() == null
                    || correction.getWorld() != online.getWorld()) {
                correction = lastSafePosition.get(id);
                long safeAt = lastSafeAt.getOrDefault(id, 0L);
                long safeMaxAge = Math.max(1000L,
                        plugin.getConfig().getLong("checks.motion.setback.safe-position-max-age-ms", 15000L));
                if (correction == null || correction.getWorld() != online.getWorld()
                        || now - safeAt > safeMaxAge) {
                    return;
                }
            }

            correction = correction.clone();
            Location current = online.getLocation();
            correction.setYaw(current.getYaw());
            correction.setPitch(current.getPitch());

            applyingSetbacks.add(id);
            boolean applied;
            try {
                applied = plugin.getMovementSetbackManager().setback(online, correction, "Motion");
            } finally {
                applyingSetbacks.remove(id);
            }

            if (applied) {
                // The shared manager performs a synchronous position correction,
                // zeros velocity and briefly rejects further positional deltas.
                lastJumpOrigin.remove(id);
                lastJumpAt.remove(id);
                lastSafePosition.put(id, online.getLocation().clone());
                lastSafeAt.put(id, System.currentTimeMillis());
                plugin.getMovementSetbackManager().recordValidPosition(online, online.getLocation());
                motionPacketStates.remove(id);
                samplePositions.remove(id);
            } else {
                plugin.getLogger().fine("Motion rubberband could not be applied to "
                        + online.getName() + ".");
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onVelocity(PlayerVelocityEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (plugin.getMovementSetbackManager().isCorrectionLocked(id)) {
            event.setVelocity(new Vector(0.0, 0.0, 0.0));
            return;
        }
        if (isFrozen(id, System.currentTimeMillis())) {
            event.setVelocity(new Vector(0.0, 0.0, 0.0));
            return;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (applyingSetbacks.contains(id)) {
            return;
        }
        clear(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    public void clear(UUID id) {
        suspicionTimes.remove(id);
        reviewUntil.remove(id);
        lastGlideChangeAt.remove(id);
        lastJumpOrigin.remove(id);
        lastJumpAt.remove(id);
        frozenUntil.remove(id);
        frozenPosition.remove(id);
        lastSafePosition.remove(id);
        lastSafeAt.remove(id);
        elytraPacketStates.remove(id);
        elytraGlidingPlayers.remove(id);
        elytraPing.remove(id);
        elytraViolationLevels.remove(id);
        pendingSetbacks.remove(id);
        pendingElytraSetbacks.remove(id);
        applyingSetbacks.remove(id);
        samplePositions.remove(id);
        hoveringTicks.remove(id);
        motionPacketProfiles.remove(id);
        motionPacketStates.remove(id);
        violationLevels.remove(id);
        lastSignalTick.remove(id);
    }

    public void clearAll() {
        suspicionTimes.clear();
        reviewUntil.clear();
        lastGlideChangeAt.clear();
        lastJumpOrigin.clear();
        lastJumpAt.clear();
        frozenUntil.clear();
        frozenPosition.clear();
        lastSafePosition.clear();
        lastSafeAt.clear();
        elytraPacketStates.clear();
        elytraGlidingPlayers.clear();
        elytraPing.clear();
        elytraViolationLevels.clear();
        pendingSetbacks.clear();
        pendingElytraSetbacks.clear();
        applyingSetbacks.clear();
        violationLevels.clear();
        lastSignalTick.clear();
        motionPacketProfiles.clear();
        motionPacketStates.clear();
        clearMovementSamples();
    }
}
