package dev.keenac.engine.core.check.impl.combat;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.CompatFlag;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.player.TrackedPlayer;
import dev.keenac.engine.core.util.PacketClock;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@CheckData(name = "Backtrack A", stableKey = "keenac.combat.backtrack", decay = 0.01, setbackVl = 15, compat = {CompatFlag.VIAVERSION_SENSITIVE, CompatFlag.RELAX_ON_MISMATCH}, relaxMultiplier = 1.2)
public class BacktrackCheck extends Check implements PacketCheck {
    private static final long MAX_BACKTRACK_DELAY_MS = 500;
    private static final double MIN_ATTACK_REACH = 2.5;
    private static final double IMPOSSIBLE_REACH = 6.0;
    private static final int MIN_SAMPLES = 10;
    private static final int MAX_DELAY_SAMPLES = 30;
    private static final double FLAG_BUFFER_THRESHOLD = 3.0;
    private static final class PlayerState {
        final ArrayDeque<Long> attackTimestamps = new ArrayDeque<>();
        long lastMovementTimestamp;
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
        PlayerState state = getState(player);
        if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
            if (wrapper.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) return;
            long now = PacketClock.currentTimeMillis();
            long delay = now - state.lastMovementTimestamp;
            state.attackTimestamps.addLast(delay);
            while (state.attackTimestamps.size() > MAX_DELAY_SAMPLES) {
                state.attackTimestamps.removeFirst();
            }
            if (delay > MAX_BACKTRACK_DELAY_MS) {
                increaseBuffer(player, 1.0);
                if (getBuffer(player) > FLAG_BUFFER_THRESHOLD) {
                    flag(player);
                    resetBuffer(player);
                }
            } else {
                decreaseBuffer(player, 0.1);
            }
        } else if (isMovementPacket(type)) {
            state.lastMovementTimestamp = PacketClock.currentTimeMillis();
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
    private boolean isMovementPacket(PacketTypeCommon type) {
        return type == PacketType.Play.Client.PLAYER_FLYING
                || type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
    }
}
