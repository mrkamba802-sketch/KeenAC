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
 * Baritone (A): advisory detection of repeated, high-confidence path-steering
 * decisions. Unlike the original Baritone check, this variant does not require
 * the player to repeat the same route motif.
 *
 * Only primitive packet values are read in recordMovement(). Bukkit player
 * state is snapshotted on the primary thread by tick().
 */
public final class BaritoneACheck implements Listener {
    private static final long MAX_PACKET_GAP_NANOS = 1_200_000_000L;
    private static final double MAX_PACKET_DISTANCE = 0.85;
    private static final double MIN_MOVEMENT_DISTANCE = 0.025;

    private final KeenAC plugin;
    private final Map<UUID, PacketProfile> profiles = new ConcurrentHashMap<>();
    private final Map<UUID, PacketState> states = new ConcurrentHashMap<>();

    private volatile boolean enabled = true;
    private volatile int maxPingMs = 220;
    private volatile long observationWindowNanos = 25_000_000_000L;
    private volatile long velocityGraceMs = 1_200L;
    private volatile int requiredTurns = 8;
    private volatile int alertStep = 4;
    private volatile double minimumTurnDegrees = 18.0;
    private volatile double maximumTurnDegrees = 150.0;
    private volatile double minimumSegmentDistance = 0.65;
    private volatile double maximumSegmentDistance = 10.0;
    private volatile double yawTurnToleranceDegrees = 12.0;
    private volatile double maximumAverageYawErrorDegrees = 8.0;
    private volatile int rotationSnapRequiredCount = 7;
    private volatile double rotationSnapMinYawDegrees = 12.0;
    private volatile double rotationSnapMaxYawDegrees = 150.0;
    private volatile double rotationSnapMaxPitchDegrees = 0.75;
    private volatile double rotationSnapMaximumAveragePitchDegrees = 0.45;
    private volatile double rotationSnapMinimumTravelDistance = 4.0;
    private volatile boolean logContext = true;

    public BaritoneACheck(KeenAC plugin) {
        this.plugin = plugin;
    }

    /** Refreshes eligibility and settings on the Bukkit primary thread. */
    public void tick() {
        refreshSettings();
        if (!enabled) {
            profiles.clear();
            states.clear();
            return;
        }

        long now = System.nanoTime();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            boolean recentVelocity = plugin.getPlayerContextManager()
                    .isWithinVelocityGrace(id, now, velocityGraceMs);
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
    }

