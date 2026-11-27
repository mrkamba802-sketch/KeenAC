package dev.keenac.context;

/**
 * Immutable snapshot of latency and movement-relevant state captured on the
 * Bukkit primary thread. Ages are -1 when no matching event was observed.
 */
public record PlayerContextSnapshot(
        long capturedAtMillis,
        int pingMs,
        double tps,
        String world,
        double x,
        double y,
        double z,
        double velocityX,
        double velocityY,
        double velocityZ,
        boolean onGround,
        boolean sprinting,
        boolean sneaking,
        boolean swimming,
        boolean inWater,
        boolean flying,
        boolean gliding,
        boolean inVehicle,
        String gameMode,
        String potionEffects,
        long teleportAgeMs,
        long velocityAgeMs
) { }
