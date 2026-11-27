package dev.keenac.engine.core.check.impl.packet;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.CompatFlag;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.player.TrackedPlayer;
import dev.keenac.engine.core.util.PacketClock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
@CheckData(name = "Packet Order A", stableKey = "keenac.packet.order", decay = 0.01, setbackVl = 15, compat = {CompatFlag.RELAX_ON_MISMATCH}, relaxMultiplier = 1.2)
public class PacketOrderCheck extends Check implements PacketCheck {
    private static final int MAX_MOVEMENT_BEFORE_LOGIN = 0;
    private static final int DUPLICATE_PACKET_THRESHOLD = 5;
    private static final long PACKET_BURST_WINDOW_MS = 100;
    private static final int MAX_PACKETS_IN_BURST = 15;
    private static final class PlayerState {
        boolean loginComplete;
        int movementCountBeforeLogin;
        int duplicatePacketCount;
        long lastPacketHash;
        final ConcurrentLinkedDeque<Long> packetBurst = new ConcurrentLinkedDeque<>();
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
        long now = PacketClock.currentTimeMillis();
        PlayerState state = getState(player);
        state.packetBurst.addLast(now);
        while (!state.packetBurst.isEmpty() && now - state.packetBurst.peekFirst() > PACKET_BURST_WINDOW_MS) {
            state.packetBurst.removeFirst();
        }
        if (state.packetBurst.size() > MAX_PACKETS_IN_BURST) {
            increaseBuffer(player, 1.0);
            if (getBuffer(player) > 3.0) {
                flag(player);
                resetBuffer(player);
            }
        }
        long currentHash = type.hashCode();
        if (currentHash == state.lastPacketHash && isMovementType(type)) {
            state.duplicatePacketCount++;
            if (state.duplicatePacketCount > DUPLICATE_PACKET_THRESHOLD) {
                flag(player);
                state.duplicatePacketCount = 0;
            }
        } else {
            state.duplicatePacketCount = 0;
        }
        state.lastPacketHash = currentHash;
        if (!state.loginComplete) {
            if (isMovementType(type)) {
                state.movementCountBeforeLogin++;
            }
            if (type == PacketType.Play.Client.PLAYER_POSITION
                    || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
                state.loginComplete = true;
                if (state.movementCountBeforeLogin > MAX_MOVEMENT_BEFORE_LOGIN) {
                    flag(player);
                }
            }
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
    public void onLoginComplete(TrackedPlayer player) {
        getState(player).loginComplete = true;
    }
    public void onDisconnect(TrackedPlayer player) {
        PlayerState state = getState(player);
        state.loginComplete = false;
        state.movementCountBeforeLogin = 0;
        state.duplicatePacketCount = 0;
        state.lastPacketHash = 0;
        state.packetBurst.clear();
    }
    private boolean isMovementType(PacketTypeCommon type) {
        return type == PacketType.Play.Client.PLAYER_FLYING
                || type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
                || type == PacketType.Play.Client.PLAYER_ROTATION;
    }
}
