package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conservative, alert-only movement-pattern check. It does not identify a
 * client by name; it looks for repeated, unusually consistent ground-navigation
 * segments observed in incoming PacketEvents movement packets.
 *
 * Packet callbacks read only cached eligibility and primitive packet values.
 * Bukkit player/world state is refreshed on the server thread by tick().
 */
public final class BaritoneCheck implements Listener {
    private final KeenAC plugin;
    private final Map<UUID, PacketProfile> profiles = new ConcurrentHashMap<>();
    private final Map<UUID, PacketState> states = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastVelocityNanos = new ConcurrentHashMap<>();

    private volatile boolean enabled = true;
    private volatile int maxPingMs = 220;
    private volatile long observationWindowNanos = 45_000_000_000L;
    private volatile int patternSegments = 4;
    private volatile int requiredRepeats = 3;
    private volatile double angleToleranceDegrees = 10.0;
    private volatile double segmentDistanceTolerance = 0.40;
    private volatile double minimumTurnDegrees = 25.0;
    private volatile double maximumTurnDegrees = 155.0;
    private volatile double minimumSegmentDistance = 1.0;
    private volatile double maximumSegmentDistance = 12.0;
    private volatile double yawTurnToleranceDegrees = 28.0;
    private volatile long alertCooldownNanos = 15_000_000_000L;
    private volatile boolean logContext = true;

    public BaritoneCheck(KeenAC plugin) {
        this.plugin = plugin;
    }

    /** Called from the main server thread once per tick. */
    public void tick() {
        refreshSettings();
        if (!enabled) {
            profiles.clear();
            states.clear();
            lastVelocityNanos.clear();
            return;
        }

        long now = System.nanoTime();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            long lastVelocity = lastVelocityNanos.getOrDefault(id, 0L);
            boolean recentVelocity = lastVelocity > 0L
                    && now - lastVelocity < 1_200_000_000L;
            boolean eligible = isEligible(player) && !recentVelocity
                    && player.getPing() <= maxPingMs;
            profiles.put(id, new PacketProfile(
                    eligible, player.getWorld().getUID(), player.getPing()));
            if (!eligible) {
                states.remove(id);
            }
        }

        profiles.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        states.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        lastVelocityNanos.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    private void refreshSettings() {
        enabled = plugin.getConfig().getBoolean("checks.baritone.enabled", true);
        maxPingMs = Math.max(80,
                plugin.getConfig().getInt("checks.baritone.max-ping-ms", 220));
        observationWindowNanos = Math.max(10_000L,
                plugin.getConfig().getLong("checks.baritone.observation-window-ms", 45000L))
                * 1_000_000L;
        patternSegments = Math.max(3,
                Math.min(8, plugin.getConfig().getInt("checks.baritone.pattern-segments", 4)));
        requiredRepeats = Math.max(2,
                Math.min(5, plugin.getConfig().getInt("checks.baritone.required-repeats", 3)));
        angleToleranceDegrees = Math.max(2.0,
                Math.min(30.0, plugin.getConfig().getDouble(
                        "checks.baritone.angle-tolerance-degrees", 10.0)));
        segmentDistanceTolerance = Math.max(0.05,
                Math.min(2.0, plugin.getConfig().getDouble(
                        "checks.baritone.segment-distance-tolerance", 0.40)));
        minimumTurnDegrees = Math.max(12.0,
                Math.min(60.0, plugin.getConfig().getDouble(
                        "checks.baritone.minimum-turn-degrees", 25.0)));
        maximumTurnDegrees = Math.max(minimumTurnDegrees + 5.0,
                Math.min(175.0, plugin.getConfig().getDouble(
                        "checks.baritone.maximum-turn-degrees", 155.0)));
        minimumSegmentDistance = Math.max(0.25,
                plugin.getConfig().getDouble("checks.baritone.minimum-segment-distance", 1.0));
        maximumSegmentDistance = Math.max(minimumSegmentDistance + 0.25,
                plugin.getConfig().getDouble("checks.baritone.maximum-segment-distance", 12.0));
        yawTurnToleranceDegrees = Math.max(10.0,
                Math.min(60.0, plugin.getConfig().getDouble(
                        "checks.baritone.yaw-turn-tolerance-degrees", 28.0)));
        alertCooldownNanos = Math.max(2_000L,
                plugin.get.getConfig().getLong("checks.baritone.alert-cooldown-ms", 15000L))
                * 1_000_000L;
        logContext = plugin.getConfig().getBoolean("checks.baritone.log-context", true);
    }

