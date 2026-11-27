package dev.keenac.engine.core.check.impl.movement;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import dev.keenac.KeenAC;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.CompatFlag;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.compensation.SimulationEngine;
import dev.keenac.engine.core.player.data.ActionData;
import dev.keenac.engine.core.physics.PredictionContext;
import dev.keenac.engine.core.physics.PredictionEngine;
import dev.keenac.engine.core.player.TrackedPlayer;
import dev.keenac.engine.core.util.MaterialUtils;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@CheckData(name = "Fly A", stableKey = "keenac.movement.fly", decay = 0.01, setbackVl = 15, compat = {CompatFlag.RELAX_ON_MISMATCH}, relaxMultiplier = 1.3)
public class FlightCheck extends Check implements PacketCheck {
    private static final double JUMP_MOMENTUM = 0.42;
    private static final double VERTICAL_TOLERANCE = 0.05;
    private static final double VERTICAL_TOLERANCE_UNCONFIRMED = 0.15;
    private static final int HOVER_TICK_THRESHOLD = 20;
    private static final double HOVER_DELTA_THRESHOLD = 0.005;
    private static final double NO_FALL_VELOCITY_THRESHOLD = 0.5;
    private static final double NO_FALL_DISTANCE = 3.0;
    private static final int NO_FALL_STRIKES = 3;
    private static final class PlayerState {
        double expectedDeltaY;
        int hoverTicks;
        double fallStartY;
        boolean falling;
        int noFallStrikes;
    }
    private final ConcurrentHashMap<UUID, PlayerState> stateMap = new ConcurrentHashMap<>();
    private PlayerState getState(TrackedPlayer player) {
        return stateMap.computeIfAbsent(player.getUuid(), k -> new PlayerState());
    }
    @Override
    public void removePlayer(java.util.UUID uuid) {
        stateMap.remove(uuid);
    }
    @Override
    public void onPacketReceive(TrackedPlayer player, PacketReceiveEvent event) {
        if (!PredictionEngine.isMovementPacket(event)) return;
        ActionData actionData = player.getActionData();
        if (actionData.hasRecentPistonUpdate(5)) {
            decreaseBuffer(player, 0.5);
            return;
        }
        if (actionData.hasRecentBlockUpdateUnder(5)) {
            decreaseBuffer(player, 0.3);
            return;
        }
        PlayerState state = getState(player);
        PredictionContext ctx = new PredictionContext(player);
        boolean currentOnGround = ctx.onGround;
        double deltaY = ctx.deltaY;
        handleNoFall(player, state, currentOnGround, deltaY, ctx.lastY, ctx.y);
        if (currentOnGround) {
            state.expectedDeltaY = 0;
            state.hoverTicks = 0;
            state.falling = false;
            return;
        }
        seedExpectedVelocityOnTakeoff(state, ctx, deltaY);
        boolean hasRiptide = PredictionEngine.checkRiptiding(player);
        boolean isFallFlying = PredictionEngine.checkFallFlying(player);
        double predictedDeltaY = predictDeltaY(player, state, ctx, hasRiptide, isFallFlying);
        double verticalDelta = deltaY - predictedDeltaY;
        SimulationEngine simEngine = KeenAC.getInstance().getSimulationEngine();
        boolean unconfirmedChanges = simEngine != null && simEngine.needsSimulation(player);
        double tolerance = unconfirmedChanges ? VERTICAL_TOLERANCE_UNCONFIRMED : VERTICAL_TOLERANCE;
        boolean verticalDeviation = Math.abs(verticalDelta) > tolerance
                && Math.abs(deltaY) > 0.01;
        if (verticalDeviation && !isFallFlying && !hasRiptide && !ctx.hasLevitation) {
            evaluateVerticalDeviation(player, state, ctx, verticalDelta, predictedDeltaY, deltaY);
        } else {
            decreaseBuffer(player, 0.1);
            state.hoverTicks = Math.max(0, state.hoverTicks - 1);
        }
        state.expectedDeltaY = deltaY;
    }
    private void seedExpectedVelocityOnTakeoff(PlayerState state, PredictionContext ctx, double deltaY) {
        if (ctx.lastOnGround && !ctx.onGround) {
            if (deltaY >= JUMP_MOMENTUM - 0.01 && deltaY <= JUMP_MOMENTUM + 0.15) {
                state.expectedDeltaY = JUMP_MOMENTUM;
            } else if (Math.abs(deltaY) < 0.01) {
                state.expectedDeltaY = 0;
            }
        }
    }
    private double predictDeltaY(TrackedPlayer player, PlayerState state, PredictionContext ctx,
                                 boolean hasRiptide, boolean isFallFlying) {
        return PredictionEngine.predictDeltaY(
                state.expectedDeltaY,
                ctx.inWater,
                ctx.inLava,
                ctx.climbing,
                PredictionEngine.checkOnHoney(player),
                ctx.hasSlowFalling,
                ctx.hasLevitation,
                ctx.hasLevitation ? PredictionEngine.getLevitationAmplifier(player) : 1.0,
                isFallFlying,
                hasRiptide,
                player.getAttributeSnapshot().gravity()
        );
    }
    private void evaluateVerticalDeviation(TrackedPlayer player, PlayerState state, PredictionContext ctx,
                                           double verticalDelta, double predictedDeltaY, double deltaY) {
        handleHoverDetection(player, state, ctx);
        if (deltaY > 0 && state.expectedDeltaY <= 0) {
            increaseBuffer(player, 1.5);
            if (getBuffer(player) > 3.0) {
                flag(player);
                resetBuffer(player);
            }
            return;
        }
        double deviationRatio = Math.abs(verticalDelta) / Math.max(Math.abs(predictedDeltaY), 0.001);
        if (deviationRatio > 2.0) {
            flag(player);
            resetBuffer(player);
            return;
        }
        increaseBuffer(player, 0.3 * Math.min(deviationRatio, 2.0));
        if (getBuffer(player) > 5.0) {
            flag(player);
            resetBuffer(player);
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
    private void handleHoverDetection(TrackedPlayer player, PlayerState state, PredictionContext ctx) {
        double yMoved = Math.abs(ctx.deltaY);
        if (yMoved < HOVER_DELTA_THRESHOLD && !ctx.inWater && !ctx.inLava && !ctx.climbing) {
            state.hoverTicks++;
            if (state.hoverTicks > HOVER_TICK_THRESHOLD) {
                increaseBuffer(player, 1.0);
                if (getBuffer(player) > 5.0) {
                    flag(player);
                    resetBuffer(player);
                    state.hoverTicks = 0;
                }
            }
        } else {
            state.hoverTicks = Math.max(0, state.hoverTicks - 1);
        }
    }
    private void handleNoFall(TrackedPlayer player, PlayerState state, boolean currentOnGround,
                              double deltaY, double lastY, double currentY) {
        if (deltaY < -NO_FALL_VELOCITY_THRESHOLD && !state.falling) {
            state.falling = true;
            state.fallStartY = lastY;
        }
        if (!state.falling) {
            if (currentOnGround) state.falling = false;
            return;
        }
        if (!currentOnGround) {
            state.noFallStrikes = 0;
            return;
        }
        double fallDistance = state.fallStartY - currentY;
        boolean impossibleClaim = fallDistance > NO_FALL_DISTANCE
                && deltaY < -NO_FALL_VELOCITY_THRESHOLD
                && !hasSolidBlockUnder(player);
        if (impossibleClaim) {
            state.noFallStrikes++;
            if (state.noFallStrikes >= NO_FALL_STRIKES) {
                state.noFallStrikes = 0;
                state.falling = false;
                flagWithSetback(player);
            }
        } else {
            state.noFallStrikes = 0;
            state.falling = false;
        }
    }
    private boolean hasSolidBlockUnder(TrackedPlayer player) {
        try {
            org.bukkit.entity.Player bukkit = player.getPlayer();
            return bukkit != null && bukkit.isOnline()
                    && !bukkit.isInsideVehicle()
                    && !PredictionEngine.checkInWater(player)
                    && !bukkit.isFlying()
                    && blockUnderFeetHasSupport(bukkit.getWorld(),
                            (int) Math.floor(player.getX()),
                            (int) Math.floor(player.getY()),
                            (int) Math.floor(player.getZ()));
        } catch (Exception e) {
            return true;
        }
    }
    private boolean blockUnderFeetHasSupport(org.bukkit.World world, int bx, int by, int bz) {
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) return true;
        return !MaterialUtils.isAirLike(world.getBlockAt(bx, by, bz).getType())
            || !MaterialUtils.isAirLike(world.getBlockAt(bx, by - 1, bz).getType());
    }
}
