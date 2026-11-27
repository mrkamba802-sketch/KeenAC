package dev.keenac.engine.core.check.impl.movement;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.CompatFlag;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.physics.PhysicsConstants;
import dev.keenac.engine.core.physics.PredictionEngine;
import dev.keenac.engine.core.player.TrackedPlayer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@CheckData(name = "Gravity", stableKey = "keenac.movement.gravity", decay = 0.02, setbackVl = 15,
    compat = {CompatFlag.RELAX_ON_MISMATCH}, relaxMultiplier = 1.3)
public class GravityCheck extends Check implements PacketCheck {
    private static final double ACCELERATION_TOLERANCE = 0.02;
    private static final int MIN_CONSECUTIVE = 4;
    private static final double BUFFER_THRESHOLD = 3.0;
    private static final double TERMINAL_SPEED = -3.92;
    private static final class PlayerState {
        double lastDeltaY;
        boolean primed;
    }
    private final ConcurrentHashMap<UUID, PlayerState> stateMap = new ConcurrentHashMap<>();
    private PlayerState getState(TrackedPlayer player) {
        return stateMap.computeIfAbsent(player.getUuid(), k -> new PlayerState());
    }
    @Override
    public void removePlayer(UUID uuid) {
        stateMap.remove(uuid);
    }
    @Override
    public void onPacketReceive(TrackedPlayer player, PacketReceiveEvent event) {
        if (!PredictionEngine.isMovementPacket(event)) return;
        PlayerState state = getState(player);
        if (player.isOnGround()) {
            state.primed = false;
            reward(player);
            return;
        }
        double deltaY = player.getDeltaY();
        if (shouldSkip(player, deltaY)) {
            state.primed = false;
            reward(player);
            return;
        }
        if (!state.primed) {
            state.lastDeltaY = deltaY;
            state.primed = true;
            reward(player);
            return;
        }
        double predictedDeltaY = predictNext(player, state.lastDeltaY);
        double observedAcceleration = deltaY - state.lastDeltaY;
        double predictedAcceleration = predictedDeltaY - state.lastDeltaY;
        double deviation = Math.abs(observedAcceleration - predictedAcceleration);
        state.lastDeltaY = deltaY;
        if (deviation > ACCELERATION_TOLERANCE) {
            increaseBuffer(player, 1.0);
            if (getBuffer(player) > BUFFER_THRESHOLD) {
                flag(player);
                resetBuffer(player);
            }
        } else {
            reward(player);
        }
    }
    private double predictNext(TrackedPlayer player, double currentDeltaY) {
        return PredictionEngine.predictDeltaY(
            currentDeltaY,
            PredictionEngine.checkInWater(player),
            PredictionEngine.checkInLava(player),
            player.isClimbing(),
            PredictionEngine.checkOnHoney(player),
            PredictionEngine.checkSlowFalling(player),
            PredictionEngine.checkLevitation(player),
            PredictionEngine.getLevitationAmplifier(player),
            PredictionEngine.checkFallFlying(player),
            PredictionEngine.checkRiptiding(player),
            player.getAttributeSnapshot().gravity());
    }
    static boolean shouldSkip(TrackedPlayer player, double deltaY) {
        if (player.isSwimming() || player.isClimbing()) return true;
        if (PredictionEngine.checkInWater(player) || PredictionEngine.checkInLava(player)) return true;
        if (PredictionEngine.checkOnHoney(player)) return true;
        if (PredictionEngine.checkSlowFalling(player)) return true;
        if (PredictionEngine.checkLevitation(player)) return true;
        if (player.isGliding()) return true;
        if (PredictionEngine.checkRiptiding(player)) return true;
        return deltaY <= TERMINAL_SPEED;
    }
    static double freeFallAcceleration() {
        return -PhysicsConstants.GRAVITY * PhysicsConstants.AIR_DRAG;
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
}
