package dev.keenac.mechanics;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MovementPhysicsTest {
    @Test
    void preservesPotionLevelsThroughOneHundredAndUsesThemInTheMovementBudget() {
        MechanicsSnapshot snapshot = snapshot(
                Map.of("speed", new MechanicsSnapshot.EffectState(100, 1200, false, true, true)),
                Map.of(), Map.of(), false, false, 0.2,
                new MechanicsSnapshot.AttributeState(0.1, 0.1));

        MovementPhysics.Profile profile = MovementPhysics.profile(snapshot, 100, 64.0);

        assertEquals(100, snapshot.potionLevel("speed"));
        assertEquals(21.0, profile.movementWithoutSprint(), 1.0E-9);
        assertEquals(4.317 * 21.0, profile.normalHorizontalSpeedBps(), 1.0E-9);
    }

    @Test
    void serverAttributeModifiersAreNotOverriddenByLowerPotionEstimates() {
        MechanicsSnapshot snapshot = snapshot(
                Map.of("speed", new MechanicsSnapshot.EffectState(2, 600, false, true, true)),
                Map.of(), Map.of(), false, false, 0.2,
                new MechanicsSnapshot.AttributeState(0.1, 0.8));

        MovementPhysics.Profile profile = MovementPhysics.profile(snapshot, 100, 64.0);

        assertEquals(8.0, profile.movementWithoutSprint(), 1.0E-9);
    }

    @Test
    void swiftSneakIsReadFromLeggingsAndChangesTheSneakingBudget() {
        MechanicsSnapshot ordinary = snapshot(Map.of(), Map.of(), Map.of(),
                true, false, 0.2, new MechanicsSnapshot.AttributeState(0.1, 0.1));
        MechanicsSnapshot swiftSneak = snapshot(Map.of(), Map.of(),
                Map.of("leggings:swift_sneak", 3), true, false, 0.2,
                new MechanicsSnapshot.AttributeState(0.1, 0.1));

        double normalAllowance = MovementPhysics.profile(ordinary, 100, 64.0).normalHorizontalSpeedBps();
        double enchantedAllowance = MovementPhysics.profile(swiftSneak, 100, 64.0).normalHorizontalSpeedBps();

        assertEquals(0.30, MovementPhysics.profile(ordinary, 100, 64.0).stanceMultiplier(), 1.0E-9);
        assertEquals(0.75, MovementPhysics.profile(swiftSneak, 100, 64.0).stanceMultiplier(), 1.0E-9);
        assertTrue(enchantedAllowance > normalAllowance);
    }

    @Test
    void alternativeBlocksAreMarkedAsUnsuitableForSimpleMovementPrediction() {
        WorldMechanicsSnapshot world = new WorldMechanicsSnapshot(
                "world", "ICE", "AIR", "AIR", Set.of("ICE"),
                false, false, false, false, true);

        assertTrue(MovementPhysics.hasUnsupportedMovementSurface(world));
    }

    @Test
    void cobwebAllowanceScalesWithLegitimatePotionAndWeavingMechanics() {
        MechanicsSnapshot ordinary = snapshot(Map.of(), Map.of(), Map.of(),
                false, false, 0.2, new MechanicsSnapshot.AttributeState(0.1, 0.1));
        MechanicsSnapshot boosted = snapshot(
                Map.of(
                        "speed", new MechanicsSnapshot.EffectState(4, 600, false, true, true),
                        "weaving", new MechanicsSnapshot.EffectState(1, 600, false, true, true)),
                Map.of(), Map.of(), false, false, 0.2,
                new MechanicsSnapshot.AttributeState(0.1, 0.1));

        double ordinaryBudget = MovementPhysics.allowedCobwebDistance(
                ordinary, MovementPhysics.profile(ordinary, 100, 64.0), 1.0, 0.054, 0.0015, 1.035);
        double boostedBudget = MovementPhysics.allowedCobwebDistance(
                boosted, MovementPhysics.profile(boosted, 100, 64.0), 1.0, 0.054, 0.0015, 1.035);

        assertTrue(Double.isFinite(boostedBudget));
        assertTrue(boostedBudget > ordinaryBudget);
    }

    private MechanicsSnapshot snapshot(
            Map<String, MechanicsSnapshot.EffectState> effects,
            Map<String, MechanicsSnapshot.AttributeState> attributes,
            Map<String, Integer> enchantments,
            boolean sneaking,
            boolean sprinting,
            double walkSpeed,
            MechanicsSnapshot.AttributeState movement
    ) {
        Map<String, MechanicsSnapshot.AttributeState> fullAttributes =
                Map.of("movement_speed", movement);
        return new MechanicsSnapshot(
                UUID.randomUUID(), "world", 1L,
                0.0, 64.0, 0.0, 0.0, 0.0, 0.0,
                50, 20.0, "SURVIVAL",
                true, sprinting, sneaking, false, false, false,
                false, false, false, false, false, false,
                0.0f, walkSpeed,
                "AIR", "AIR", "AIR", "AIR", "AIR", "AIR", "AIR",
                "CRAFTING", 0,
                effects, fullAttributes, enchantments,
                new WorldMechanicsSnapshot("world", "STONE", "AIR", "AIR",
                        Set.of(), false, false, false, false, true),
                -1, -1, -1, -1, -1
        );
    }
}
