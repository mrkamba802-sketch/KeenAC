package dev.keenac.engine.core.check.impl.movement;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.CompatFlag;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.player.TrackedPlayer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@CheckData(name = "NoSlow A", stableKey = "keenac.movement.noslow", decay = 0.01, setbackVl = 15, compat = {CompatFlag.RELAX_ON_MISMATCH}, relaxMultiplier = 1.3)
public class NoSlowCheck extends Check implements PacketCheck {
    private static final double BASE_WALK_SPEED = 0.102;
    private static final double SPRINT_MULTIPLIER = 1.3;
    private static final double SNEAK_MULTIPLIER = 0.3;
    private static final double USING_ITEM_SLOWDOWN = 0.2;
    private static final double MIN_SPEED_FOR_CHECK = 0.05;
    private static final int MIN_USING_ITEM_TICKS = 3;
    private static final class PlayerState {
        boolean usingItem;
        int usingItemTicks;
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
        if (type == PacketType.Play.Client.USE_ITEM) {
            PlayerState state = getState(player);
            state.usingItem = true;
            state.usingItemTicks = 0;
            return;
        }
        if (!isMovementPacket(event)) return;
        PlayerState state = getState(player);
        if (state.usingItem) {
            state.usingItemTicks++;
            if (state.usingItemTicks > 20) {
                state.usingItem = false;
                state.usingItemTicks = 0;
            }
        }
        double deltaX = player.getDeltaX();
        double deltaZ = player.getDeltaZ();
        double horizontalSpeed = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        if (horizontalSpeed < MIN_SPEED_FOR_CHECK) {
            decreaseBuffer(player, 0.1);
            return;
        }
        double maxExpectedSpeed = BASE_WALK_SPEED * SPRINT_MULTIPLIER;
        if (state.usingItem && horizontalSpeed > maxExpectedSpeed * 0.9) {
            increaseBuffer(player, 0.8);
            if (getBuffer(player) > 4.0) {
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
    private boolean isMovementPacket(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        return type == PacketType.Play.Client.PLAYER_FLYING
                || type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
    }
}
