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
import dev.keenac.engine.core.util.PacketClock;

import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects only sustained excessive movement-packet rates.
 * Low packet rates are ignored because lag, packet loss and idle clients are legitimate.
 */
@CheckData(name = "Timer A", stableKey = "keenac.movement.timer",
        decay = 0.005, setbackVl = 25,
        compat = {CompatFlag.RELAX_ON_MISMATCH}, relaxMultiplier = 1.2)
public class TimerCheck extends Check implements PacketCheck {
    private static final long WINDOW_MS = 1_000L;
    private static final long EVALUATION_INTERVAL_MS = 1_000L;
    private static final int MAX_PACKETS_PER_SECOND = 45;
    private static final int REQUIRED_FAST_WINDOWS = 3;
    private static final double FLAG_BUFFER = 3.0;

    private static final class PlayerState {
        final ArrayDeque<Long> timestamps = new ArrayDeque<>();
        long lastEvaluation;
        int consecutiveFastWindows;
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
        if (!isMovementPacket(event)) return;

        PlayerState state = getState(player);
        long now = PacketClock.currentTimeMillis();

        if (state.lastEvaluation == 0L || now < state.lastEvaluation) {
            state.lastEvaluation = now;
            state.timestamps.clear();
            state.consecutiveFastWindows = 0;
        }

        state.timestamps.addLast(now);
        while (!state.timestamps.isEmpty() && now - state.timestamps.peekFirst() > WINDOW_MS) {
            state.timestamps.removeFirst();
        }

        if (now - state.lastEvaluation < EVALUATION_INTERVAL_MS) return;

        int packetsInWindow = state.timestamps.size();
        if (packetsInWindow > MAX_PACKETS_PER_SECOND) {
            state.consecutiveFastWindows++;
            if (state.consecutiveFastWindows >= REQUIRED_FAST_WINDOWS) {
                increaseBuffer(player, 1.0);
                if (getBuffer(player) > FLAG_BUFFER) {
                    flag(player);
                    resetBuffer(player);
                    state.consecutiveFastWindows = 0;
                }
            }
        } else {
            state.consecutiveFastWindows = 0;
            decreaseBuffer(player, 0.25);
        }

        state.lastEvaluation = now;
    }

    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }

    private boolean isMovementPacket(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        return type == PacketType.Play.Client.PLAYER_FLYING
                || type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
                || type == PacketType.Play.Client.PLAYER_ROTATION;
    }
}
