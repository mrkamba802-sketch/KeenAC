package dev.keenac.engine.core.check.impl.combat;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.player.TrackedPlayer;
import dev.keenac.engine.core.util.PacketClock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@CheckData(name = "Macro A", stableKey = "keenac.combat.macro", decay = 0.01, setbackVl = 20)
public class MacroCheck extends Check implements PacketCheck {
    private static final int PATTERN_WINDOW = 50;
    private static final int MIN_REPEAT_COUNT = 8;
    private static final double REPETITION_THRESHOLD = 0.9;
    private static final class PlayerState {
        final Map<String, Integer> movementPatterns = new ConcurrentHashMap<>();
        long lastPatternTime;
        StringBuilder patternBuffer = new StringBuilder();
        int totalPatterns;
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
        PacketTypeCommon type = event.getPacketType();
        if (!isMovementType(type)) return;
        PlayerState state = getState(player);
        long now = PacketClock.currentTimeMillis();
        if (now - state.lastPatternTime > 100) {
            flushPattern(player, state);
        }
        state.lastPatternTime = now;
        char movementCode = getMovementCode(type, player);
        state.patternBuffer.append(movementCode);
        state.totalPatterns++;
        if (state.patternBuffer.length() > PATTERN_WINDOW) {
            state.patternBuffer.deleteCharAt(0);
        }
        if (state.patternBuffer.length() >= 10) {
            detectRepetition(player, state);
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
    private void flushPattern(TrackedPlayer player, PlayerState state) {
        if (state.patternBuffer.length() >= 5) {
            String pattern = state.patternBuffer.toString();
            state.movementPatterns.merge(pattern, 1, Integer::sum);
        }
        state.patternBuffer = new StringBuilder();
    }
    private void detectRepetition(TrackedPlayer player, PlayerState state) {
        String current = state.patternBuffer.toString();
        if (current.length() < 10) return;
        int occurrences = 0;
        for (Map.Entry<String, Integer> entry : state.movementPatterns.entrySet()) {
            if (entry.getKey().contains(current.substring(current.length() - 5))) {
                occurrences += entry.getValue();
            }
        }
        double ratio = (double) occurrences / Math.max(state.totalPatterns, 1);
        if (ratio > REPETITION_THRESHOLD && occurrences >= MIN_REPEAT_COUNT) {
            increaseBuffer(player, 2.0);
            if (getBuffer(player) > 5.0) {
                flag(player);
                resetBuffer(player);
                state.movementPatterns.clear();
                state.totalPatterns = 0;
            }
        }
    }
    private char getMovementCode(PacketTypeCommon type, TrackedPlayer player) {
        if (type == PacketType.Play.Client.PLAYER_POSITION) return 'P';
        if (type == PacketType.Play.Client.PLAYER_ROTATION) return 'R';
        if (type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) return 'M';
        if (type == PacketType.Play.Client.PLAYER_FLYING) return 'F';
        return 'X';
    }
    private boolean isMovementType(PacketTypeCommon type) {
        return type == PacketType.Play.Client.PLAYER_POSITION
            || type == PacketType.Play.Client.PLAYER_ROTATION
            || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
            || type == PacketType.Play.Client.PLAYER_FLYING;
    }
}
