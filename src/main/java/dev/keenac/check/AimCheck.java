package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conservative, multi-signal aim-assist review.
 *
 * The check is advisory only. It combines repeated accurate attacks against
 * moving targets with recent rotation behaviour; one isolated hit never flags.
 * Bukkit entity snapshots are refreshed on the main thread. Packet callbacks
 * only work with snapshots and synchronized per-player state.
 */
public final class AimCheck implements Listener {
    private static final long ROTATION_HISTORY_NANOS = 900_000_000L;
    private static final long MAX_ROTATION_AGE_NANOS = 1_500_000_000L;

    private final KeenAC plugin;
    private final Map<UUID, AimState> players = new ConcurrentHashMap<>();
    private final Map<Integer, TargetSnapshot> targets = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private volatile double accurateAngle;
    private volatile double veryAccurateAngle;
    private volatile double minimumTargetSpeed;
    private volatile double stableAngleDeviation;
    private volatile int requiredMovingHits;
    private volatile int requiredSmoothApproaches;
    private volatile long observationWindowNanos;
    private volatile long alertCooldownNanos;
    private volatile int maximumPingMillis;

    public AimCheck(KeenAC plugin) {
        this.plugin = plugin;
        reloadSettings();
    }

    public void reloadSettings() {
        enabled = plugin.getConfig().getBoolean("checks.aim.enabled", true);
        accurateAngle = clamp(
                plugin.getConfig().getDouble("checks.aim.accurate-angle-degrees", 3.5),
                1.0, 12.0);
        veryAccurateAngle = clamp(
                plugin.getConfig().getDouble("checks.aim.very-accurate-angle-degrees", 1.4),
                0.4, accurateAngle);
        minimumTargetSpeed = clamp(
                plugin.getConfig().getDouble("checks.aim.minimum-target-speed", 0.08),
                0.01, 1.0);
        stableAngleDeviation = clamp(
                plugin.getConfig().getDouble("checks.aim.stable-angle-deviation", 0.65),
                0.1, 3.0);
        requiredMovingHits = Math.max(5,
                plugin.getConfig().getInt("checks.aim.required-moving-hits", 8));
        requiredSmoothApproaches = Math.max(2,
                plugin.getConfig().getInt("checks.aim.required-smooth-approaches", 4));
        observationWindowNanos = Math.max(2_000L,
                plugin.getConfig().getLong("checks.aim.observation-window-ms", 8000L))
                * 1_000_000L;
        alertCooldownNanos = Math.max(1000L,
                plugin.getConfig().getLong("checks.aim.alert-cooldown-ms", 5000L))
                * 1_000_000L;
        maximumPingMillis = Math.max(50,
                plugin.getConfig().getInt("checks.aim.max-ping-ms", 250));
    }

    /** Refresh player positions, target movement and fallback rotations each tick. */
    public void tick() {
        if (!enabled) {
            return;
        }

        long now = System.nanoTime();
        Set<Integer> onlineIds = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            Location location = player.getLocation();
            Location eye = player.getEyeLocation();
            UUID id = player.getUniqueId();
            int entityId = player.getEntityId();
            UUID worldId = player.getWorld().getUID();
            onlineIds.add(entityId);

            AimState state = players.computeIfAbsent(id, ignored -> new AimState());
            synchronized (state) {
                if (state.worldId != null && !state.worldId.equals(worldId)) {
                    state.resetEvidence();
                    state.rotations.clear();
                } else if (state.hasPosition) {
                    double dx = location.getX() - state.lastX;
                    double dy = location.getY() - state.lastY;
                    double dz = location.getZ() - state.lastZ;
                    if (dx * dx + dy * dy + dz * dz > 36.0) {
                        state.resetEvidence();
                        state.rotations.clear();
                    }
                }

                state.worldId = worldId;
                state.eyeX = eye.getX();
                state.eyeY = eye.getY();
                state.eyeZ = eye.getZ();
                state.lastX = location.getX();
                state.lastY = location.getY();
                state.lastZ = location.getZ();
                state.hasPosition = true;
                state.ping = player.getPing();

                if (now - state.lastPacketRotationNanos > MAX_ROTATION_AGE_NANOS) {
                    state.yaw = location.getYaw();
                    state.pitch = location.getPitch();
                }

                trimRotations(state, now);
            }

            TargetSnapshot previous = targets.get(entityId);
            double moveX = 0.0;
            double moveZ = 0.0;
            if (previous != null && previous.worldId.equals(worldId)) {
                moveX = location.getX() - previous.x;
                moveZ = location.getZ() - previous.z;
                // A large one-tick displacement is more likely a teleport than
                // ordinary target movement and must not count as tracking data.
                if (moveX * moveX + moveZ * moveZ > 25.0) {
                    moveX = 0.0;
                    moveZ = 0.0;
                }
            }

            targets.put(entityId, new TargetSnapshot(
                    entityId, id, worldId, location.getX(),
                    location.getY(), location.getZ(),
                    Math.max(1.0, player.getHeight()),
                    Math.hypot(moveX, moveZ),
                    player.getPing()));
        }

