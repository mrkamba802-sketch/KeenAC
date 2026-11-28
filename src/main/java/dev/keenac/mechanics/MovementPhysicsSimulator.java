package dev.keenac.mechanics;

import java.util.List;

/**
 * Conservative 1.21.x player-motion integrator for ordinary walking surfaces.
 *
 * It models input acceleration, ground friction, basic air drag/gravity,
 * jumping, axis-clipped AABB movement and the vanilla-sized step-up attempt.
 * Special media (fluids, cobwebs, climbing, powder snow, etc.) must be routed
 * to dedicated models instead of being judged by this baseline integrator.
 */
public final class MovementPhysicsSimulator {
    private static final double PLAYER_HALF_WIDTH = 0.30;
    private static final double PLAYER_HEIGHT = 1.80;
    private static final double STEP_HEIGHT = 0.60;
    private static final double GRAVITY = 0.08;
    private static final double VERTICAL_DRAG = 0.98;
    private static final double AIR_DRAG = 0.91;
    private static final double EPSILON = 1.0E-7;
    private static final double BASE_TICKS_PER_SECOND = 20.0;

    private MovementPhysicsSimulator() { }

    public static Result simulate(
            MovementState initial,
            MovementInput input,
            MechanicsSnapshot mechanics,
            MovementPhysics.Profile profile,
            int ticks
    ) {
        if (initial == null || !initial.isFinite() || input == null
                || mechanics == null || profile == null) {
            throw new IllegalArgumentException("A finite state, input and mechanics profile are required.");
        }
        if (ticks < 1 || ticks > 5) {
            throw new IllegalArgumentException("Simulation is limited to 1-5 ticks per step.");
        }

        MovementState state = initial;
        List<CollisionBox> collisionBoxes = mechanics.world().collisionBoxes();
        boolean anyHorizontalCollision = false;
        boolean anyVerticalCollision = false;

        for (int tick = 0; tick < ticks; tick++) {
            double forward = input.forwardAxis();
            double strafe = input.strafeAxis();
            double inputLength = Math.sqrt(forward * forward + strafe * strafe);
            if (inputLength > 1.0) {
                forward /= inputLength;
                strafe /= inputLength;
            }

            double radians = Math.toRadians(state.yaw());
            double sin = Math.sin(radians);
            double cos = Math.cos(radians);
            double directionX = strafe * cos - forward * sin;
            double directionZ = forward * cos + strafe * sin;

            boolean grounded = state.onGround();
            double friction = grounded
                    ? clamp(mechanics.world().supportSlipperiness(), 0.35, 0.98) * AIR_DRAG
                    : AIR_DRAG;
            double targetSpeed = profile.normalHorizontalSpeedBps() / BASE_TICKS_PER_SECOND;
            if (mechanics.usingItem()) targetSpeed *= 0.20;

            double velocityX;
            double velocityZ;
            if (grounded) {
                // Express ground acceleration as a response toward the supported
                // vanilla speed. This keeps the equilibrium at the API-derived
                // movement attribute speed while preserving block slipperiness.
                velocityX = state.velocityX() * friction
                        + directionX * targetSpeed * (1.0 - friction);
                velocityZ = state.velocityZ() * friction
                        + directionZ * targetSpeed * (1.0 - friction);
            } else {
                velocityX = state.velocityX() * AIR_DRAG + directionX * targetSpeed * 0.02;
                velocityZ = state.velocityZ() * AIR_DRAG + directionZ * targetSpeed * 0.02;
            }

            double velocityY = state.velocityY();
            if (grounded && input.jump()) {
                velocityY = 0.42 + Math.min(4, mechanics.potionLevel("jump_boost")) * 0.10;
                grounded = false;
            }

            double moveY;
            if (grounded) {
                // A small downward probe keeps ground contact and avoids treating
                // a one-tick support edge as a free airborne state.
                moveY = -0.08;
            } else {
                velocityY = (velocityY - GRAVITY) * VERTICAL_DRAG;
                moveY = velocityY;
            }

            MoveResult moved = move(
                    state.x(), state.y(), state.z(),
                    velocityX, moveY, velocityZ,
                    collisionBoxes, grounded
            );
            anyHorizontalCollision |= moved.collidedX() || moved.collidedZ();
            anyVerticalCollision |= moved.collidedY();

            boolean nextGrounded = moved.onGround();
            if (moved.collidedY() && velocityY < 0.0) velocityY = 0.0;
            if (nextGrounded) velocityY = 0.0;
            if (moved.collidedX()) velocityX = 0.0;
            if (moved.collidedZ()) velocityZ = 0.0;

            state = new MovementState(
                    moved.x(), moved.y(), moved.z(),
                    velocityX, velocityY, velocityZ,
                    state.yaw(), nextGrounded
            );
        }

        return new Result(state, anyHorizontalCollision, anyVerticalCollision);
    }

    private static MoveResult move(
            double x, double y, double z,
            double dx, double dy, double dz,
            List<CollisionBox> colliders,
            boolean wasGrounded
    ) {
        MoveResult normal = moveAxes(x, y, z, dx, dy, dz, colliders);
        if (!wasGrounded || !(normal.collidedX() || normal.collidedZ())) {
            return normal;
        }

        MoveResult stepped = tryStep(x, y, z, dx, dz, colliders);
        if (stepped == null) return normal;

        double normalDistance = square(normal.x() - x) + square(normal.z() - z);
        double steppedDistance = square(stepped.x() - x) + square(stepped.z() - z);
        return steppedDistance > normalDistance + EPSILON ? stepped : normal;
    }

