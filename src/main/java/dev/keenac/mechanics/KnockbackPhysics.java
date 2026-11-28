package dev.keenac.mechanics;

import java.util.UUID;

/**
 * Conservative helpers for server-authoritative velocity and PvP knockback.
 * Combat observations are evidence/context only; the simulator still seeds
 * from the server's actual velocity rather than assuming a textbook impulse.
 */
public final class KnockbackPhysics {
    private static final double AIRBORNE_Y_THRESHOLD = 0.03;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private KnockbackPhysics() { }

    public static boolean allowsPredictionDuringDamageGrace(
            long damageAgeMillis, long gracePeriodMillis, boolean knockbackActive
    ) {
        return knockbackActive
                || damageAgeMillis < 0L
                || damageAgeMillis >= Math.max(0L, gracePeriodMillis);
    }

    public static long effectiveImpulseWindowMillis(
            long baseAgeMillis, int pingMillis, long maximumPingExtensionMillis
    ) {
        if (baseAgeMillis <= 0L) return 0L;
        long pingExtension = Math.max(0L, pingMillis);
        pingExtension = Math.min(Math.max(0L, maximumPingExtensionMillis), pingExtension);
        if (Long.MAX_VALUE - baseAgeMillis < pingExtension) return Long.MAX_VALUE;
        return baseAgeMillis + pingExtension;
    }

    public static boolean isRecentImpulse(
            Impulse impulse, long nowNanos, long maxAgeMillis, double minimumSpeed
    ) {
        return isRecentImpulse(impulse, nowNanos, maxAgeMillis, 0, 0L, minimumSpeed);
    }

    public static boolean isRecentImpulse(
            Impulse impulse, long nowNanos, long maxAgeMillis,
            int pingMillis, long maximumPingExtensionMillis, double minimumSpeed
    ) {
        return impulse != null && isRecentVector(
                new MotionVector(impulse.velocityX(), impulse.velocityY(), impulse.velocityZ()),
                impulse.timestampNanos(), nowNanos,
                effectiveImpulseWindowMillis(maxAgeMillis, pingMillis, maximumPingExtensionMillis),
                minimumSpeed);
    }

    public static boolean isRecentCombatImpulse(
            CombatImpulse impulse, long nowNanos, long maxAgeMillis,
            int pingMillis, long maximumPingExtensionMillis, double minimumSpeed
    ) {
        return impulse != null && isRecentVector(
                impulse.knockback(), impulse.timestampNanos(), nowNanos,
                effectiveImpulseWindowMillis(maxAgeMillis, pingMillis, maximumPingExtensionMillis),
                minimumSpeed);
    }

    private static boolean isRecentVector(
            MotionVector vector, long timestampNanos, long nowNanos,
            long maxAgeMillis, double minimumSpeed
    ) {
        if (vector == null || !vector.isFinite() || maxAgeMillis <= 0L
                || !Double.isFinite(minimumSpeed) || minimumSpeed < 0.0) {
            return false;
        }
        long ageNanos = nowNanos - timestampNanos;
        if (ageNanos < 0L || maxAgeMillis > Long.MAX_VALUE / NANOS_PER_MILLI
                || ageNanos > maxAgeMillis * NANOS_PER_MILLI) {
            return false;
        }
        double speed = vector.length();
        return Double.isFinite(speed) && speed >= minimumSpeed;
    }

    /** Bounded packet-gap extension for clients with higher round-trip latency. */
    public static long maxPredictionIntervalNanos(
            int pingMillis, long baseIntervalNanos, long maximumExtensionNanos
    ) {
        if (baseIntervalNanos <= 0L) return 0L;
        long pingExtension = Math.max(0L, pingMillis) * 500_000L;
        pingExtension = Math.min(Math.max(0L, maximumExtensionNanos), pingExtension);
        if (Long.MAX_VALUE - baseIntervalNanos < pingExtension) return Long.MAX_VALUE;
        return baseIntervalNanos + pingExtension;
    }