        targets.keySet().removeIf(id -> !onlineIds.contains(id));
    }

    /** Called for rotation-bearing client movement packets. */
    public void recordRotation(UUID playerId, float yaw, float pitch) {
        if (!enabled || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            return;
        }

        long now = System.nanoTime();
        AimState state = players.computeIfAbsent(playerId, ignored -> new AimState());
        synchronized (state) {
            if (state.lastPacketRotationNanos != 0L
                    && now - state.lastPacketRotationNanos > ROTATION_HISTORY_NANOS) {
                state.rotations.clear();
            }

            state.yaw = yaw;
            state.pitch = pitch;
            state.lastPacketRotationNanos = now;
            state.rotations.addLast(new RotationSample(now, yaw, pitch));
            trimRotations(state, now);
        }
    }

    /** Process an attack packet without cancelling it; confirmed reviews alert staff. */
    public void recordAttack(UUID attackerId, int targetEntityId) {
        if (!enabled) {
            return;
        }

        AimState state = players.get(attackerId);
        TargetSnapshot target = targets.get(targetEntityId);
        if (state == null || target == null || attackerId.equals(target.playerId)) {
            return;
        }

        long now = System.nanoTime();
        int violationLevel = 0;
        boolean alert = false;

        synchronized (state) {
            if (state.worldId == null || !state.worldId.equals(target.worldId)
                    || state.ping > maximumPingMillis
                    || target.ping > maximumPingMillis
                    || state.lastPacketRotationNanos == 0L
                    || now - state.lastPacketRotationNanos > MAX_ROTATION_AGE_NANOS) {
                return;
            }

            double dx = target.x - state.eyeX;
            double dz = target.z - state.eyeZ;
            double distanceSquared = dx * dx + dz * dz;
            if (distanceSquared < 0.16 || distanceSquared > 100.0) {
                return;
            }

            double error = minimumHitboxAngleError(state, target);
            boolean movingTarget = target.horizontalMotion >= minimumTargetSpeed;
            boolean smoothApproach = movingTarget && hasSmoothApproach(state, target, now);

            state.observations.addLast(new AimObservation(
                    now, target.entityId, error, movingTarget, smoothApproach));
            trimObservations(state, now);

            if (!hasCompositeEvidence(state)) {
                return;
            }

            state.observations.clear();
            state.violationLevel++;
            violationLevel = state.violationLevel;
            if (now - state.lastAlertNanos >= alertCooldownNanos) {
                state.lastAlertNanos = now;
                alert = true;
            }
        }

        if (alert) {
            final int vl = violationLevel;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(attackerId);
                if (player != null && player.isOnline()) {
                    plugin.getAlertManager().send("Aim", player, vl);
                }
            });
        }
    }

    private boolean hasCompositeEvidence(AimState state) {
        List<AimObservation> accurate = new ArrayList<>();
        int veryAccurate = 0;
        int smooth = 0;

        for (AimObservation observation : state.observations) {
            if (!observation.movingTarget || observation.error > accurateAngle) {
                continue;
            }

            accurate.add(observation);
            if (observation.error <= veryAccurateAngle) {
                veryAccurate++;
            }
            if (observation.smoothApproach) {
                smooth++;
            }
        }

        if (accurate.size() < requiredMovingHits) {
            return false;
        }

        // Stable precision is only supporting evidence; it cannot trigger alone.
        double mean = 0.0;
        for (AimObservation observation : accurate) {
            mean += observation.error;
        }
        mean /= accurate.size();

        double variance = 0.0;
        for (AimObservation observation : accurate) {
            double difference = observation.error - mean;
            variance += difference * difference;
        }
        double deviation = Math.sqrt(variance / accurate.size());

        boolean repeatedSmoothTracking = smooth >= requiredSmoothApproaches;
        boolean unusuallyStablePrecision =
                veryAccurate >= requiredMovingHits - 2
                        && deviation <= stableAngleDeviation;
        return repeatedSmoothTracking || unusuallyStablePrecision;
    }

    private boolean hasSmoothApproach(AimState state, TargetSnapshot target, long now) {
        List<RotationSample> recent = new ArrayList<>();
        for (RotationSample sample : state.rotations) {
            if (now - sample.timeNanos <= 450_000_000L) {
                recent.add(sample);
            }
        }

        if (recent.size() < 4) {
            return false;
        }

        double firstError = angleError(
                state.eyeX, state.eyeY, state.eyeZ,
                recent.get(0).yaw, recent.get(0).pitch, target);
        double previousError = firstError;
        int improvements = 0;
        int reversals = 0;

        for (int i = 1; i < recent.size(); i++) {
            RotationSample sample = recent.get(i);
            double currentError = angleError(
                    state.eyeX, state.eyeY, state.eyeZ,
                    sample.yaw, sample.pitch, target);

            if (currentError < previousError - 0.05) {
                improvements++;
            } else if (currentError > previousError + 0.8) {
                reversals++;
            }
            previousError = currentError;
        }

        return firstError - previousError >= 2.0
                && improvements >= 2
                && reversals <= 1
                && previousError <= accurateAngle * 1.5;
    }

    private double minimumHitboxAngleError(AimState state, TargetSnapshot target) {
        double best = Double.MAX_VALUE;
        double[] heights = {0.20, 0.50, 0.82};
        double[][] horizontalOffsets = {
                {0.0, 0.0}, {0.22, 0.0}, {-0.22, 0.0},
                {0.0, 0.22}, {0.0, -0.22}
        };

        for (double height : heights) {
            for (double[] offset : horizontalOffsets) {
                double pointX = target.x + offset[0];
                double pointY = target.y + target.height * height;
                double pointZ = target.z + offset[1];
                best = Math.min(best, angleErrorToPoint(
                        state.eyeX, state.eyeY, state.eyeZ,
                        state.yaw, state.pitch, pointX, pointY, pointZ));
            }
        }
        return best;
    }

    private static double angleError(
            double eyeX, double eyeY, double eyeZ,
            float yaw, float pitch, TargetSnapshot target) {
        double best = Double.MAX_VALUE;
        double[] heights = {0.20, 0.50, 0.82};
        for (double height : heights) {
            best = Math.min(best, angleErrorToPoint(
                    eyeX, eyeY, eyeZ, yaw, pitch,
                    target.x, target.y + target.height * height, target.z));
        }
        return best;
    }

    private static double angleErrorToPoint(
            double eyeX, double eyeY, double eyeZ,
            float yaw, float pitch,
            double targetX, double targetY, double targetZ) {
        double dx = targetX - eyeX;
        double dy = targetY - eyeY;
        double dz = targetZ - eyeZ;
        double horizontal = Math.hypot(dx, dz);
        if (horizontal < 1.0E-4) {
            return 180.0;
        }

        double expectedYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double expectedPitch = -Math.toDegrees(Math.atan2(dy, horizontal));
        double yawDifference = wrapDegrees(expectedYaw - yaw);
        double pitchDifference = expectedPitch - pitch;
        return Math.hypot(
                yawDifference * Math.cos(Math.toRadians(pitch)),
                pitchDifference);
    }

    private static double wrapDegrees(double angle) {
        angle %= 360.0;
        if (angle >= 180.0) angle -= 360.0;
        if (angle < -180.0) angle += 360.0;
        return angle;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void trimRotations(AimState state, long now) {
        while (!state.rotations.isEmpty()
                && now - state.rotations.peekFirst().timeNanos > ROTATION_HISTORY_NANOS) {
            state.rotations.removeFirst();
        }
        while (state.rotations.size() > 64) {
            state.rotations.removeFirst();
        }
    }

    private void trimObservations(AimState state, long now) {
        while (!state.observations.isEmpty()
                && now - state.observations.peekFirst().timeNanos > observationWindowNanos) {
            state.observations.removeFirst();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        players.remove(id);
        targets.remove(event.getPlayer().getEntityId());
    }

    public void clearAll() {
        players.clear();
        targets.clear();
    }

    private static final class AimState {
        private final Deque<RotationSample> rotations = new ArrayDeque<>();
        private final Deque<AimObservation> observations = new ArrayDeque<>();
        private UUID worldId;
        private double eyeX;
        private double eyeY;
        private double eyeZ;
        private double lastX;
        private double lastY;
        private double lastZ;
        private boolean hasPosition;
        private float yaw;
        private float pitch;
        private int ping;
        private long lastPacketRotationNanos;
        private long lastAlertNanos;
        private int violationLevel;

        private void resetEvidence() {
            observations.clear();
        }
    }

    private record RotationSample(long timeNanos, float yaw, float pitch) { }

    private record AimObservation(
            long timeNanos,
            int targetEntityId,
            double error,
            boolean movingTarget,
            boolean smoothApproach) { }

    private record TargetSnapshot(
            int entityId,
            UUID playerId,
            UUID worldId,
            double x,
            double y,
            double z,
            double height,
            double horizontalMotion,
            int ping) { }
}
