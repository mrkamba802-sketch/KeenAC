package dev.keenac.engine.core.physics;
import dev.keenac.engine.core.player.TrackedPlayer;
public final class PredictionContext {
    public final double x, y, z;
    public final double lastX, lastY, lastZ;
    public final double lastLastX, lastLastY, lastLastZ;
    public final double deltaX, deltaY, deltaZ;
    public final boolean onGround, lastOnGround;
    public final boolean sprinting, sneaking, swimming, climbing;
    public final int protocolVersion;
    public final double horizontalSpeed;
    public final double lastHorizontalSpeed;
    public final double baseSpeed;
    public final double predictedMaxHorizontalSpeed;
    public final boolean inWater, inLava;
    public final boolean hasSlowFalling, hasLevitation;
    public PredictionContext(TrackedPlayer player) {
        this.x = player.getX();
        this.y = player.getY();
        this.z = player.getZ();
        this.lastX = player.getLastX();
        this.lastY = player.getLastY();
        this.lastZ = player.getLastZ();
        this.lastLastX = player.getLastLastX();
        this.lastLastY = player.getLastLastY();
        this.lastLastZ = player.getLastLastZ();
        this.deltaX = player.getDeltaX();
        this.deltaY = player.getDeltaY();
        this.deltaZ = player.getDeltaZ();
        this.onGround = player.isOnGround();
        this.lastOnGround = player.isLastOnGround();
        this.sprinting = player.isSprinting();
        this.sneaking = player.isSneaking();
        this.swimming = player.isSwimming();
        this.climbing = player.isClimbing();
        this.protocolVersion = player.getProtocolVersion();
        this.horizontalSpeed = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        double ldx = lastX - lastLastX;
        double ldz = lastZ - lastLastZ;
        this.lastHorizontalSpeed = Math.sqrt(ldx * ldx + ldz * ldz);
        // AttributeSnapshot reads the effective MOVEMENT_SPEED attribute, including
        // active attribute modifiers. Applying potion multipliers again double-counts
        // Speed/Slowness and can make legal movement exceed the prediction.
        this.baseSpeed = PredictionEngine.calculateBaseSpeed(
                sprinting, sneaking, 1.0, 1.0, player.getAttributeSnapshot());
        this.predictedMaxHorizontalSpeed = PredictionEngine.calculateMaxHorizontalSpeed(
                baseSpeed, lastHorizontalSpeed, onGround, climbing, swimming, protocolVersion,
                player.getAttributeSnapshot());
        this.inWater = PredictionEngine.checkInWater(player);
        this.inLava = PredictionEngine.checkInLava(player);
        this.hasSlowFalling = PredictionEngine.checkSlowFalling(player);
        this.hasLevitation = PredictionEngine.checkLevitation(player);
    }
}
