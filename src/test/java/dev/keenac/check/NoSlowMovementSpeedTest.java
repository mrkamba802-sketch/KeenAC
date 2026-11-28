package dev.keenac.check;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NoSlowMovementSpeedTest {
    @Test
    void calculatesNormalGroundMovementBudget() {
        assertEquals(4.317 * 0.2,
                NoSlowMovementSpeed.allowedItemUseSpeed(
                        false, false, false, false, 0, 1.0),
                1.0E-9);
    }

    @Test
    void appliesSprintMultiplierOnLand() {
        double walking = NoSlowMovementSpeed.allowedItemUseSpeed(
                false, false, false, false, 0, 1.0);
        double sprinting = NoSlowMovementSpeed.allowedItemUseSpeed(
                false, false, false, true, 0, 1.0);

        assertEquals(walking * 1.3, sprinting, 1.0E-9);
    }

    @Test
    void calculatesSwimmingAndDepthStriderSeparatelyFromPotionEffects() {
        double withoutDepthStrider = NoSlowMovementSpeed.allowedItemUseSpeed(
                true, false, true, true, 0, 1.0);
        double withDepthStrider = NoSlowMovementSpeed.allowedItemUseSpeed(
                true, false, true, true, 3, 1.0);

        assertTrue(withDepthStrider > withoutDepthStrider);
        assertEquals(3.918 * (1.0 + 0.118 * 3) * 0.2,
                withDepthStrider, 1.0E-9);
    }

    @Test
    void dolphinGraceOnlyChangesTheWaterAllowanceWhenEnabledByCaller() {
        double ordinarySwimming = NoSlowMovementSpeed.allowedItemUseSpeed(
                true, false, true, false, 0, 1.0);
        double dolphinSwimming = NoSlowMovementSpeed.allowedItemUseSpeed(
                true, true, true, false, 0, 1.0);

        assertTrue(dolphinSwimming > ordinarySwimming);
        assertEquals(9.8 * 0.2, dolphinSwimming, 1.0E-9);
    }

    @Test
    void boundsInvalidMovementMultipliersAndDepthStriderLevels() {
        double invalidMultiplier = NoSlowMovementSpeed.allowedItemUseSpeed(
                false, false, false, false, 0, Double.NaN);
        double defaultMultiplier = NoSlowMovementSpeed.allowedItemUseSpeed(
                false, false, false, false, 0, 1.0);
        double excessiveDepth = NoSlowMovementSpeed.allowedItemUseSpeed(
                true, false, true, true, 99, 1.0);
        double maxDepth = NoSlowMovementSpeed.allowedItemUseSpeed(
                true, false, true, true, 3, 1.0);

        assertEquals(defaultMultiplier, invalidMultiplier, 1.0E-9);
        assertEquals(maxDepth, excessiveDepth, 1.0E-9);
    }
}
