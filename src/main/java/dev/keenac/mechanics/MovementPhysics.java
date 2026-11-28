package dev.keenac.mechanics;

/**
 * Deterministic, Bukkit-independent movement budgets shared by movement checks.
 *
 * This is a conservative movement envelope, not a byte-for-byte replacement
 * for the Minecraft client physics integrator. New checks should use these
 * common values instead of inventing their own potion/enchantment multipliers.
 */
public final class MovementPhysics {
    public static final double VANILLA_WALK_SPEED_BPS = 4.317;
    private static final double DEFAULT_WALK_SPEED = 0.2;

    private MovementPhysics() { }

    public static Profile profile(MechanicsSnapshot snapshot, int potionLevelCap,
                                  double attributeMultiplierCap) {
        if (snapshot == null) return Profile.neutral();

        int levelCap = Math.max(1, Math.min(255, potionLevelCap));
        double attributeCap = finiteClamp(attributeMultiplierCap, 1.0, 128.0, 64.0);

        int speedLevel = Math.min(levelCap, snapshot.potionLevel("speed"));
        int slownessLevel = Math.min(levelCap, snapshot.potionLevel("slowness"));
        double potionMultiplier = 1.0;
        if (speedLevel > 0) potionMultiplier *= 1.0 + 0.20 * speedLevel;
        if (slownessLevel > 0) {
            potionMultiplier *= Math.max(0.05, 1.0 - 0.15 * slownessLevel);
        }

        double attributeMultiplier = snapshot.attributeRatio("movement_speed");
        if (!Double.isFinite(attributeMultiplier) || attributeMultiplier <= 0.0) {
            attributeMultiplier = 1.0;
        }
        attributeMultiplier = clamp(attributeMultiplier, 0.05, attributeCap);

        // Bukkit's effective attribute generally includes active modifiers. The
        // explicit potion estimate is a conservative fallback for server forks
        // whose attribute snapshot does not yet include every effect modifier.
        double movementWithoutSprint = clamp(
                Math.max(attributeMultiplier, potionMultiplier), 0.05, attributeCap);
        double sprintMultiplier = snapshot.sprinting() ? 1.30 : 1.0;
        double movementWithSprint = clamp(
                Math.max(attributeMultiplier, potionMultiplier * sprintMultiplier),
                0.05, attributeCap);

        double walkMultiplier = Double.isFinite(snapshot.walkSpeed())
                ? clamp(snapshot.walkSpeed() / DEFAULT_WALK_SPEED, 0.0, 5.0) : 1.0;
        int swiftSneak = Math.min(3, snapshot.enchantmentLevel("leggings", "swift_sneak"));
        double stanceMultiplier = snapshot.sneaking()
                ? Math.min(0.75, 0.30 + 0.15 * swiftSneak) : 1.0;
        if (snapshot.blocking()) stanceMultiplier *= 0.20;

        double weavingMultiplier = snapshot.hasPotionEffect("weaving") ? 2.0 : 1.0;
        double normalSpeed = VANILLA_WALK_SPEED_BPS
                * movementWithSprint * walkMultiplier * stanceMultiplier;

        return new Profile(
                movementWithoutSprint,
                movementWithSprint,
                walkMultiplier,
                stanceMultiplier,
                weavingMultiplier,
                normalSpeed
        );
    }

    public static double allowedCobwebDistance(
            MechanicsSnapshot snapshot,
            Profile profile,
            double elapsedTicks,
            double configuredBasePerTick,
            double bufferPerTick,
            double strictMultiplier
    ) {
        if (snapshot == null || profile == null) return 0.075 * clamp(elapsedTicks, 0.5, 4.0);

        double base = finiteClamp(configuredBasePerTick, 0.052, 0.075, 0.054);
        double buffer = finiteClamp(bufferPerTick, 0.0, 0.002, 0.0015);
        double strict = finiteClamp(strictMultiplier, 1.0, 1.08, 1.035);
        double stanceBuffer = Math.max(0.20, profile.stanceMultiplier());

        return (base * profile.movementWithSprint()
                * profile.walkSpeedMultiplier()
                * profile.stanceMultiplier()
                * profile.weavingMultiplier()
                * strict
                + buffer * stanceBuffer)
                * clamp(elapsedTicks, 0.5, 4.0);
    }

    public static double allowedItemUseSpeed(
            MechanicsSnapshot snapshot,
            boolean includePotionEffects,
            int potionLevelCap,
            double attributeMultiplierCap
    ) {
        return allowedItemUseSpeed(snapshot, snapshot != null && snapshot.inWater(),
                includePotionEffects, potionLevelCap, attributeMultiplierCap);
    }

    public static double allowedItemUseSpeed(
            MechanicsSnapshot snapshot,
            boolean inWaterOverride,
            boolean includePotionEffects,
            int potionLevelCap,
            double attributeMultiplierCap
    ) {
        if (snapshot == null) return 4.317 * 0.2;
        Profile profile = profile(snapshot, potionLevelCap, attributeMultiplierCap);
        double movementMultiplier = includePotionEffects
                ? profile.movementWithoutSprint() : 1.0;
        return allowedItemUseSpeed(
                inWaterOverride || snapshot.inWater(),
                snapshot.hasPotionEffect("dolphins_grace"),
                snapshot.swimming(),
                snapshot.sprinting(),
                snapshot.enchantmentLevel("boots", "depth_strider"),
                movementMultiplier
        );
    }

    /** Shared item-use speed calculation; kept parameterized for regression tests; kept parameterized for regression tests. */
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
                ? clamp(movementMultiplier, 0.05, 128.0) : 1.0;

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

        double fullGroundSpeed = VANILLA_WALK_SPEED_BPS * multiplier;
        if (sprinting) fullGroundSpeed *= 1.3;
        return fullGroundSpeed * 0.2;
    }

    public static boolean hasUnsupportedMovementSurface(WorldMechanicsSnapshot world) {
        return world == null || !world.chunkDataComplete()
                || world.hasAlternativeMovementSurface();
    }

    private static double finiteClamp(double value, double minimum, double maximum, double fallback) {
        return Double.isFinite(value) ? clamp(value, minimum, maximum) : fallback;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record Profile(
            double movementWithoutSprint,
            double movementWithSprint,
            double walkSpeedMultiplier,
            double stanceMultiplier,
            double weavingMultiplier,
            double normalHorizontalSpeedBps
    ) {
        public static Profile neutral() {
            return new Profile(1.0, 1.0, 1.0, 1.0, 1.0, VANILLA_WALK_SPEED_BPS);
        }
    }
}