    private void refreshSettings() {
        enabled = plugin.getConfig().getBoolean("checks.baritone-a.enabled", true);
        maxPingMs = Math.max(80,
                plugin.getConfig().getInt("checks.baritone-a.max-ping-ms", 220));
        observationWindowNanos = Math.max(5_000L,
                Math.min(60_000L, plugin.getConfig().getLong(
                        "checks.baritone-a.observation-window-ms", 25000L))) * 1_000_000L;
        velocityGraceMs = Math.max(200L,
                Math.min(5_000L, plugin.getConfig().getLong(
                        "checks.baritone-a.velocity-grace-ms", 1200L)));
        requiredTurns = Math.max(5,
                Math.min(16, plugin.getConfig().getInt(
                        "checks.baritone-a.required-turns", 8)));
        alertStep = Math.max(2,
                Math.min(8, plugin.getConfig().getInt(
                        "checks.baritone-a.alert-step", 4)));
        minimumTurnDegrees = Math.max(10.0,
                Math.min(45.0, plugin.getConfig().getDouble(
                        "checks.baritone-a.minimum-turn-degrees", 18.0)));
        maximumTurnDegrees = Math.max(minimumTurnDegrees + 5.0,
                Math.min(175.0, plugin.getConfig().getDouble(
                        "checks.baritone-a.maximum-turn-degrees", 150.0)));
        minimumSegmentDistance = Math.max(0.25,
                plugin.getConfig().getDouble(
                        "checks.baritone-a.minimum-segment-distance", 0.65));
        maximumSegmentDistance = Math.max(minimumSegmentDistance + 0.25,
                plugin.getConfig().getDouble(
                        "checks.baritone-a.maximum-segment-distance", 10.0));
        yawTurnToleranceDegrees = Math.max(3.0,
                Math.min(30.0, plugin.getConfig().getDouble(
                        "checks.baritone-a.yaw-turn-tolerance-degrees", 12.0)));
        maximumAverageYawErrorDegrees = Math.max(1.0,
                Math.min(yawTurnToleranceDegrees, plugin.getConfig().getDouble(
                        "checks.baritone-a.maximum-average-yaw-error-degrees", 8.0)));
        rotationSnapRequiredCount = Math.max(4,
                Math.min(12, plugin.getConfig().getInt(
                        "checks.baritone-a.rotation-snap-required-count", 7)));
        rotationSnapMinYawDegrees = Math.max(6.0,
                Math.min(45.0, plugin.getConfig().getDouble(
                        "checks.baritone-a.rotation-snap-min-yaw-degrees", 12.0)));
        rotationSnapMaxYawDegrees = Math.max(rotationSnapMinYawDegrees + 5.0,
                Math.min(175.0, plugin.getConfig().getDouble(
                        "checks.baritone-a.rotation-snap-max-yaw-degrees", 150.0)));
        rotationSnapMaxPitchDegrees = Math.max(0.1,
                Math.min(5.0, plugin.getConfig().getDouble(
                        "checks.baritone-a.rotation-snap-max-pitch-degrees", 0.75)));
        rotationSnapMaximumAveragePitchDegrees = Math.max(0.05,
                Math.min(rotationSnapMaxPitchDegrees, plugin.getConfig().getDouble(
                        "checks.baritone-a.rotation-snap-max-average-pitch-degrees", 0.45)));
        rotationSnapMinimumTravelDistance = Math.max(1.0,
                Math.min(20.0, plugin.getConfig().getDouble(
                        "checks.baritone-a.rotation-snap-min-travel-distance", 4.0)));
        logContext = plugin.getConfig().getBoolean("checks.baritone-a.log-context", true);
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
        Material below = player.getLocation().getBlock().getRelative(0, -1, 0).getType();
        return below != Material.ICE && below != Material.PACKED_ICE
                && below != Material.BLUE_ICE && below != Material.FROSTED_ICE
                && below != Material.SLIME_BLOCK && below != Material.HONEY_BLOCK
                && below != Material.SOUL_SAND && below != Material.SOUL_SOIL
                && below != Material.MUD;
    }

    /**
     * PacketEvents entry point. Can receive both position-only and
     * rotation-only movement updates so turn correlation is not lost.
     */
    public void recordRotation(UUID id, float yaw, float pitch) {
        if (!enabled || id == null || !Float.isFinite(yaw) || !Float.isFinite(pitch)) return;
        PacketProfile profile = profiles.get(id);
        PacketState state = states.get(id);
        if (profile == null || !profile.eligible() || state == null) return;
        long now = System.nanoTime();
        synchronized (state) {
            observeRotation(state, yaw, pitch, now);
            pruneRotationHistory(state, now);
        }
    }

