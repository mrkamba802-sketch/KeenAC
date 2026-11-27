package dev.keenac.engine.core.physics;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import dev.keenac.engine.core.player.TrackedPlayer;
public final class PredictionEngine {
    private static final double GRAVITY_SLOW_FALLING = 0.01;
    private static final double WATER_GRAVITY_OFFSET = 0.02;
    private static final double LAVA_GRAVITY_OFFSET = 0.02;
    private static final double LEVITATION_STRENGTH = 0.05;
    private static final double WATER_DRAG_VERTICAL = 0.8;
    private static final double LAVA_DRAG_VERTICAL = 0.5;
    private static final double HONEY_MAX_DELTA_Y = -0.5;
    private static final double CLIMB_MAX_DELTA_Y = 0.15;
    private static final String POTION_SPEED = "SPEED";
    private static final String POTION_SLOWNESS_OLD = "SLOW";
    private static final String POTION_SLOWNESS = "SLOWNESS";
    private static final String POTION_SLOW_FALLING = "SLOW_FALLING";
    private static final String POTION_LEVITATION = "LEVITATION";
    private static final int SPEED_POTION_MAX_LEVEL = 5;
    private static final int SLOWNESS_POTION_MAX_LEVEL = 4;
    private static final double SPEED_POTION_MULT = 0.20;
    private static final double SLOWNESS_POTION_MULT = 0.15;
    private static final double GROUND_ACCEL_FACTOR = 0.16277136;
    private static final double AIR_ACCEL_FACTOR = 0.026;
    private PredictionEngine() {}
    public static boolean isMovementPacket(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        return type == PacketType.Play.Client.PLAYER_FLYING
                || type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
    }
    public static double calculateBaseSpeed(boolean sprinting, boolean sneaking,
                                            double speedMultiplier, double slownessMultiplier,
                                            dev.keenac.engine.core.player.TrackedPlayer player) {
        return calculateBaseSpeed(sprinting, sneaking, speedMultiplier, slownessMultiplier,
                player.getAttributeSnapshot());
    }
    public static double calculateBaseSpeed(boolean sprinting, boolean sneaking,
                                            double speedMultiplier, double slownessMultiplier,
                                            dev.keenac.engine.core.player.AttributeSnapshot attributes) {
        double speed = attributes == null ? PhysicsConstants.PLAYER_WALK_SPEED : attributes.movementSpeed();
        if (sprinting) speed *= PhysicsConstants.PLAYER_SPRINT_MULTIPLIER;
        if (sneaking) {
            double sneak = attributes == null ? PhysicsConstants.PLAYER_CROUCH_MULTIPLIER
                    : attributes.sneakingSpeed();
            speed *= Math.max(0.1, Math.min(1.0, sneak));
        }
        speed *= speedMultiplier;
        speed *= slownessMultiplier;
        return speed;
    }
    public static double calculateHorizontalSpeed(double deltaX, double deltaZ) {
        return Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
    }
    public static double calculateBaseSpeed(boolean sprinting, boolean sneaking,
                                             double speedMultiplier, double slownessMultiplier) {
        double speed = PhysicsConstants.PLAYER_WALK_SPEED;
        if (sprinting) speed *= PhysicsConstants.PLAYER_SPRINT_MULTIPLIER;
        if (sneaking) speed *= PhysicsConstants.PLAYER_CROUCH_MULTIPLIER;
        speed *= speedMultiplier;
        speed *= slownessMultiplier;
        return speed;
    }
    public static double calculateMaxHorizontalSpeed(double baseSpeed, double lastHorizontalSpeed,
                                                      boolean onGround, boolean climbing,
                                                      boolean swimming, int protocol) {
        boolean inWeb = climbing && !swimming;
        double friction;
        if (onGround) {
            friction = PhysicsConstants.GROUND_FRICTION;
        } else {
            friction = PhysicsConstants.AIR_DRAG;
        }
        if (inWeb) friction = Math.min(friction, PhysicsConstants.WEB_FRICTION);
        double maxAccel;
        if (onGround) {
            double accelFactor = GROUND_ACCEL_FACTOR / (friction * friction * friction);
            maxAccel = baseSpeed * accelFactor;
        } else {
            maxAccel = baseSpeed * AIR_ACCEL_FACTOR;
        }
        if (swimming) maxAccel *= 0.9;
        double maxSpeed = lastHorizontalSpeed * friction + maxAccel;
        return maxSpeed;
    }
    public static double calculateMaxHorizontalSpeed(double baseSpeed, double lastHorizontalSpeed,
                                                      boolean onGround, boolean climbing,
                                                      boolean swimming, int protocol, double deltaY) {
        boolean inWeb = climbing && !swimming;
        double friction;
        if (onGround) {
            friction = PhysicsConstants.GROUND_FRICTION;
        } else {
            friction = PhysicsConstants.AIR_DRAG;
        }
        if (inWeb) friction = Math.min(friction, PhysicsConstants.WEB_FRICTION);
        double maxAccel;
        if (onGround) {
            double accelFactor = GROUND_ACCEL_FACTOR / (friction * friction * friction);
            maxAccel = baseSpeed * accelFactor;
        } else {
            maxAccel = baseSpeed * AIR_ACCEL_FACTOR;
        }
        if (swimming) maxAccel *= 0.9;
        double maxSpeed = lastHorizontalSpeed * friction + maxAccel;
        if (swimming && protocol >= 393) {
            double swimBoost = 0.01 * Math.max(0, deltaY);
            maxSpeed += swimBoost;
        }
        return maxSpeed;
    }
    public static double calculateMaxHorizontalSpeed(double baseSpeed, double lastHorizontalSpeed,
                                                      boolean onGround, boolean climbing,
                                                      boolean swimming, int protocol,
                                                      dev.keenac.engine.core.player.AttributeSnapshot attributes) {
        if (attributes == null) {
            return calculateMaxHorizontalSpeed(baseSpeed, lastHorizontalSpeed, onGround,
                    climbing, swimming, protocol);
        }
        double friction = onGround ? PhysicsConstants.GROUND_FRICTION : PhysicsConstants.AIR_DRAG;
        friction *= onGround ? attributes.frictionModifier() : attributes.airDragModifier();
        friction = Math.max(0.1, Math.min(1.25, friction));
        if (climbing && !swimming) friction = Math.min(friction, PhysicsConstants.WEB_FRICTION);
        double acceleration;
        if (onGround) {
            acceleration = baseSpeed * GROUND_ACCEL_FACTOR / Math.max(0.01, friction * friction * friction);
        } else {
            acceleration = baseSpeed * AIR_ACCEL_FACTOR;
        }
        if (swimming) {
            double waterEfficiency = Math.max(0.0, Math.min(1.0, attributes.waterMovementEfficiency()));
            acceleration *= 0.9 + waterEfficiency * 0.5;
        }
        double movementEfficiency = Math.max(0.0, Math.min(1.0, attributes.movementEfficiency()));
        acceleration *= 1.0 + movementEfficiency * 0.1;
        return lastHorizontalSpeed * friction + acceleration;
    }
    public static double predictDeltaY(double currentExpectedDeltaY, boolean inWater, boolean inLava,
                                        boolean climbing, boolean onHoney, boolean hasSlowFalling,
                                        boolean hasLevitation, double levitationAmplifier,
                                        boolean isFallFlying, boolean hasRiptide) {
        double gravity = hasSlowFalling ? GRAVITY_SLOW_FALLING : PhysicsConstants.GRAVITY;
        if (inWater) {
            return currentExpectedDeltaY * WATER_DRAG_VERTICAL - WATER_GRAVITY_OFFSET;
        } else if (inLava) {
            return currentExpectedDeltaY * LAVA_DRAG_VERTICAL - LAVA_GRAVITY_OFFSET;
        } else if (climbing) {
            double result = currentExpectedDeltaY;
            if (currentExpectedDeltaY > CLIMB_MAX_DELTA_Y) result = CLIMB_MAX_DELTA_Y;
            return result;
        } else if (onHoney) {
            return Math.max(currentExpectedDeltaY, HONEY_MAX_DELTA_Y);
        } else if (isFallFlying || hasRiptide) {
            return currentExpectedDeltaY;
        } else if (hasLevitation) {
            return currentExpectedDeltaY + LEVITATION_STRENGTH * levitationAmplifier;
        } else {
            return (currentExpectedDeltaY - gravity) * PhysicsConstants.AIR_DRAG;
        }
    }
    public static double predictDeltaY(double currentExpectedDeltaY, boolean inWater, boolean inLava,
                                        boolean climbing, boolean onHoney, boolean hasSlowFalling,
                                        boolean hasLevitation, double levitationAmplifier,
                                        boolean isFallFlying, boolean hasRiptide,
                                        double effectiveGravity) {
        double gravity = hasSlowFalling ? GRAVITY_SLOW_FALLING
                : (Double.isFinite(effectiveGravity) ? Math.max(0.001, effectiveGravity) : PhysicsConstants.GRAVITY);
        if (inWater) return currentExpectedDeltaY * WATER_DRAG_VERTICAL - WATER_GRAVITY_OFFSET;
        if (inLava) return currentExpectedDeltaY * LAVA_DRAG_VERTICAL - LAVA_GRAVITY_OFFSET;
        if (climbing) return Math.min(currentExpectedDeltaY, CLIMB_MAX_DELTA_Y);
        if (onHoney) return Math.max(currentExpectedDeltaY, HONEY_MAX_DELTA_Y);
        if (isFallFlying || hasRiptide) return currentExpectedDeltaY;
        if (hasLevitation) return currentExpectedDeltaY + LEVITATION_STRENGTH * levitationAmplifier;
        return (currentExpectedDeltaY - gravity) * PhysicsConstants.AIR_DRAG;
    }
    public static boolean checkInWater(TrackedPlayer player) {
        return player.isCachedInWater();
    }
    public static boolean checkInLava(TrackedPlayer player) {
        return player.isCachedInLava();
    }
    public static boolean checkOnHoney(TrackedPlayer player) {
        return player.isCachedOnHoney();
    }
    public static double getSpeedPotionMultiplier(TrackedPlayer player) {
        return player.getCachedSpeedMultiplier();
    }
    public static double getSlownessPotionMultiplier(TrackedPlayer player) {
        return player.getCachedSlownessMultiplier();
    }
    public static boolean checkSlowFalling(TrackedPlayer player) {
        return player.isCachedHasSlowFalling();
    }
    public static boolean checkLevitation(TrackedPlayer player) {
        return player.isCachedHasLevitation();
    }
    public static double getLevitationAmplifier(TrackedPlayer player) {
        return player.getCachedLevitationAmplifier();
    }
    public static boolean checkRiptiding(TrackedPlayer player) {
        return player.isCachedHasRiptide();
    }
    public static boolean checkFallFlying(TrackedPlayer player) {
        return player.isCachedIsFallFlying();
    }
}
