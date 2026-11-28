package dev.keenac.mechanics;

/**
 * Conservative impulse helpers for server-authoritative velocity events.
 * The current player velocity seeds the simulator; this class does not assume
 * every damage or velocity event is knockback.
 */
public final class KnockbackPhysics {
    private static final double AIRBORNE_Y_THRESHOLD = 0.03;

    private KnockbackPhysics() { }

    /**
     * A recent, sufficiently strong server velocity event provides the context
     * needed to keep prediction active during the generic post-damage quiet
     * window. Without that event, recent damage remains a prediction suppressor.
     */
    public static boolean allowsPredictionDuringDamageGrace(
            long damageAgeMillis, long gracePeriodMillis, boolean knockbackActive
    ) {
        return knockbackActive
                || damageAgeMillis < 0L
                || damageAgeMillis >= Math.max(0L, gracePeriodMillis);
    }

    public static boolean isRecentImpulse(
            Impulse impulse, long nowNanos, long maxAgeMillis, double minimumSpeed
    ) {
        if (impulse == null || maxAgeMillis <= 0L || !impulse.isFinite()
                || !Double.isFinite(minimumSpeed) || minimumSpeed < 0.0) {
            return false;
        }
        long ageNanos = nowNanos - impulse.timestampNanos();
        if (ageNanos < 0L || ageNanos > maxAgeMillis * 1_000_000L) {
            return false;
        }
        double speedSquared = impulse.velocityX() * impulse.velocityX()
                + impulse.velocityY() * impulse.velocityY()
                + impulse.velocityZ() * impulse.velocityZ();
        return Double.isFinite(speedSquared) && speedSquared >= minimumSpeed * minimumSpeed;
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
}
