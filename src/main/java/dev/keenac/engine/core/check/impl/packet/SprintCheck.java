package dev.keenac.engine.core.check.impl.packet;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.player.TrackedPlayer;
import dev.keenac.engine.core.util.PacketClock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@CheckData(name = "Sprint A", stableKey = "keenac.packet.sprint", decay = 0.01, setbackVl = 15)
public class SprintCheck extends Check implements PacketCheck {
    private static final int MAX_SPRINT_TOGGLE_PER_SECOND = 4;
    private static final long TOGGLE_WINDOW_MS = 1000;
    private static final int MIN_TOGGLE_FLAGS = 3;
    private static final class PlayerState {
        boolean lastSprinting;
        long lastToggleTime;
        int toggleCount;
        int consecutiveFlags;
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
        if (!isMovementPacket(event)) return;
        PlayerState state = getState(player);
        boolean sprinting = player.isSprinting();
        long now = PacketClock.currentTimeMillis();
        if (now - state.lastToggleTime > TOGGLE_WINDOW_MS) {
            if (state.toggleCount > MAX_SPRINT_TOGGLE_PER_SECOND) {
                state.consecutiveFlags++;
                if (state.consecutiveFlags >= MIN_TOGGLE_FLAGS) {
                    flag(player);
                    state.consecutiveFlags = 0;
                }
            } else {
                state.consecutiveFlags = Math.max(0, state.consecutiveFlags - 1);
            }
            state.toggleCount = 0;
            state.lastToggleTime = now;
        }
        if (sprinting != state.lastSprinting) {
            state.toggleCount++;
        }
        state.lastSprinting = sprinting;
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
    private boolean isMovementPacket(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        return type == PacketType.Play.Client.PLAYER_FLYING
                || type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
    }
}