    private boolean isEligible(Player player) {
        if (!player.isOnline() || !player.isValid() || player.isDead()
                || !player.isOnGround() || player.isInsideVehicle()
                || player.isFlying() || player.isGliding() || player.isSwimming()
                || player.isRiptiding() || player.isClimbing()
                || player.isInWater() || player.isInLava() || player.isSleeping()
                || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR
                || player.hasPotionEffect(PotionEffectType.LEVITATION)
                || player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
            return false;
        }
        return !isSpecialSurface(player.getLocation());
    }

    private boolean isSpecialSurface(Location location) {
        Material below = location.getBlock().getRelative(0, -1, 0).getType();
        return below == Material.ICE || below == Material.PACKED_ICE
                || below == Material.BLUE_ICE || below == Material.FROSTED_ICE
                || below == Material.SLIME_BLOCK || below == Material.HONEY_BLOCK
                || below == Material.SOUL_SAND || below == Material.SOUL_SOIL
                || below == Material.MUD;
    }

    /**
     * PacketEvents callback. No Bukkit API is used here. The detector needs a
     * sequence of position-bearing, on-ground packets and optional yaw changes.
     */
    public void recordMovement(UUID id, double x, double z, float yaw,
                               boolean rotationChanged, boolean onGround) {
        if (!enabled) {
            return;
        }
        PacketProfile profile = profiles.get(id);
        if (profile == null || !profile.eligible() || !onGround
                || profile.ping() > maxPingMs) {
            states.remove(id);
            return;
        }

        long now = System.nanoTime();
        PacketState state = states.computeIfAbsent(id,
                ignored -> new PacketState(profile.worldId(), x, z, yaw, now));
        boolean shouldAlert = false;
        int violationLevel = 0;
        int observedTurns = 0;

        synchronized (state) {
            if (!state.worldId.equals(profile.worldId())
                    || now - state.lastPacketNanos > 350_000_000L
                    || now <= state.lastPacketNanos) {
                state.reset(profile.worldId(), x, z, yaw, now);
                return;
            }

            double dx = x - state.lastX;
            double dz = z - state.lastZ;
            double distance = Math.hypot(dx, dz);
            if (distance > 0.85) {
                // Packet bursts, knockback-like jumps, and discontinuities are
                // discarded instead of being interpreted as navigation patterns.
                state.reset(profile.worldId(), x, z, yaw, now);
                return;
            }

            if (rotationChanged) {
                if (state.hasYaw) {
                    state.accumulatedYawChange += wrapDegrees(yaw - state.lastYaw);
                }
                state.lastYaw = yaw;
                state.hasYaw = true;
            }

            state.lastPacketNanos = now;
            state.lastX = x;
            state.lastZ = z;
            if (distance < 0.025) {
                return;
            }

            double direction = Math.toDegrees(Math.atan2(-dx, dz));
            if (!state.hasDirection) {
                state.lastDirection = direction;
                state.hasDirection = true;
                state.segmentDistance += distance;
                return;
            }

            // Keep a stable heading anchor: several small steering updates add
            // up to one meaningful turn instead of being lost packet by packet.
            double turn = wrapDegrees(direction - state.lastDirection);
            if (Math.abs(turn) >= minimumTurnDegrees) {
                double segmentLength = state.segmentDistance;
                boolean validTurn = Math.abs(turn) <= maximumTurnDegrees
                        && segmentLength >= minimumSegmentDistance
                        && segmentLength <= maximumSegmentDistance;
                boolean yawMatches = Math.abs(wrapDegrees(
                        state.accumulatedYawChange - turn)) <= yawTurnToleranceDegrees;

                if (validTurn && yawMatches) {
                    state.turns.addLast(new TurnSample(turn, segmentLength, now));
                    pruneOldTurns(state, now);
                    if (hasRepeatedPattern(state)
                            && now - state.lastAlertNanos >= alertCooldownNanos) {
                        state.lastAlertNanos = now;
                        state.violationLevel = Math.min(Integer.MAX_VALUE,
                                state.violationLevel + 1);
                        violationLevel = state.violationLevel;
                        observedTurns = state.turns.size();
                        shouldAlert = true;
                    }
                } else {
                    // A discontinuous or non-yaw-correlated turn breaks the
                    // candidate sequence, rather than adding weak evidence.
                    state.turns.clear();
                }

                state.segmentDistance = 0.0;
                state.accumulatedYawChange = 0.0;
                state.lastDirection = direction;
            }
            state.segmentDistance += distance;
        }

        if (shouldAlert) {
            final int vl = violationLevel;
            final int turnCount = observedTurns;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(id);
                if (player == null || !player.isOnline()
                        || !plugin.getConfig().getBoolean("checks.baritone.enabled", true)
                        || plugin.getAlertManager() == null) {
                    return;
                }
                plugin.getAlertManager().send("Baritone", player, vl);
                if (logContext) {
                    plugin.getLogger().warning(String.format(java.util.Locale.ROOT,
                            "Baritone movement-pattern review for %s (VL=%d, repeated turns=%d)",
                            player.getName(), vl, turnCount));
                }
            });
        }
    }

    private void pruneOldTurns(PacketState state, long now) {
        while (!state.turns.isEmpty()
                && now - state.turns.peekFirst().timestampNanos() > observationWindowNanos) {
            state.turns.removeFirst();
        }
        while (state.turns.size() > 48) {
            state.turns.removeFirst();
        }
    }

    /**
     * Requires the same multi-turn route motif to recur several times in
     * non-overlapping blocks. A straight walk, one turn, or ordinary camera
     * movement cannot satisfy this condition.
     */
    private boolean hasRepeatedPattern(PacketState state) {
        int size = state.turns.size();
        int blockSize = patternSegments;
        if (size < blockSize * requiredRepeats) {
            return false;
        }

        List<TurnSample> samples = new ArrayList<>(state.turns);
        for (int offset = 0; offset < blockSize; offset++) {
            int matchingBlocks = 0;
            for (int start = offset; start + blockSize <= samples.size(); start += blockSize) {
                boolean matches = true;
                for (int i = 0; i < blockSize; i++) {
                    TurnSample candidate = samples.get(start + i);
                    TurnSample reference = samples.get(samples.size() - blockSize + i);
                    if (Math.abs(wrapDegrees(candidate.angleDegrees()
                            - reference.angleDegrees())) > angleToleranceDegrees
                            || Math.abs(candidate.segmentDistance()
                            - reference.segmentDistance()) > segmentDistanceTolerance) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    matchingBlocks++;
                }
            }
            if (matchingBlocks >= requiredRepeats) {
                return true;
            }
        }
        return false;
    }

    private static double wrapDegrees(double angle) {
        double wrapped = angle % 360.0;
        if (wrapped > 180.0) wrapped -= 360.0;
        if (wrapped < -180.0) wrapped += 360.0;
        return wrapped;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastVelocityNanos.put(id, System.nanoTime());
        states.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        reset(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        reset(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        reset(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        reset(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        reset(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGlideChange(EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player player) {
            reset(player.getUniqueId());
        }
    }

    public void reset(UUID id) {
        profiles.remove(id);
        states.remove(id);
        lastVelocityNanos.remove(id);
    }

    public void clearAll() {
        profiles.clear();
        states.clear();
        lastVelocityNanos.clear();
    }

    private record PacketProfile(boolean eligible, UUID worldId, int ping) { }

    private record TurnSample(double angleDegrees, double segmentDistance,
                               long timestampNanos) { }

    private static final class PacketState {
        private final UUID worldId;
        private final Deque<TurnSample> turns = new ArrayDeque<>();
        private double lastX;
        private double lastZ;
        private double lastYaw;
        private double lastDirection;
        private double accumulatedYawChange;
        private double segmentDistance;
        private long lastPacketNanos;
        private long lastAlertNanos;
        private int violationLevel;
        private boolean hasYaw;
        private boolean hasDirection;

        private PacketState(UUID worldId, double x, double z, float yaw, long now) {
            this.worldId = worldId;
            reset(worldId, x, z, yaw, now);
        }

        private void reset(UUID world, double x, double z, float yaw, long now) {
            // worldId is immutable for a PacketState, so a world change is
            // handled by replacing the state in recordMovement.
            lastX = x;
            lastZ = z;
            lastYaw = yaw;
            lastDirection = 0.0;
            accumulatedYawChange = 0.0;
            segmentDistance = 0.0;
            lastPacketNanos = now;
            turns.clear();
            hasYaw = true;
            hasDirection = false;
        }
    }
}