    public static boolean isSprintResetWithinWindow(
            boolean attackerSprinting,
            long sprintStartAgeMillis,
            long stopToStartMillis,
            long maximumWindowMillis
    ) {
        return attackerSprinting
                && maximumWindowMillis > 0L
                && sprintStartAgeMillis >= 0L
                && sprintStartAgeMillis <= maximumWindowMillis
                && stopToStartMillis >= 0L
                && stopToStartMillis <= maximumWindowMillis;
    }

    public static MovementState seedState(
            double x, double y, double z,
            float yaw, boolean packetOnGround,
            MechanicsSnapshot mechanics
    ) {
        if (mechanics == null) {
            throw new IllegalArgumentException("A mechanics snapshot is required.");
        }
        double velocityX = finiteOrZero(mechanics.velocityX());
        double velocityY = finiteOrZero(mechanics.velocityY());
        double velocityZ = finiteOrZero(mechanics.velocityZ());
        boolean onGround = packetOnGround && velocityY <= AIRBORNE_Y_THRESHOLD;
        return new MovementState(x, y, z, velocityX, velocityY, velocityZ, yaw, onGround);
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    public record MotionVector(double x, double y, double z) {
        public boolean isFinite() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
        }

        public double length() {
            return Math.sqrt(x * x + y * y + z * z);
        }

        public static MotionVector zero() {
            return new MotionVector(0.0, 0.0, 0.0);
        }
    }

    public record Impulse(
            double velocityX,
            double velocityY,
            double velocityZ,
            long timestampNanos
    ) {
        public boolean isFinite() {
            return Double.isFinite(velocityX)
                    && Double.isFinite(velocityY)
                    && Double.isFinite(velocityZ);
        }
    }

    /**
     * A Paper attack-knockback event paired, when available, with the following
     * PlayerVelocityEvent. The two vectors are kept separate: event force is not
     * assumed to be identical to the final velocity sent to the client.
     */
    public record CombatImpulse(
            MotionVector knockback,
            MotionVector velocityBefore,
            MotionVector appliedVelocity,
            boolean appliedVelocityCaptured,
            long appliedVelocityAtNanos,
            long timestampNanos,
            String cause,
            UUID attackerId,
            boolean attackerSprinting,
            boolean sprintReset,
            long sprintStartAgeMillis,
            long sprintStopToStartMillis,
            double targetKnockbackResistance,
            double attackerKnockbackAttribute,
            int attackerKnockbackEnchantment,
            int attackerPingMs
    ) {
        public CombatImpulse {
            knockback = knockback == null ? MotionVector.zero() : knockback;
            velocityBefore = velocityBefore == null ? MotionVector.zero() : velocityBefore;
            appliedVelocity = appliedVelocity == null ? MotionVector.zero() : appliedVelocity;
            cause = cause == null ? "UNKNOWN" : cause;
            attackerKnockbackEnchantment = Math.max(0, attackerKnockbackEnchantment);
            attackerPingMs = Math.max(0, attackerPingMs);
        }

        public CombatImpulse withAppliedVelocity(MotionVector velocity, long eventAtNanos) {
            return new CombatImpulse(knockback, velocityBefore, velocity,
                    velocity != null && velocity.isFinite(), eventAtNanos, timestampNanos,
                    cause, attackerId, attackerSprinting, sprintReset,
                    sprintStartAgeMillis, sprintStopToStartMillis,
                    targetKnockbackResistance, attackerKnockbackAttribute,
                    attackerKnockbackEnchantment, attackerPingMs);
        }

        public boolean isFinite() {
            return knockback.isFinite() && velocityBefore.isFinite()
                    && (!appliedVelocityCaptured || appliedVelocity.isFinite())
                    && Double.isFinite(targetKnockbackResistance)
                    && Double.isFinite(attackerKnockbackAttribute);
        }
    }

    public record SprintState(
            boolean sprinting,
            long lastStartedAtNanos,
            long lastStoppedAtNanos
    ) { }
}
