package dev.keenac.check;

import dev.keenac.mechanics.MovementPhysics;

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
        return MovementPhysics.allowedItemUseSpeed(
                inWater, dolphinsGrace, swimming, sprinting, depthStrider, movementMultiplier);
    }
}