    private static MoveResult moveAxes(
            double x, double y, double z,
            double dx, double dy, double dz,
            List<CollisionBox> colliders
    ) {
        Box box = playerBox(x, y, z);
        double allowedY = clipY(box, dy, colliders);
        boolean collidedY = Math.abs(allowedY - dy) > EPSILON;
        box = box.shift(0.0, allowedY, 0.0);

        double allowedX = clipX(box, dx, colliders);
        boolean collidedX = Math.abs(allowedX - dx) > EPSILON;
        box = box.shift(allowedX, 0.0, 0.0);

        double allowedZ = clipZ(box, dz, colliders);
        boolean collidedZ = Math.abs(allowedZ - dz) > EPSILON;
        box = box.shift(0.0, 0.0, allowedZ);

        boolean onGround = dy < 0.0 && collidedY;
        return new MoveResult(
                x + allowedX, y + allowedY, z + allowedZ,
                collidedX, collidedY, collidedZ, onGround
        );
    }

    private static MoveResult tryStep(
            double x, double y, double z,
            double dx, double dz,
            List<CollisionBox> colliders
    ) {
        Box box = playerBox(x, y, z);
        double up = clipY(box, STEP_HEIGHT, colliders);
        if (up <= EPSILON) return null;
        box = box.shift(0.0, up, 0.0);

        double allowedX = clipX(box, dx, colliders);
        boolean collidedX = Math.abs(allowedX - dx) > EPSILON;
        box = box.shift(allowedX, 0.0, 0.0);

        double allowedZ = clipZ(box, dz, colliders);
        boolean collidedZ = Math.abs(allowedZ - dz) > EPSILON;
        box = box.shift(0.0, 0.0, allowedZ);

        double down = clipY(box, -up, colliders);
        boolean onGround = Math.abs(down + up) > EPSILON;
        box = box.shift(0.0, down, 0.0);
        if (!onGround) return null;

        return new MoveResult(
                box.centerX(), box.minY(), box.centerZ(),
                collidedX, Math.abs(down + up) > EPSILON, collidedZ, true
        );
    }

    private static double clipX(Box moving, double requested, List<CollisionBox> colliders) {
        double allowed = requested;
        for (CollisionBox block : colliders) {
            if (!overlap(moving.minY(), moving.maxY(), block.minY(), block.maxY())
                    || !overlap(moving.minZ(), moving.maxZ(), block.minZ(), block.maxZ())) continue;
            if (allowed > 0.0 && moving.maxX() <= block.minX() + EPSILON) {
                allowed = Math.min(allowed, block.minX() - moving.maxX());
            } else if (allowed < 0.0 && moving.minX() >= block.maxX() - EPSILON) {
                allowed = Math.max(allowed, block.maxX() - moving.minX());
            }
        }
        return allowed;
    }

    private static double clipY(Box moving, double requested, List<CollisionBox> colliders) {
        double allowed = requested;
        for (CollisionBox block : colliders) {
            if (!overlap(moving.minX(), moving.maxX(), block.minX(), block.maxX())
                    || !overlap(moving.minZ(), moving.maxZ(), block.minZ(), block.maxZ())) continue;
            if (allowed > 0.0 && moving.maxY() <= block.minY() + EPSILON) {
                allowed = Math.min(allowed, block.minY() - moving.maxY());
            } else if (allowed < 0.0 && moving.minY() >= block.maxY() - EPSILON) {
                allowed = Math.max(allowed, block.maxY() - moving.minY());
            }
        }
        return allowed;
    }

    private static double clipZ(Box moving, double requested, List<CollisionBox> colliders) {
        double allowed = requested;
        for (CollisionBox block : colliders) {
            if (!overlap(moving.minX(), moving.maxX(), block.minX(), block.maxX())
                    || !overlap(moving.minY(), moving.maxY(), block.minY(), block.maxY())) continue;
            if (allowed > 0.0 && moving.maxZ() <= block.minZ() + EPSILON) {
                allowed = Math.min(allowed, block.minZ() - moving.maxZ());
            } else if (allowed < 0.0 && moving.minZ() >= block.maxZ() - EPSILON) {
                allowed = Math.max(allowed, block.maxZ() - moving.minZ());
            }
        }
        return allowed;
    }

    private static Box playerBox(double x, double y, double z) {
        return new Box(x - PLAYER_HALF_WIDTH, y, z - PLAYER_HALF_WIDTH,
                x + PLAYER_HALF_WIDTH, y + PLAYER_HEIGHT, z + PLAYER_HALF_WIDTH);
    }

    private static boolean overlap(double firstMin, double firstMax, double secondMin, double secondMax) {
        return firstMax > secondMin + EPSILON && firstMin < secondMax - EPSILON;
    }

    private static double square(double value) {
        return value * value;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record Result(
            MovementState state,
            boolean collidedHorizontally,
            boolean collidedVertically
    ) { }

    private record MoveResult(
            double x, double y, double z,
            boolean collidedX,
            boolean collidedY,
            boolean collidedZ,
            boolean onGround
    ) { }

    private record Box(
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ
    ) {
        private Box shift(double dx, double dy, double dz) {
            return new Box(minX + dx, minY + dy, minZ + dz,
                    maxX + dx, maxY + dy, maxZ + dz);
        }

        private double centerX() { return (minX + maxX) * 0.5; }
        private double centerZ() { return (minZ + maxZ) * 0.5; }
    }
}
