package dev.keenac.mechanics;

/** Client-world movement state in blocks and blocks per tick. */
public record MovementState(
        double x, double y, double z,
        double velocityX, double velocityY, double velocityZ,
        float yaw,
        boolean onGround
) {
    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Double.isFinite(velocityX) && Double.isFinite(velocityY)
                && Double.isFinite(velocityZ) && Float.isFinite(yaw);
    }
}