    public void recordMovement(UUID id, double x, double z, float yaw, float pitch,
                               boolean rotationChanged, boolean onGround) {
        if (!enabled || id == null || !Double.isFinite(x) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
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
                ignored -> new PacketState(profile.worldId(), x, z, yaw, pitch,
                        rotationChanged, now, requiredTurns, rotationSnapRequiredCount));

        boolean shouldAlert = false;
        int violationLevel = 0;
        String alertReason = null;
        String signalSummary = null;

        synchronized (state) {
            if (!state.worldId.equals(profile.worldId())) {
                state.reset(profile.worldId(), x, z, yaw, pitch, rotationChanged,
                        now, requiredTurns, rotationSnapRequiredCount);
                return;
            }

            if (now <= state.lastPacketNanos
                    || now - state.lastPacketNanos > MAX_PACKET_GAP_NANOS) {
                state.reset(profile.worldId(), x, z, yaw, pitch, rotationChanged,
                        now, requiredTurns, rotationSnapRequiredCount);
                return;
            }

            double dx = x - state.lastX;
            double dz = z - state.lastZ;
            double distance = Math.hypot(dx, dz);
            if (distance > MAX_PACKET_DISTANCE) {
                state.reset(profile.worldId(), x, z, yaw, pitch, rotationChanged,
                        now, requiredTurns, rotationSnapRequiredCount);
                return;
            }

            if (rotationChanged) {
                observeRotation(state, yaw, pitch, now);
            }

            state.lastPacketNanos = now;
            state.lastX = x;
            state.lastZ = z;
            if (distance >= MIN_MOVEMENT_DISTANCE) {
                state.travelSamples.addLast(new TravelSample(distance, now));
            }
            pruneRotationHistory(state, now);

            double recentTravel = recentTravelDistance(state);
            List<BaritoneARotationAnalyzer.RotationSnap> recentSnaps =
                    new ArrayList<>(state.rotationSnaps);
            boolean snapDensity = BaritoneARotationAnalyzer.hasSnapDensity(
                    recentSnaps, rotationSnapRequiredCount,
                    rotationSnapMaximumAveragePitchDegrees,
                    recentTravel, rotationSnapMinimumTravelDistance);

            if (snapDensity && state.rotationSnaps.size() >= state.nextAlertAtSnaps) {
                if (state.violationLevel < Integer.MAX_VALUE) {
                    state.violationLevel++;
                }
                violationLevel = state.violationLevel;
                state.nextAlertAtSnaps = state.rotationSnaps.size() + alertStep;
                shouldAlert = true;
                alertReason = "Repeated horizontal rotation snaps with very small pitch changes occurred during sustained ground movement.";
                signalSummary = "rotation snaps=" + state.rotationSnaps.size()
                        + ", travelled=" + String.format(java.util.Locale.ROOT, "%.2f", recentTravel)
                        + " blocks";
            }

            if (distance >= MIN_MOVEMENT_DISTANCE) {
                double direction = Math.toDegrees(Math.atan2(-dx, dz));
                if (!state.hasDirection) {
                    state.lastDirection = direction;
                    state.hasDirection = true;
                    state.segmentDistance += distance;
                } else {
                    double turn = BaritoneASteeringAnalyzer.wrapDegrees(
                            direction - state.lastDirection);
                    if (Math.abs(turn) >= minimumTurnDegrees) {
                        double segmentDistance = state.segmentDistance;
                        double yawTurn = state.accumulatedYawChange;
                        boolean matched = BaritoneASteeringAnalyzer.matchesTurn(
                                turn, yawTurn, segmentDistance,
                                minimumTurnDegrees, maximumTurnDegrees,
                                minimumSegmentDistance, maximumSegmentDistance,
                                yawTurnToleranceDegrees);

                        if (matched) {
                            state.turns.addLast(new BaritoneASteeringAnalyzer.TurnSample(
                                    turn, yawTurn, segmentDistance, now));
                            pruneOldTurns(state, now);

                            List<BaritoneASteeringAnalyzer.TurnSample> recentTurns =
                                    new ArrayList<>(state.turns);
                            boolean consistent = BaritoneASteeringAnalyzer.hasConsistentSteering(
                                    recentTurns, requiredTurns, maximumAverageYawErrorDegrees);
                            if (!shouldAlert && consistent
                                    && state.turns.size() >= state.nextAlertAtTurns) {
                                if (state.violationLevel < Integer.MAX_VALUE) {
                                    state.violationLevel++;
                                }
                                violationLevel = state.violationLevel;
                                state.nextAlertAtTurns = state.turns.size() + alertStep;
                                shouldAlert = true;
                                alertReason = "Repeated ground-navigation turns showed unusually precise camera-to-travel steering.";
                                signalSummary = "matched steering turns=" + state.turns.size();
                            }
                        } else {
                            state.turns.clear();
                            state.nextAlertAtTurns = requiredTurns;
                        }

                        state.segmentDistance = 0.0;
                        state.accumulatedYawChange = 0.0;
                        state.lastDirection = direction;
                    }
                    state.segmentDistance += distance;
                }
            }
        }

        if (shouldAlert) {
            final int vl = violationLevel;
            final String reason = alertReason;
            final String summary = signalSummary;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(id);
                if (player == null || !player.isOnline()
                        || !plugin.getConfig().getBoolean("checks.baritone-a.enabled", true)
                        || plugin.getAlertManager() == null) {
                    return;
                }
                plugin.getAlertManager().send("Baritone (A)", player, vl, reason);
                if (logContext) {
                    plugin.getLogger().warning(String.format(java.util.Locale.ROOT,
                            "Baritone (A) review for %s (VL=%d, %s, ping=%dms)",
                            player.getName(), vl, summary, player.getPing()));
                }
            });
        }
    }

    private void observeRotation(PacketState state, float yaw, float pitch, long now) {
        if (!state.hasYaw) {
            state.lastYaw = yaw;
            state.lastPitch = pitch;
            state.hasYaw = true;
            return;
        }

        double yawDelta = BaritoneASteeringAnalyzer.wrapDegrees(yaw - state.lastYaw);
        double pitchDelta = pitch - state.lastPitch;
        state.accumulatedYawChange += yawDelta;
        if (BaritoneARotationAnalyzer.isRotationSnap(
                yawDelta, pitchDelta,
                rotationSnapMinYawDegrees, rotationSnapMaxYawDegrees,
                rotationSnapMaxPitchDegrees)) {
            state.rotationSnaps.addLast(
                    new BaritoneARotationAnalyzer.RotationSnap(yawDelta, pitchDelta, now));
        }
        state.lastYaw = yaw;
        state.lastPitch = pitch;
    }

    private void pruneRotationHistory(PacketState state, long now) {
        while (!state.rotationSnaps.isEmpty()
                && now - state.rotationSnaps.peekFirst().timestampNanos() > observationWindowNanos) {
            state.rotationSnaps.removeFirst();
        }
        while (!state.travelSamples.isEmpty()
                && now - state.travelSamples.peekFirst().timestampNanos() > observationWindowNanos) {
            state.travelSamples.removeFirst();
        }
        while (state.rotationSnaps.size() > 40) {
            state.rotationSnaps.removeFirst();
        }
        while (state.travelSamples.size() > 512) {
            state.travelSamples.removeFirst();
        }
    }

    private double recentTravelDistance(PacketState state) {
        double total = 0.0;
        for (TravelSample sample : state.travelSamples) {
            total += sample.distance();
        }
        return total;
    }

    private void pruneOldTurns(PacketState state, long now) {
        while (!state.turns.isEmpty()
                && now - state.turns.peekFirst().timestampNanos() > observationWindowNanos) {
            state.turns.removeFirst();
        }
        while (state.turns.size() > 32) {
            state.turns.removeFirst();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        states.remove(event.getPlayer().getUniqueId());
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
    }

    public void clearAll() {
        profiles.clear();
        states.clear();
    }

    private record PacketProfile(boolean eligible, UUID worldId, int ping) { }
    private record TravelSample(double distance, long timestampNanos) { }

    private static final class PacketState {
        private UUID worldId;
        private final Deque<BaritoneASteeringAnalyzer.TurnSample> turns = new ArrayDeque<>();
        private final Deque<BaritoneARotationAnalyzer.RotationSnap> rotationSnaps = new ArrayDeque<>();
        private final Deque<TravelSample> travelSamples = new ArrayDeque<>();
        private double lastX;
        private double lastZ;
        private double lastYaw;
        private double lastPitch;
        private double lastDirection;
        private double accumulatedYawChange;
        private double segmentDistance;
        private long lastPacketNanos;
        private int violationLevel;
        private int nextAlertAtTurns;
        private int nextAlertAtSnaps;
        private boolean hasYaw;
        private boolean hasDirection;

        private PacketState(UUID worldId, double x, double z, float yaw, float pitch,
                            boolean rotationChanged, long now, int initialRequiredTurns,
                            int initialRequiredSnaps) {
            reset(worldId, x, z, yaw, pitch, rotationChanged, now,
                    initialRequiredTurns, initialRequiredSnaps);
        }

        private void reset(UUID worldId, double x, double z, float yaw, float pitch,
                           boolean rotationChanged, long now, int initialRequiredTurns,
                           int initialRequiredSnaps) {
            this.worldId = worldId;
            lastX = x;
            lastZ = z;
            lastYaw = yaw;
            lastPitch = pitch;
            lastDirection = 0.0;
            accumulatedYawChange = 0.0;
            segmentDistance = 0.0;
            lastPacketNanos = now;
            turns.clear();
            rotationSnaps.clear();
            travelSamples.clear();
            violationLevel = 0;
            nextAlertAtTurns = initialRequiredTurns;
            nextAlertAtSnaps = initialRequiredSnaps;
            hasYaw = rotationChanged;
            hasDirection = false;
        }
    }
}
