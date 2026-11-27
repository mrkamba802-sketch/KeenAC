package dev.keenac.check;

/** Pure movement-budget calculation, kept separate for deterministic tests. */
public final class NoSlowMovementSpeed {
    private NoSlowMovementSpeed() { }

    public static double allowedItemUseSpeed(
            boolean inWater,
            boolean dolphinsGrace,
            boolean swimming,
            boolean sprinting,
            int depthStrider,
            double movementMultiplier
    ) {
        int depth = Math.max(0, Math.min(3, depthStrider));
        double multiplier = Double.isFinite(movementMultiplier)
                ? Math.max(0.75, Math.min(4.0, movementMultiplier)) : 1.0;

        if (inWater) {
            double fullWaterSpeed;
            if (dolphinsGrace) {
                fullWaterSpeed = 9.8 + 8.983 * depth;
            } else if (swimming && sprinting) {
                fullWaterSpeed = 3.918 * (1.0 + 0.118 * depth);
            } else if (swimming) {
                fullWaterSpeed = 2.20 * (1.0 + depth / 3.0);
            } else {
                fullWaterSpeed = 1.97 * (1.0 + depth / 3.0);
            }
            return fullWaterSpeed * multiplier * 0.2;
        }

        double fullGroundSpeed = 4.317 * multiplier;
        if (sprinting) fullGroundSpeed *= 1.3;
        return fullGroundSpeed * 0.2;
    }
}
