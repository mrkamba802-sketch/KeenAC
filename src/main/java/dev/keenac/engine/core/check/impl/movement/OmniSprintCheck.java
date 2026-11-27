package dev.keenac.engine.core.check.impl.movement;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import dev.keenac.engine.compat.sprint.DirectionalMovement;
import dev.keenac.engine.compat.sprint.OmniSprintModel;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.CompatFlag;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.physics.PredictionContext;
import dev.keenac.engine.core.physics.PredictionEngine;
import dev.keenac.engine.core.player.TrackedPlayer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@CheckData(name = "Sprint B", stableKey = "keenac.movement.sprint", decay = 0.01, setbackVl = 20,
    compat = {CompatFlag.RELAX_ON_MISMATCH},
    relaxMultiplier = 1.3)
public class OmniSprintCheck extends Check implements PacketCheck {
    private static final double FLAGS_REQUIRED = 2.0D;
    private static final class PlayerState {
        final OmniSprintModel model = new OmniSprintModel();
        boolean lastSprinting;
        double lastDeltaYaw;
        int clientGroundTicks;
        int serverGroundTicks;
        int clientAirTicks;
        int serverAirTicks;
        int consecutiveFlags;
    }
    private final ConcurrentHashMap<UUID, PlayerState> stateMap = new ConcurrentHashMap<>();
    private PlayerState getState(TrackedPlayer player) {
        return stateMap.computeIfAbsent(player.getUuid(), uuid -> new PlayerState());
    }
    @Override
    public void removePlayer(UUID uuid) {
        stateMap.remove(uuid);
    }
    @Override
    public void onPacketReceive(TrackedPlayer player, PacketReceiveEvent event) {
        if (!PredictionEngine.isMovementPacket(event)) return;
        PlayerState state = getState(player);
        PredictionContext ctx = new PredictionContext(player);
        float yaw = player.getYaw();
        float lastYaw = player.getLastYaw();
        double deltaYaw = DirectionalMovement.yawDifference(yaw, lastYaw);
        OmniSprintModel.Context c = new OmniSprintModel.Context();
        c.deltaX = ctx.deltaX;
        c.deltaZ = ctx.deltaZ;
        c.yaw = yaw;
        c.lastYaw = lastYaw;
        c.deltaYaw = deltaYaw;
        c.yawAccel = Math.abs(deltaYaw - state.lastDeltaYaw);
        c.baseSpeed = ctx.baseSpeed;
        c.sprinting = ctx.sprinting;
        c.lastSprinting = state.lastSprinting;
        c.onGround = ctx.onGround;
        c.serverGround = player.isServerOnGround();
        c.clientGroundTicks = state.clientGroundTicks;
        c.serverGroundTicksPlus = state.serverGroundTicks;
        c.clientAirTicks = state.clientAirTicks;
        c.serverAirTicks = state.serverAirTicks;
        c.packetMoving = player.isMovedSinceTick();
        c.insideWater = ctx.inWater;
        c.nearWater = ctx.inWater;
        c.nearWebs = false;
        c.nearClimbable = ctx.climbing;
        c.onIce = false;
        c.onSlime = false;
        c.onSoulSand = false;
        c.onHoney = player.isCachedOnHoney();
        c.nearBoat = false;
        c.onBoat = false;
        c.nearWall = false;
        c.colliding = false;
        c.underBlock = false;
        c.predictDownwards = false;
        c.predictUpwards = false;
        c.recentCollision = false;
        c.recentGhostBlock = false;
        c.teleports = false;
        c.riptiding = player.isCachedHasRiptide();
        c.recentRiptiding = false;
        c.gliding = player.isGliding();
        c.takingVelocity = player.isVelocityReceived();
        c.inVehicle = false;
        c.recentVehicle = false;
        c.slimeBounce = false;
        c.cancelPending = false;
        OmniSprintModel.Result result = state.model.handle(c);
        if (result.groundFlag || result.airFlag) {
            state.consecutiveFlags++;
            double required = trustAdjustedThreshold(player, FLAGS_REQUIRED);
            if (state.consecutiveFlags >= required) {
                flag(player);
                resetBuffer(player);
                state.consecutiveFlags = 0;
            }
        } else {
            state.consecutiveFlags = Math.max(0, state.consecutiveFlags - 1);
        }
        state.lastSprinting = ctx.sprinting;
        state.lastDeltaYaw = deltaYaw;
        state.clientGroundTicks = ctx.onGround ? state.clientGroundTicks + 1 : 0;
        state.serverGroundTicks = player.isServerOnGround() ? state.serverGroundTicks + 1 : 0;
        state.clientAirTicks = !ctx.onGround ? state.clientAirTicks + 1 : 0;
        state.serverAirTicks = !player.isServerOnGround() ? state.serverAirTicks + 1 : 0;
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
}
