package dev.keenac.engine.core.check.impl.combat;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.player.TrackedPlayer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@CheckData(name = "Criticals A", stableKey = "keenac.combat.criticals", decay = 0.01, setbackVl = 10)
public class CriticalsCheck extends Check implements PacketCheck {
    private static final double MIN_DELTA_Y_CRITICAL = 0.11;
    private static final double MAX_DELTA_Y_CRITICAL = 0.5;
    private static final int INVALID_THRESHOLD = 4;
    private static final class PlayerState {
        int attacksSinceGround;
        int consecutiveInvalid;
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
        if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
            if (wrapper.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                handleAttack(player);
            }
        } else if (isMovementPacket(type)) {
            handleMovement(player);
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
    private void handleAttack(TrackedPlayer player) {
        PlayerState state = getState(player);
        if (player.isOnGround()) {
            state.consecutiveInvalid = Math.max(0, state.consecutiveInvalid - 1);
            return;
        }
        double deltaY = player.getDeltaY();
        boolean validCritMotion = deltaY > MIN_DELTA_Y_CRITICAL && deltaY < MAX_DELTA_Y_CRITICAL;
        if (!validCritMotion && deltaY >= -0.01) {
            state.consecutiveInvalid++;
            if (state.consecutiveInvalid >= INVALID_THRESHOLD) {
                flagWithSetback(player);
                state.consecutiveInvalid = 0;
            }
        } else {
            state.consecutiveInvalid = Math.max(0, state.consecutiveInvalid - 1);
        }
    }
    private void handleMovement(TrackedPlayer player) {
        PlayerState state = getState(player);
        if (player.isOnGround()) {
            state.attacksSinceGround = 0;
        } else {
            state.attacksSinceGround++;
        }
    }
    private boolean isMovementPacket(PacketTypeCommon type) {
        return type == PacketType.Play.Client.PLAYER_FLYING
                || type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
    }
}
