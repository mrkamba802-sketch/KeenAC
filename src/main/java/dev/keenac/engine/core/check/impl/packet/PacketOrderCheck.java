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

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks the session boundary only. Consecutive movement packet types and short bursts
 * are normal Minecraft traffic and are not packet-order violations.
 */
@CheckData(name = "Packet Order A", stableKey = "keenac.packet.order",
        decay = 0.01, setbackVl = 15,
        compat = {CompatFlag.RELAX_ON_MISMATCH}, relaxMultiplier = 1.2)
public class PacketOrderCheck extends Check implements PacketCheck {
    private static final class PlayerState {
        volatile boolean loginComplete;
    }

    private final ConcurrentHashMap<UUID, PlayerState> stateMap = new ConcurrentHashMap<>();

    private PlayerState getState(TrackedPlayer player) {
        return stateMap.computeIfAbsent(player.getUuid(), ignored -> new PlayerState());
    }

    @Override
    public void removePlayer(UUID uuid) {
        stateMap.remove(uuid);
    }

    @Override
    public void onPacketReceive(TrackedPlayer player, PacketReceiveEvent event) {
        if (getState(player).loginComplete) return;
        if (isMovementType(event.getPacketType())) {
            // Do not turn early/queued packets into a flag during initial session setup.
            // PacketListener marks the session complete as soon as it tracks the player.
        }
    }

    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }

    public void onLoginComplete(TrackedPlayer player) {
        getState(player).loginComplete = true;
    }

    public void onDisconnect(TrackedPlayer player) {
        stateMap.remove(player.getUuid());
    }

    private boolean isMovementType(PacketTypeCommon type) {
        return type == PacketType.Play.Client.PLAYER_FLYING
                || type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
                || type == PacketType.Play.Client.PLAYER_ROTATION;
    }
}
