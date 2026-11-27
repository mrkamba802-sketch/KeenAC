package dev.keenac.engine.core.player;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import dev.keenac.engine.compat.trust.TrustFactorModel;
import dev.keenac.engine.core.bedrock.BedrockInfo;
import dev.keenac.engine.core.player.data.ActionData;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
public class TrackedPlayer {
    private volatile AttributeSnapshot attributeSnapshot = AttributeSnapshot.defaults();
    public enum Pose {
        STANDING,
        FALL_FLYING,
        SWIMMING,
        SLEEPING,
        SPIN_ATTACK,
        SNEAKING,
        DYING,
        LONG_JUMPING
    }
    static final class PositionState {
        final double x, y, z;
        final double lastX, lastY, lastZ;
        final double lastLastX, lastLastY, lastLastZ;
        final double deltaX, deltaY, deltaZ;
        final int tickCount;
        PositionState(double x, double y, double z,
                      double lastX, double lastY, double lastZ,
                      double lastLastX, double lastLastY, double lastLastZ,
                      double deltaX, double deltaY, double deltaZ,
                      int tickCount) {
            this.x = x; this.y = y; this.z = z;
            this.lastX = lastX; this.lastY = lastY; this.lastZ = lastZ;
            this.lastLastX = lastLastX; this.lastLastY = lastLastY; this.lastLastZ = lastLastZ;
            this.deltaX = deltaX; this.deltaY = deltaY; this.deltaZ = deltaZ;
            this.tickCount = tickCount;
        }
    }
    static final class GroundState {
        final boolean onGround;
        final boolean lastOnGround;
        final double groundX, groundY, groundZ;
        GroundState(boolean onGround, boolean lastOnGround,
                    double groundX, double groundY, double groundZ) {
            this.onGround = onGround;
            this.lastOnGround = lastOnGround;
            this.groundX = groundX;
            this.groundY = groundY;
            this.groundZ = groundZ;
        }
    }
    static final class RotationState {
        final float yaw, pitch;
        final float lastYaw, lastPitch;
        RotationState(float yaw, float pitch, float lastYaw, float lastPitch) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.lastYaw = lastYaw;
            this.lastPitch = lastPitch;
        }
    }
    private final UUID uuid;
    private final String name;
    private final Player player;
    private final User user;
    private volatile ClientVersion clientVersion;
    private static final int UNKNOWN_PROTOCOL_VERSION = 4;
    private volatile int protocolVersion;
    private volatile PositionState pos = new PositionState(0,0,0, 0,0,0, 0,0,0, 0,0,0, 0);
    private volatile GroundState ground = new GroundState(false, false, 0,0,0);
    private volatile RotationState rotation = new RotationState(0, 0, 0, 0);
    private volatile double width = 0.6;
    private volatile double height = 1.8;
    private volatile boolean serverOnGround;
    private volatile boolean sprinting;
    private volatile boolean sneaking;
    private volatile boolean flying;
    private volatile boolean swimming;
    private volatile boolean climbing;
    private volatile boolean gliding;
    private volatile double elytraMomentum;
    private volatile int glideStartTick;
    private volatile Pose pose = Pose.STANDING;
    private volatile int transactionPing;
    private volatile int transactionId;
    private volatile double velocityX, velocityY, velocityZ;
    private volatile double serverVelocityX, serverVelocityY, serverVelocityZ;
    private volatile boolean velocityReceived;
    private volatile boolean allowFlight;
    private volatile double teleportX, teleportY, teleportZ;
    private final ConcurrentHashMap<String, Integer> violationLevels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> buffers = new ConcurrentHashMap<>();
    private volatile int attackCooldown;
    private volatile long lastAttackTime;
    private volatile long joinTime;
    private volatile boolean movedSinceTick;
    private volatile boolean valid = true;
    private volatile BedrockInfo bedrockInfo;
    private volatile boolean alertsEnabled = true;
    private final ActionData actionData = new ActionData(this);
    private final TrustFactorModel trustFactor = new TrustFactorModel();
    private volatile dev.keenac.engine.core.compensation.CompensatedWorld compensatedWorld;
    private volatile boolean respawned;
    private volatile boolean cachedInWater;
    private volatile boolean cachedInLava;
    private volatile boolean cachedOnHoney;
    private volatile double cachedSpeedMultiplier = 1.0;
    private volatile double cachedSlownessMultiplier = 1.0;
    private volatile boolean cachedHasSlowFalling;
    private volatile boolean cachedHasLevitation;
    private volatile double cachedLevitationAmplifier = 1.0;
    private volatile boolean cachedIsFallFlying;
    private volatile boolean cachedHasRiptide;
    public TrackedPlayer(Player player, User user) {
        this.uuid = player.getUniqueId();
        this.name = player.getName();
        this.player = player;
        this.user = user;
        ClientVersion version = user != null ? user.getClientVersion() : null;
        this.clientVersion = version;
        this.protocolVersion = version != null ? version.getProtocolVersion() : UNKNOWN_PROTOCOL_VERSION;
        this.joinTime = dev.keenac.engine.core.util.PacketClock.currentTimeMillis();
    }
    public double getHeight() {
        switch (pose) {
            case FALL_FLYING: return 0.6;
            case SWIMMING: return 0.6;
            case SPIN_ATTACK: return 0.6;
            case SLEEPING: return 0.2;
            case DYING: return 0.0;
            case SNEAKING: return protocolVersion >= 477 ? 1.5 : 1.62;
            case LONG_JUMPING: return protocolVersion >= 477 ? 1.5 : 1.8;
            case STANDING:
            default: return 1.8;
        }
    }
    public double getEyeHeight() {
        switch (pose) {
            case FALL_FLYING: return 0.4;
            case SWIMMING: return 0.4;
            case SPIN_ATTACK: return 0.4;
            case SLEEPING: return 0.2;
            case DYING: return 0.0;
            case SNEAKING: return protocolVersion >= 477 ? 1.27 : 1.54;
            case LONG_JUMPING: return protocolVersion >= 477 ? 1.27 : 1.62;
            case STANDING:
            default: return 1.62;
        }
    }
    public double getDeltaX() { return pos.deltaX; }
    public double getDeltaZ() { return pos.deltaZ; }
    public double getHorizontalSpeed() {
        return Math.sqrt(pos.deltaX * pos.deltaX + pos.deltaZ * pos.deltaZ);
    }
    public double getVerticalSpeed() {
        return Math.abs(pos.deltaY);
    }
    public double getDistanceSq(double x, double y, double z) {
        double dx = this.pos.x - x;
        double dy = this.pos.y - y;
        double dz = this.pos.z - z;
        return dx * dx + dy * dy + dz * dz;
    }
    public void setPosition(double x, double y, double z) {
        PositionState old = this.pos;
        this.pos = new PositionState(
            x, y, z,
            old.x, old.y, old.z,
            old.lastX, old.lastY, old.lastZ,
            x - old.x, y - old.y, z - old.z,
            old.tickCount + 1
        );
    }
    public void resetTickState() {
        this.movedSinceTick = false;
        GroundState g = this.ground;
        this.ground = new GroundState(g.onGround, g.onGround, g.groundX, g.groundY, g.groundZ);
        RotationState r = this.rotation;
        this.rotation = new RotationState(r.yaw, r.pitch, r.yaw, r.pitch);
    }
    public void setOnGround(boolean onGround) {
        GroundState old = this.ground;
        double gx = onGround ? pos.x : old.groundX;
        double gy = onGround ? pos.y : old.groundY;
        double gz = onGround ? pos.z : old.groundZ;
        this.ground = new GroundState(onGround, old.onGround, gx, gy, gz);
    }
    public UUID getUuid() { return uuid; }
    public String getName() { return name; }
    public Player getPlayer() { return player; }
    public User getUser() { return user; }
    public ClientVersion getClientVersion() { return clientVersion; }
    public void setClientVersion(ClientVersion clientVersion) {
        this.clientVersion = clientVersion;
        this.protocolVersion = clientVersion != null
            ? clientVersion.getProtocolVersion()
            : UNKNOWN_PROTOCOL_VERSION;
    }
    public int getProtocolVersion() { return protocolVersion; }
    public double getX() { return pos.x; }
    public double getY() { return pos.y; }
    public double getZ() { return pos.z; }
    public double getLastX() { return pos.lastX; }
    public double getLastY() { return pos.lastY; }
    public double getLastZ() { return pos.lastZ; }
    public double getLastLastX() { return pos.lastLastX; }
    public double getLastLastY() { return pos.lastLastY; }
    public double getLastLastZ() { return pos.lastLastZ; }
    public double getDeltaY() { return pos.deltaY; }
    public int getTickCount() { return pos.tickCount; }
    public double getWidth() { return width; }
    public void setHeight(double height) { this.height = height; }
    public boolean isOnGround() { return ground.onGround; }
    public boolean isLastOnGround() { return ground.lastOnGround; }
    public boolean isServerOnGround() { return serverOnGround; }
    public void setServerOnGround(boolean serverOnGround) { this.serverOnGround = serverOnGround; }
    public double getGroundX() { return ground.groundX; }
    public double getGroundY() { return ground.groundY; }
    public double getGroundZ() { return ground.groundZ; }
    public boolean isSprinting() { return sprinting; }
    public void setSprinting(boolean sprinting) { this.sprinting = sprinting; }
    public boolean isSneaking() { return sneaking; }
    public synchronized void setSneaking(boolean sneaking) {
        this.sneaking = sneaking;
        if (sneaking && pose == Pose.STANDING) pose = Pose.SNEAKING;
        else if (!sneaking && pose == Pose.SNEAKING) pose = Pose.STANDING;
    }
    public boolean isFlying() { return flying; }
    public void setFlying(boolean flying) { this.flying = flying; }
    public boolean isSwimming() { return swimming; }
    public synchronized void setSwimming(boolean swimming) {
        this.swimming = swimming;
        if (swimming) pose = Pose.SWIMMING;
        else if (pose == Pose.SWIMMING) pose = Pose.STANDING;
    }
    public boolean isClimbing() { return climbing; }
    public void setClimbing(boolean climbing) { this.climbing = climbing; }
    public boolean isGliding() { return gliding; }
    public synchronized void setGliding(boolean gliding) {
        this.gliding = gliding;
        if (gliding) pose = Pose.FALL_FLYING;
        else if (pose == Pose.FALL_FLYING) pose = Pose.STANDING;
    }
    public double getElytraMomentum() { return elytraMomentum; }
    public void setElytraMomentum(double elytraMomentum) { this.elytraMomentum = elytraMomentum; }
    public int getGlideStartTick() { return glideStartTick; }
    public void setGlideStartTick(int glideStartTick) { this.glideStartTick = glideStartTick; }
    public Pose getPose() { return pose; }
    public void setPose(Pose pose) {
        this.pose = pose;
        this.sneaking = (pose == Pose.SNEAKING);
        this.swimming = (pose == Pose.SWIMMING);
        this.gliding = (pose == Pose.FALL_FLYING);
    }
    public boolean isLongJumping() { return pose == Pose.LONG_JUMPING; }
    public void setLongJumping(boolean longJumping) {
        if (longJumping) pose = Pose.LONG_JUMPING;
        else if (pose == Pose.LONG_JUMPING) pose = Pose.STANDING;
    }
    public boolean isDying() { return pose == Pose.DYING; }
    public int getTransactionPing() { return transactionPing; }
    public void setTransactionPing(int transactionPing) { this.transactionPing = transactionPing; }
    public int getTransactionId() { return transactionId; }
    public void setTransactionId(int transactionId) { this.transactionId = transactionId; }
    public double getVelocityX() { return velocityX; }
    public void setVelocityX(double velocityX) { this.velocityX = velocityX; }
    public double getVelocityY() { return velocityY; }
    public void setVelocityY(double velocityY) { this.velocityY = velocityY; }
    public double getVelocityZ() { return velocityZ; }
    public void setVelocityZ(double velocityZ) { this.velocityZ = velocityZ; }
    public void setVelocity(double x, double y, double z) { this.velocityX = x; this.velocityY = y; this.velocityZ = z; }
    public double getServerVelocityX() { return serverVelocityX; }
    public void setServerVelocityX(double v) { this.serverVelocityX = v; }
    public double getServerVelocityY() { return serverVelocityY; }
    public void setServerVelocityY(double v) { this.serverVelocityY = v; }
    public double getServerVelocityZ() { return serverVelocityZ; }
    public void setServerVelocityZ(double v) { this.serverVelocityZ = v; }
    public boolean isVelocityReceived() { return velocityReceived; }
    public void setVelocityReceived(boolean v) { this.velocityReceived = v; }
    public boolean isAllowFlight() { return allowFlight; }
    public void setAllowFlight(boolean allowFlight) { this.allowFlight = allowFlight; }
    public void setProtocolVersion(int protocolVersion) { this.protocolVersion = protocolVersion; }
    public double getTeleportX() { return teleportX; }
    public double getTeleportY() { return teleportY; }
    public double getTeleportZ() { return teleportZ; }
    public void setTeleportPosition(double x, double y, double z) { this.teleportX = x; this.teleportY = y; this.teleportZ = z; }
    public ConcurrentHashMap<String, Integer> getViolationLevels() { return violationLevels; }
    public ConcurrentHashMap<String, Double> getBuffers() { return buffers; }
    public int getAttackCooldown() { return attackCooldown; }
    public void setAttackCooldown(int attackCooldown) { this.attackCooldown = attackCooldown; }
    public long getLastAttackTime() { return lastAttackTime; }
    public void setLastAttackTime(long lastAttackTime) { this.lastAttackTime = lastAttackTime; }
    public long getJoinTime() { return joinTime; }
    public float getYaw() { return rotation.yaw; }
    public void setYaw(float yaw) {
        RotationState old = this.rotation;
        this.rotation = new RotationState(yaw, old.pitch, old.yaw, old.lastPitch);
    }
    public float getPitch() { return rotation.pitch; }
    public void setPitch(float pitch) {
        RotationState old = this.rotation;
        this.rotation = new RotationState(old.yaw, pitch, old.lastYaw, old.lastPitch);
    }
    public float getLastYaw() { return rotation.lastYaw; }
    public float getLastPitch() { return rotation.lastPitch; }
    public boolean isMovedSinceTick() { return movedSinceTick; }
    public void setMovedSinceTick(boolean movedSinceTick) { this.movedSinceTick = movedSinceTick; }
    public boolean isValid() { return valid; }
    public void setValid(boolean valid) { this.valid = valid; }
    public BedrockInfo getBedrockInfo() { return bedrockInfo; }
    public void setBedrockInfo(BedrockInfo bedrockInfo) { this.bedrockInfo = bedrockInfo; }
    public boolean isBedrock() { return bedrockInfo != null; }
    public boolean isAlertsEnabled() { return alertsEnabled; }
    public void setAlertsEnabled(boolean alertsEnabled) { this.alertsEnabled = alertsEnabled; }
    public TrustFactorModel getTrustFactor() { return trustFactor; }
    public ActionData getActionData() { return actionData; }
    public dev.keenac.engine.core.compensation.CompensatedWorld getCompensatedWorld() { return compensatedWorld; }
    public void setCompensatedWorld(dev.keenac.engine.core.compensation.CompensatedWorld world) { this.compensatedWorld = world; }
    public boolean isRespawned() { return respawned; }
    public void setRespawned(boolean respawned) { this.respawned = respawned; }
    public int getTotalViolationLevel() {
        int total = 0;
        for (int v : violationLevels.values()) {
            total += v;
        }
        return total;
    }
    public void updateCachedState() {
        try {
            this.attributeSnapshot = AttributeSnapshot.capture(player);
        } catch (Exception ignored) {
            this.attributeSnapshot = AttributeSnapshot.defaults();
        }
        try {
            org.bukkit.Location loc = player.getLocation();
            org.bukkit.block.Block block = loc.getBlock();
            this.cachedInWater = dev.keenac.engine.core.util.MaterialUtils.isWater(block.getType());
            this.cachedInLava = dev.keenac.engine.core.util.MaterialUtils.isLava(block.getType());
            this.cachedOnHoney = dev.keenac.engine.core.util.MaterialUtils.isHoney(
                loc.clone().subtract(0, 0.1, 0).getBlock().getType());
        } catch (Exception e) {
            this.cachedInWater = false;
            this.cachedInLava = false;
            this.cachedOnHoney = false;
        }
        try {
            this.cachedSpeedMultiplier = computePotionMultiplier("SPEED", 0.20, 5);
            this.cachedSlownessMultiplier = computePotionMultiplier("SLOW", -0.15, 4);
            this.cachedHasSlowFalling = hasPotionEffect("SLOW_FALLING");
            this.cachedHasLevitation = hasPotionEffect("LEVITATION");
            this.cachedLevitationAmplifier = getPotionAmplifier("LEVITATION");
        } catch (Exception e) {
            this.cachedSpeedMultiplier = 1.0;
            this.cachedSlownessMultiplier = 1.0;
            this.cachedHasSlowFalling = false;
            this.cachedHasLevitation = false;
            this.cachedLevitationAmplifier = 1.0;
        }
        try {
            java.lang.reflect.Method riptideMethod = player.getClass().getMethod("isRiptiding");
            this.cachedHasRiptide = (Boolean) riptideMethod.invoke(player);
            java.lang.reflect.Method glideMethod = player.getClass().getMethod("isGliding");
            this.cachedIsFallFlying = (Boolean) glideMethod.invoke(player);
        } catch (Exception e) {
            this.cachedHasRiptide = false;
            this.cachedIsFallFlying = false;
        }
    }
    private double computePotionMultiplier(String nameContains, double perLevel, int maxLevel) {
        for (org.bukkit.potion.PotionEffect effect : player.getActivePotionEffects()) {
            String effectName = effect.getType().getName().toUpperCase();
            if (effectName.contains(nameContains)) {
                int level = Math.min(effect.getAmplifier() + 1, maxLevel);
                return 1.0 + (perLevel * level);
            }
        }
        return 1.0;
    }
    private boolean hasPotionEffect(String nameContains) {
        for (org.bukkit.potion.PotionEffect effect : player.getActivePotionEffects()) {
            if (effect.getType().getName().toUpperCase().contains(nameContains)) return true;
        }
        return false;
    }
    private double getPotionAmplifier(String nameContains) {
        for (org.bukkit.potion.PotionEffect effect : player.getActivePotionEffects()) {
            if (effect.getType().getName().toUpperCase().contains(nameContains)) {
                return effect.getAmplifier() + 1;
            }
        }
        return 1.0;
    }
    public AttributeSnapshot getAttributeSnapshot() { return attributeSnapshot; }
    public boolean isCachedInWater() { return cachedInWater; }
    public boolean isCachedInLava() { return cachedInLava; }
    public boolean isCachedOnHoney() { return cachedOnHoney; }
    public double getCachedSpeedMultiplier() { return cachedSpeedMultiplier; }
    public double getCachedSlownessMultiplier() { return cachedSlownessMultiplier; }
    public boolean isCachedHasSlowFalling() { return cachedHasSlowFalling; }
    public boolean isCachedHasLevitation() { return cachedHasLevitation; }
    public double getCachedLevitationAmplifier() { return cachedLevitationAmplifier; }
    public boolean isCachedIsFallFlying() { return cachedIsFallFlying; }
    public boolean isCachedHasRiptide() { return cachedHasRiptide; }
}
