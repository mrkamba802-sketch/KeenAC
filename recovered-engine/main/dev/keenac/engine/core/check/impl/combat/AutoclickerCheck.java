package dev.keenac.engine.core.check.impl.combat;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import dev.keenac.KeenAC;
import dev.keenac.engine.compat.stats.StatisticalMetrics;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.CompatFlag;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.config.KeenACConfig;
import dev.keenac.engine.core.player.TrackedPlayer;
import dev.keenac.engine.core.version.VersionBracket;
import dev.keenac.engine.core.util.PacketClock;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@CheckData(name = "Autoclicker A", stableKey = "keenac.combat.autoclicker", decay = 0.01, setbackVl = 20,
    compat = {CompatFlag.RELAX_ON_MISMATCH},
    relaxMultiplier = 1.5)
public class AutoclickerCheck extends Check implements PacketCheck {
    private static final int MIN_CLICKS_FOR_EVAL = 20;
    private static final long CLICK_WINDOW_MS = 3000;
    private static final double LOW_CPS_LEGACY = 6.0;
    private static final double HIGH_CPS_LEGACY = 20.0;
    private static final double LOW_CPS_MODERN = 1.0;
    private static final double HIGH_CPS_MODERN = 8.0;
    private static final double STD_DEV_AUTOCLICKER_THRESHOLD = 3.0;
    private static final double MIN_HUMAN_STD_DEV = 15.0;
    private static final double IMPOSSIBLE_MEAN_INTERVAL_MS = 25.0;
    private static final double HIGH_RATE_FLAG_BUFFER = 10.0;
    static final class PlayerState {
        final ArrayDeque<Long> clickTimestamps = new ArrayDeque<>();
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
        if (type != PacketType.Play.Client.INTERACT_ENTITY) return;
        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
        if (wrapper.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) return;
        PlayerState state = getState(player);
        long now = PacketClock.currentTimeMillis();
        state.clickTimestamps.addLast(now);
        while (!state.clickTimestamps.isEmpty() && now - state.clickTimestamps.peekFirst() > CLICK_WINDOW_MS) {
            state.clickTimestamps.removeFirst();
        }
        if (state.clickTimestamps.size() < MIN_CLICKS_FOR_EVAL) return;
        int protocol = player.getProtocolVersion();
        VersionBracket bracket = VersionBracket.fromProtocol(protocol);
        double lowCPS, highCPS;
        if (player.isBedrock()) {
            KeenACConfig cfg = KeenAC.getInstance().getKeenACConfig();
            lowCPS = LOW_CPS_MODERN;
            highCPS = cfg.getBedrockCpsLimit();
        } else if (bracket == VersionBracket.LEGACY) {
            lowCPS = LOW_CPS_LEGACY;
            highCPS = HIGH_CPS_LEGACY;
        } else {
            lowCPS = LOW_CPS_MODERN;
            highCPS = HIGH_CPS_MODERN;
        }
        double cps = state.clickTimestamps.size() / (CLICK_WINDOW_MS / 1000.0);
        if (cps < lowCPS) {
            decreaseBuffer(player, 0.2);
            return;
        }
        if (cps > highCPS) {
            double meanMs = meanInterval(state);
            if (meanMs < IMPOSSIBLE_MEAN_INTERVAL_MS) {
                increaseBuffer(player, 0.5);
                if (getBuffer(player) > HIGH_RATE_FLAG_BUFFER) {
                    flag(player);
                    resetBuffer(player);
                }
            } else {
                decreaseBuffer(player, 0.2);
            }
            return;
        }
        double stdDev = calculateStdDev(state);
        if (stdDev < STD_DEV_AUTOCLICKER_THRESHOLD && cps > lowCPS) {
            double[] intervals = intervalsOf(state);
            double entropy = StatisticalMetrics.entropy(intervals);
            double strength = entropy < 1.0 ? 1.8 : 1.5;
            increaseBuffer(player, strength);
            if (getBuffer(player) > 4.0) {
                flag(player);
                resetBuffer(player);
            }
        } else if (stdDev < MIN_HUMAN_STD_DEV) {
            increaseBuffer(player, 0.5);
            if (getBuffer(player) > 6.0) {
                flag(player);
                resetBuffer(player);
            }
        } else {
            decreaseBuffer(player, 0.2);
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
    static double calculateStdDev(PlayerState state) {
        if (state == null || state.clickTimestamps.size() < 2) return Double.MAX_VALUE;
        int intervals = state.clickTimestamps.size() - 1;
        Iterator<Long> cursor = state.clickTimestamps.iterator();
        long first = cursor.next();
        long previous = first;
        double mean = 0;
        while (cursor.hasNext()) {
            long ts = cursor.next();
            mean += ts - previous;
            previous = ts;
        }
        mean /= intervals;
        double variance = 0;
        cursor = state.clickTimestamps.iterator();
        previous = cursor.next();
        while (cursor.hasNext()) {
            long ts = cursor.next();
            double diff = (ts - previous) - mean;
            variance += diff * diff;
            previous = ts;
        }
        variance /= intervals;
        return Math.sqrt(variance);
    }
    static double meanInterval(PlayerState state) {
        if (state == null || state.clickTimestamps.size() < 2) return Double.MAX_VALUE;
        long span = state.clickTimestamps.peekLast() - state.clickTimestamps.peekFirst();
        return (double) span / (state.clickTimestamps.size() - 1);
    }
    static double[] intervalsOf(PlayerState state) {
        if (state == null || state.clickTimestamps.size() < 2) return new double[0];
        double[] intervals = new double[state.clickTimestamps.size() - 1];
        Iterator<Long> cursor = state.clickTimestamps.iterator();
        long previous = cursor.next();
        int index = 0;
        while (cursor.hasNext()) {
            long ts = cursor.next();
            intervals[index++] = ts - previous;
            previous = ts;
        }
        return intervals;
    }
}
