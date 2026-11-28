package dev.keenac.mechanics;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class KnockbackPhysicsTest {
    @Test
    void acceptsOnlyRecentFiniteVelocityImpulsesAboveTheConfiguredMinimum() {
        long now = 5_000_000_000L;
        KnockbackPhysics.Impulse recent = new KnockbackPhysics.Impulse(
                0.35, 0.42, -0.10, now - 300_000_000L);
        KnockbackPhysics.Impulse old = new KnockbackPhysics.Impulse(
                0.35, 0.42, -0.10, now - 1_500_000_000L);
        KnockbackPhysics.Impulse weak = new KnockbackPhysics.Impulse(
                0.01, 0.01, 0.0, now - 100_000_000L);
        KnockbackPhysics.Impulse invalid = new KnockbackPhysics.Impulse(
                Double.NaN, 0.4, 0.0, now - 100_000_000L);
        KnockbackPhysics.Impulse negativeClock = new KnockbackPhysics.Impulse(
                0.35, 0.42, -0.10, -800_000_000L);
        KnockbackPhysics.Impulse future = new KnockbackPhysics.Impulse(
                0.35, 0.42, -0.10, now + 1L);

        assertTrue(KnockbackPhysics.isRecentImpulse(recent, now, 1200L, 0.06));
        assertFalse(KnockbackPhysics.isRecentImpulse(old, now, 1200L, 0.06));
        assertFalse(KnockbackPhysics.isRecentImpulse(weak, now, 1200L, 0.06));
        assertFalse(KnockbackPhysics.isRecentImpulse(invalid, now, 1200L, 0.06));
        assertFalse(KnockbackPhysics.isRecentImpulse(future, now, 1200L, 0.06));
        assertTrue(KnockbackPhysics.isRecentImpulse(
                negativeClock, -500_000_000L, 1200L, 0.06));
    }

    @Test
    void recentDamageOnlySuppressesPredictionWhenNoStrongVelocityImpulseWasObserved() {
        assertFalse(KnockbackPhysics.allowsPredictionDuringDamageGrace(100L, 500L, false));
        assertTrue(KnockbackPhysics.allowsPredictionDuringDamageGrace(100L, 500L, true));
        assertTrue(KnockbackPhysics.allowsPredictionDuringDamageGrace(-1L, 500L, false));
        assertTrue(KnockbackPhysics.allowsPredictionDuringDamageGrace(500L, 500L, false));
        assertTrue(KnockbackPhysics.allowsPredictionDuringDamageGrace(100L, 0L, false));
    }

    @Test
    void pingExtendsImpulseWindowWithoutAllowingUnboundedAge() {
        assertEquals(1_400L, KnockbackPhysics.effectiveImpulseWindowMillis(1200L, 200, 500L));
        assertEquals(1_700L, KnockbackPhysics.effectiveImpulseWindowMillis(1200L, 900, 500L));
        assertEquals(1200L, KnockbackPhysics.effectiveImpulseWindowMillis(1200L, -10, 500L));

        long now = 5_000_000_000L;
        KnockbackPhysics.Impulse delayed = new KnockbackPhysics.Impulse(
                0.35, 0.42, -0.10, now - 1_350_000_000L);
        assertFalse(KnockbackPhysics.isRecentImpulse(delayed, now, 1200L, 0.06));
        assertTrue(KnockbackPhysics.isRecentImpulse(
                delayed, now, 1200L, 200, 500L, 0.06));
    }

    @Test
    void highPingWidensPacketGapOnlyToTheConfiguredCap() {
        assertEquals(175_000_000L,
                KnockbackPhysics.maxPredictionIntervalNanos(100, 125_000_000L, 125_000_000L));
        assertEquals(250_000_000L,
                KnockbackPhysics.maxPredictionIntervalNanos(500, 125_000_000L, 125_000_000L));
        assertEquals(125_000_000L,
                KnockbackPhysics.maxPredictionIntervalNanos(-20, 125_000_000L, 125_000_000L));
    }

    @Test
    void sprintResetRequiresARecentStopThenStartWhileAttackerIsSprinting() {
        assertTrue(KnockbackPhysics.isSprintResetWithinWindow(true, 80L, 120L, 300L));
        assertFalse(KnockbackPhysics.isSprintResetWithinWindow(false, 80L, 120L, 300L));
        assertFalse(KnockbackPhysics.isSprintResetWithinWindow(true, 301L, 120L, 300L));
        assertFalse(KnockbackPhysics.isSprintResetWithinWindow(true, 80L, 301L, 300L));
        assertFalse(KnockbackPhysics.isSprintResetWithinWindow(true, -1L, 120L, 300L));
    }

    @Test
    void pairsCombatKnockbackWithTheFollowingAuthoritativeVelocityWithoutEquatingThem() {
        KnockbackPhysics.CombatImpulse combat = new KnockbackPhysics.CombatImpulse(
                new KnockbackPhysics.MotionVector(0.35, 0.32, -0.12),
                new KnockbackPhysics.MotionVector(0.04, 0.0, 0.02),
                KnockbackPhysics.MotionVector.zero(),
                false, 0L, 1_000L, "ENTITY_ATTACK", UUID.randomUUID(),
                true, true, 80L, 120L, 0.0, 0.1, 1, 45);

        KnockbackPhysics.CombatImpulse paired = combat.withAppliedVelocity(
                new KnockbackPhysics.MotionVector(0.30, 0.28, -0.10), 1_020L);

        assertFalse(combat.appliedVelocityCaptured());
        assertTrue(paired.appliedVelocityCaptured());
        assertEquals(0.30, paired.appliedVelocity().x(), 1.0E-9);
        assertEquals(0.35, paired.knockback().x(), 1.0E-9);
        assertTrue(paired.sprintReset());
        assertTrue(KnockbackPhysics.isRecentCombatImpulse(
                paired, 1_100L, 1200L, 45, 500L, 0.06));
    }

    @Test
    void seedsPredictionWithServerVelocityAndAirborneState() {
        MechanicsSnapshot mechanics = snapshot(0.42, 0.36, -0.18);
        MovementState state = KnockbackPhysics.seedState(
                10.0, 64.0, -3.0, 90.0f, true, mechanics);

        assertEquals(0.42, state.velocityX(), 1.0E-9);
        assertEquals(0.36, state.velocityY(), 1.0E-9);
        assertEquals(-0.18, state.velocityZ(), 1.0E-9);
        assertEquals(90.0f, state.yaw(), 1.0E-6);
        assertFalse(state.onGround());
    }

    @Test
    void horizontalImpulseKeepsGroundContactWhenTheClientReportsGrounded() {
        MechanicsSnapshot mechanics = snapshot(0.42, 0.0, -0.18);
        MovementState state = KnockbackPhysics.seedState(
                1.0, 64.0, 1.0, -45.0f, true, mechanics);

        assertTrue(state.onGround());
        assertEquals(0.42, state.velocityX(), 1.0E-9);
        assertEquals(-0.18, state.velocityZ(), 1.0E-9);
    }

    private MechanicsSnapshot snapshot(double velocityX, double velocityY, double velocityZ) {
        WorldMechanicsSnapshot world = new WorldMechanicsSnapshot(
                "world", "STONE", "AIR", "AIR", Set.of(),
                false, false, false, false, true, java.util.List.of(), 0.6);
        return new MechanicsSnapshot(
                UUID.randomUUID(), "world", 1L,
                0.0, 64.0, 0.0, velocityX, velocityY, velocityZ,
                50, 20.0, "SURVIVAL",
                true, false, false, false, false, false,
                false, false, false, false, false, false,
                0.0f, 0.2,
                "AIR", "AIR", "AIR", "AIR", "AIR", "AIR", "AIR",
                "CRAFTING", 0,
                Map.of(), Map.of("movement_speed",
                        new MechanicsSnapshot.AttributeState(0.1, 0.1)),
                Map.of(), world, -1, 600, 600, -1, -1);
    }
}
