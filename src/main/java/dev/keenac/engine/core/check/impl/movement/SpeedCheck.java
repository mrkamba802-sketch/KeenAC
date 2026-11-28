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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@CheckData(name = "Speed A", stableKey = "keenac.movement.speed", decay = 0.01, setbackVl = 20, compat = {CompatFlag.RELAX_ON_MISMATCH}, relaxMultiplier = 1.5)
public class SpeedCheck extends Check implements PacketCheck {
    private static final double SPEED_TOLERANCE = 1.15;
    private static final double SPEED_TOLERANCE_UNCONFIRMED = 1.20;
    private static final double PRE_1_18_2_THRESHOLD = 0.03;
    private static final double MIN_SPEED_FLAG_BUFFER = 3.0;
    private static final class PlayerState {
        double maxObservedSpeed;
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

        var bukkitPlayer = player.getPlayer();
        if (bukkitPlayer == null || !bukkitPlayer.isOnline()
                || bukkitPlayer.isInsideVehicle()
                || player.isFlying()
                || player.isCachedIsFallFlying()
                || player.isCachedHasRiptide()) {
            decreaseBuffer(player, 0.25);
            return;
        }

        var contextManager = KeenAC.getInstance().getPlayerContextManager();
        if (contextManager != null) {
            long teleportAge = contextManager.teleportAgeMillis(player.getUuid());
            if ((teleportAge >= 0L && teleportAge < 1_000L)
                    || contextManager.isWithinVelocityGrace(
                            player.getUuid(), System.nanoTime(), 750L)) {
                decreaseBuffer(player, 0.25);
                return;
            }
        }

        ActionData actionData = player.getActionData();
        if (actionData.hasRecentPistonUpdate(5)) {
            decreaseBuffer(player, 0.5);
            return;
        }
        if (actionData.hasRecentBlockUpdateUnder(5)) {
            decreaseBuffer(player, 0.3);
            return;
        }

        PredictionContext ctx = new PredictionContext(player);
        double actualSpeed = ctx.horizontalSpeed;
        PlayerState state = getState(player);
        if (actualSpeed > state.maxObservedSpeed) {
            state.maxObservedSpeed = actualSpeed;
        }
        if (actualSpeed < PRE_1_18_2_THRESHOLD && ctx.protocolVersion < 757) {
            decreaseBuffer(player, 0.1);
            return;
        }
        if (actualSpeed < 0.005) {
            decreaseBuffer(player, 0.05);
            return;
        }

        double maxSpeed = ctx.predictedMaxHorizontalSpeed;
        if (!Double.isFinite(maxSpeed) || maxSpeed < 0.02) {
            decreaseBuffer(player, 0.1);
            return;
        }

        SimulationEngine simEngine = KeenAC.getInstance().getSimulationEngine();
        boolean unconfirmedChanges = simEngine != null && simEngine.needsSimulation(player);
        double tolerance = unconfirmedChanges ? SPEED_TOLERANCE_UNCONFIRMED : SPEED_TOLERANCE;
        double exceedRatio = actualSpeed / maxSpeed;
        if (exceedRatio > tolerance) {
            // Even extreme single samples can be caused by stale state or packet bursts.
            // Require repeated evidence instead of issuing an immediate violation.
            double bufferIncrease = exceedRatio > 1.75
                    ? 1.0 : 0.5 * (exceedRatio - 1.0);
            increaseBuffer(player, bufferIncrease);
            if (getBuffer(player) > MIN_SPEED_FLAG_BUFFER) {
                flag(player);
                resetBuffer(player);
            }
        } else {
            decreaseBuffer(player, 0.1);
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
    public double getMaxObservedSpeed(TrackedPlayer player) {
        return getState(player).maxObservedSpeed;
    }
}
