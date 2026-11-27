package dev.keenac.engine.core.check.impl.movement;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.player.TrackedPlayer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@CheckData(name = "Rotation Break A", stableKey = "keenac.movement.rotationbreak", decay = 0.02, setbackVl = 15)
public class RotationBreakCheck extends Check implements PacketCheck {
    private static final float MAX_ROTATION_DELTA = 45.0f;
    private static final class PlayerState {
        float breakStartYaw;
        float breakStartPitch;
        boolean breaking;
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
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_DIGGING) return;
        WrapperPlayClientPlayerDigging wrapper = new WrapperPlayClientPlayerDigging(event);
        DiggingAction action = wrapper.getAction();
        PlayerState state = getState(player);
        if (action == DiggingAction.START_DIGGING) {
            state.breaking = true;
            state.breakStartYaw = player.getYaw();
            state.breakStartPitch = player.getPitch();
        } else if (action == DiggingAction.FINISHED_DIGGING) {
            if (!state.breaking) return;
            state.breaking = false;
            float deltaYaw = Math.abs(player.getYaw() - state.breakStartYaw);
            float deltaPitch = Math.abs(player.getPitch() - state.breakStartPitch);
            if (deltaYaw > 180) deltaYaw = 360 - deltaYaw;
            if (deltaYaw > MAX_ROTATION_DELTA || deltaPitch > MAX_ROTATION_DELTA) {
                increaseBuffer(player, 1.0);
                if (getBuffer(player) > 3.0) {
                    flag(player);
                    resetBuffer(player);
                }
            } else {
                decreaseBuffer(player, 0.1);
            }
        } else if (action == DiggingAction.CANCELLED_DIGGING) {
            state.breaking = false;
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
}
