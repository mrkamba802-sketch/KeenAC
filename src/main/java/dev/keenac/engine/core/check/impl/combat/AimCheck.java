package dev.keenac.engine.core.check.impl.combat;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerRotation;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.player.TrackedPlayer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@CheckData(name = "Aim A", stableKey = "keenac.combat.aim", decay = 0.01, setbackVl = 10)
public class AimCheck extends Check implements PacketCheck {
    private static final double INSTANT_SNAP_THRESHOLD = 100.0;
    private static final double INSTANT_SNAP_PITCH_THRESHOLD = 60.0;
    private static final float ROTATION_MODULO = 360.0f;
    private static final int MIN_ROTATION_SAMPLES = 5;
    private static final double AIMBOT_YAW_VARIANCE_THRESHOLD = 0.5;
    private static final double MIN_DELTA_THRESHOLD = 0.015;
    private static final double SNAP_BUFFER_FLAG_THRESHOLD = 3.0;
    static float normalizeYawDelta(float deltaYaw) {
        float normalized = deltaYaw % ROTATION_MODULO;
        if (normalized >= 180.0f) normalized -= ROTATION_MODULO;
        if (normalized < -180.0f) normalized += ROTATION_MODULO;
        return normalized;
    }
    static boolean isInstantSnap(double absDeltaYaw, double absDeltaPitch) {
        return absDeltaYaw > INSTANT_SNAP_THRESHOLD || absDeltaPitch > INSTANT_SNAP_PITCH_THRESHOLD;
    }
    private static final class PlayerState {
        float lastYaw;
        float lastPitch;
        boolean hasRotation;
        double yawAccumulator;
        double pitchAccumulator;
        int rotationCount;
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
        PacketTypeCommon type = event.getPacketType();
        if (type != PacketType.Play.Client.PLAYER_ROTATION
                && type != PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            return;
        }
        PlayerState state = getState(player);
        WrapperPlayClientPlayerRotation wrapper = new WrapperPlayClientPlayerRotation(event);
        float yaw = wrapper.getYaw();
        float pitch = wrapper.getPitch();
        if (!state.hasRotation) {
            state.lastYaw = yaw;
            state.lastPitch = pitch;
            state.hasRotation = true;
            return;
        }
        float deltaYaw = normalizeYawDelta(yaw - state.lastYaw);
        float deltaPitch = pitch - state.lastPitch;
        double absDeltaYaw = Math.abs(deltaYaw);
        double absDeltaPitch = Math.abs(deltaPitch);
        if (absDeltaYaw < MIN_DELTA_THRESHOLD && absDeltaPitch < MIN_DELTA_THRESHOLD) {
            state.lastYaw = yaw;
            state.lastPitch = pitch;
            return;
        }
        if (isInstantSnap(absDeltaYaw, absDeltaPitch)) {
            increaseBuffer(player, 1.0);
            if (getBuffer(player) > SNAP_BUFFER_FLAG_THRESHOLD) {
                flag(player);
                resetBuffer(player);
            }
        }
        state.yawAccumulator += absDeltaYaw;
        state.pitchAccumulator += absDeltaPitch;
        state.rotationCount++;
        if (state.rotationCount >= MIN_ROTATION_SAMPLES) {
            double avgYawDelta = state.yawAccumulator / state.rotationCount;
            double avgPitchDelta = state.pitchAccumulator / state.rotationCount;
            if (avgYawDelta > 0.1 && avgPitchDelta < AIMBOT_YAW_VARIANCE_THRESHOLD) {
                increaseBuffer(player, 0.3);
                if (getBuffer(player) > 5.0) {
                    flag(player);
                    resetBuffer(player);
                }
            } else {
                decreaseBuffer(player, 0.1);
            }
            state.yawAccumulator = 0;
            state.pitchAccumulator = 0;
            state.rotationCount = 0;
        }
        state.lastYaw = yaw;
        state.lastPitch = pitch;
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
}
