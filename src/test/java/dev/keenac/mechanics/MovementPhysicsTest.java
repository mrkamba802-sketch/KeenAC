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
    void itemUseBudgetHonorsWalkSpeedAndSwiftSneak() {
        MechanicsSnapshot defaultWalk = snapshot(Map.of(), Map.of(), Map.of(),
                false, false, 0.2, new MechanicsSnapshot.AttributeState(0.1, 0.1));
        MechanicsSnapshot fastWalk = snapshot(Map.of(), Map.of(), Map.of(),
                false, false, 0.4, new MechanicsSnapshot.AttributeState(0.1, 0.1));
        MechanicsSnapshot sneaking = snapshot(Map.of(), Map.of(),
                Map.of("leggings:swift_sneak", 3), true, false, 0.2,
                new MechanicsSnapshot.AttributeState(0.1, 0.1));

        double baseline = MovementPhysics.allowedItemUseSpeed(defaultWalk, false, 100, 64.0);
        double fast = MovementPhysics.allowedItemUseSpeed(fastWalk, false, 100, 64.0);
        double sneakingBudget = MovementPhysics.allowedItemUseSpeed(sneaking, false, 100, 64.0);

        assertEquals(baseline * 2.0, fast, 1.0E-9);
        assertTrue(sneakingBudget > 0.0);
        assertTrue(sneakingBudget < baseline);
    }

    @Test
    void alternativeBlocksAreMarkedAsUnsuitableForSimpleMovementPrediction() {
        WorldMechanicsSnapshot world = new WorldMechanicsSnapshot(
                "world", "ICE", "AIR", "AIR", Set.of("ICE"),
                false, false, false, false, true, java.util.List.of(), 0.6);

        assertTrue(MovementPhysics.hasUnsupportedMovementSurface(world));
    }

    @Test
    void movementSimulatorConvergesToVanillaGroundSpeedAndKeepsGroundContact() {
        // Long, flat runway: the simulator must not fall off the test platform.
        MechanicsSnapshot mechanics = simulationSnapshot(java.util.List.of(
                new CollisionBox(-5, 63, -100, 5, 64, 100)), 0.6);
        MovementState initial = new MovementState(0.0, 64.0, 0.0,
                0.0, 0.0, 0.0, 0.0f, true);
        MovementInput forward = new MovementInput(
                true, false, false, false, false, false, false, 1L);

        MovementPhysicsSimulator.Result result = simulateRepeated(
                initial, forward, mechanics, 60);

        assertTrue(result.state().z() > 10.0);
        assertEquals(4.317 / 20.0, result.state().velocityZ(), 0.002);
        assertTrue(result.state().onGround());
    }

    @Test
    void movementSimulatorClipsThePlayerAgainstAFullBlockWall() {
        MechanicsSnapshot mechanics = simulationSnapshot(java.util.List.of(
                new CollisionBox(-5, 63, -5, 5, 64, 5),
                new CollisionBox(1, 64, -1, 2, 66, 1)), 0.6);
        MovementState initial = new MovementState(0.5, 64.0, 0.0,
                0.0, 0.0, 0.0, 0.0f, true);
        MovementInput right = new MovementInput(
                false, false, false, true, false, false, false, 1L);

        MovementPhysicsSimulator.Result result = simulateRepeated(
                initial, right, mechanics, 60);

        assertTrue(result.collidedHorizontally());
        assertTrue(result.state().x() <= 0.700001);
    }

    @Test
    void movementSimulatorPredictsJumpImpulseAndAirborneGravity() {
        MechanicsSnapshot mechanics = simulationSnapshot(java.util.List.of(
                new CollisionBox(-5, 63, -5, 5, 64, 5)), 0.6);
        MovementState initial = new MovementState(0.0, 64.0, 0.0,
                0.0, 0.0, 0.0, 0.0f, true);
        MovementInput jump = new MovementInput(
                false, false, false, false, true, false, false, 1L);

        MovementPhysics.Profile profile = MovementPhysics.profile(mechanics, 100, 64.0);
        MovementPhysicsSimulator.Result takeoff = MovementPhysicsSimulator.simulate(
                initial, jump, mechanics, profile, 1);

        assertFalse(takeoff.state().onGround());
        assertEquals(64.3332, takeoff.state().y(), 1.0E-6);
        assertEquals(0.3332, takeoff.state().velocityY(), 1.0E-6);

        MovementInput noJump = new MovementInput(
                false, false, false, false, false, false, false, 2L);
        MovementPhysicsSimulator.Result airborne = MovementPhysicsSimulator.simulate(
                takeoff.state(), noJump, mechanics, profile, 1);

        assertTrue(airborne.state().y() > takeoff.state().y());
        assertEquals(0.248136, airborne.state().velocityY(), 1.0E-6);
        assertFalse(airborne.state().onGround());
    }

    @Test
    void movementSimulatorDetectsLandingAfterAJumpArc() {
        MechanicsSnapshot mechanics = simulationSnapshot(java.util.List.of(
                new CollisionBox(-5, 63, -5, 5, 64, 5)), 0.6);
        MovementState falling = new MovementState(0.0, 64.45, 0.0,
                0.0, -0.45, 0.0, 0.0f, false);
        MovementInput idle = new MovementInput(
                false, false, false, false, false, false, false, 1L);

        MovementPhysicsSimulator.Result landed = simulateRepeated(
                falling, idle, mechanics, 12);

        assertTrue(landed.state().onGround());
        assertEquals(64.0, landed.state().y(), 1.0E-6);
        assertEquals(0.0, landed.state().velocityY(), 1.0E-9);
        assertTrue(landed.collidedVertically());
    }

    private MovementPhysicsSimulator.Result simulateRepeated(
            MovementState initial, MovementInput input, MechanicsSnapshot mechanics, int ticks
    ) {
        MovementPhysics.Profile profile = MovementPhysics.profile(mechanics, 100, 64.0);
        MovementState state = initial;
        boolean horizontalCollision = false;
        boolean verticalCollision = false;
        for (int tick = 0; tick < ticks; tick++) {
            MovementPhysicsSimulator.Result result = MovementPhysicsSimulator.simulate(
                    state, input, mechanics, profile, 1);
            state = result.state();
            horizontalCollision |= result.collidedHorizontally();
            verticalCollision |= result.collidedVertically();
        }
        return new MovementPhysicsSimulator.Result(state, horizontalCollision, verticalCollision);
    }

    private MechanicsSnapshot simulationSnapshot(
            java.util.List<CollisionBox> collisions, double slipperiness
    ) {
        WorldMechanicsSnapshot world = new WorldMechanicsSnapshot(
                "world", "STONE", "AIR", "AIR", Set.of(),
                false, false, false, false, true, collisions, slipperiness);
        return new MechanicsSnapshot(
                UUID.randomUUID(), "world", 1L,
                0.0, 64.0, 0.0, 0.0, 0.0, 0.0,
                50, 20.0, "SURVIVAL",
                true, false, false, false, false, false,
                false, false, false, false, false, false,
                0.0f, 0.2,
                "AIR", "AIR", "AIR", "AIR", "AIR", "AIR", "AIR",
                "CRAFTING", 0,
                Map.of(), Map.of("movement_speed",
                        new MechanicsSnapshot.AttributeState(0.1, 0.1)),
                Map.of(), world, -1, -1, -1, -1, -1);
    }

    @Test
    void cobwebIsNotTreatedAsAnAlternativeSurfaceForTheNoWebCheck() {
        WorldMechanicsSnapshot web = new WorldMechanicsSnapshot(
                "world", "STONE", "COBWEB", "AIR", Set.of("COBWEB"),
                false, false, false, true, true, java.util.List.of(), 0.6);

        assertFalse(web.hasAlternativeMovementSurface());
        assertTrue(web.inCobweb());
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
                        Set.of(), false, false, false, false, true, java.util.List.of(), 0.6),
                -1, -1, -1, -1, -1
        );
    }
}
