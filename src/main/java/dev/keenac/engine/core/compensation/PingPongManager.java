package dev.keenac.engine.core.compensation;
import dev.keenac.KeenAC;
import dev.keenac.engine.core.player.TrackedPlayer;
import java.util.ArrayList;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
public final class PingPongManager {
    private final KeenAC plugin;
    private final Map<UUID, PlayerPingState> playerStates = new ConcurrentHashMap<>();
    public PingPongManager(KeenAC plugin) {
        this.plugin = plugin;
    }
    public void onTickStart(TrackedPlayer player) {
        if (!player.isValid()) return;
        PlayerPingState state = getState(player);
        state.sendPing(plugin, player, true);
    }
    public void onTickEnd(TrackedPlayer player) {
        if (!player.isValid()) return;
        PlayerPingState state = getState(player);
        state.currentTick++;
        state.sendPing(plugin, player, false);
    }
    public boolean processPingResponse(TrackedPlayer player, short id) {
        PlayerPingState state = playerStates.get(player.getUuid());
        if (state == null) return false;
        PingRecord record = state.pendingPings.remove(id);
        if (record == null) return false;
        long rttNanos = System.nanoTime() - record.sendTimeNanos;
        int rttMs = (int) (rttNanos / 1_000_000);
        if (record.isStartPing) {
            state.confirmedTick = Math.max(state.confirmedTick, record.tick - 1);
            state.lastPreChangeRtt = rttMs;
        } else {
            state.confirmedTick = Math.max(state.confirmedTick, record.tick);
            state.lastPostChangeRtt = rttMs;
        }
        LatencyCompensator compensator = plugin.getLatencyCompensator();
        if (compensator != null) {
            compensator.updateLatency(player.getUuid(), getEstimatedLatencyMs(player));
        }
        fireConfirmedCallbacks(state);
        return true;
    }
    public void onTickConfirmed(TrackedPlayer player, int tick, Runnable callback) {
        PlayerPingState state = getState(player);
        if (tick <= state.confirmedTick) {
            runCallback(callback);
            return;
        }
        state.tickCallbacks
                .computeIfAbsent(tick, k -> new ConcurrentLinkedQueue<>())
                .add(callback);
    }
    private void runCallback(Runnable callback) {
        try {
            callback.run();
        } catch (Exception e) {
            plugin.getLogger().warning("KeenAC: ping-pong callback failed: " + e.getMessage());
        }
    }
    private void fireConfirmedCallbacks(PlayerPingState state) {
        int confirmed = state.confirmedTick;
        for (Integer tick : new ArrayList<>(state.tickCallbacks.keySet())) {
            if (tick == null || tick > confirmed) continue;
            Queue<Runnable> callbacks = state.tickCallbacks.remove(tick);
            if (callbacks == null) continue;
            for (Runnable callback : callbacks) {
                runCallback(callback);
            }
        }
    }
    public int getConfirmedTick(TrackedPlayer player) {
        PlayerPingState state = playerStates.get(player.getUuid());
        return state != null ? state.confirmedTick : 0;
    }
    public boolean isTickConfirmed(TrackedPlayer player, int tick) {
        return getConfirmedTick(player) >= tick;
    }
    public int getEstimatedLatencyMs(TrackedPlayer player) {
        PlayerPingState state = playerStates.get(player.getUuid());
        if (state == null) return 0;
        return (state.lastPreChangeRtt + state.lastPostChangeRtt) / 4;
    }
    public int getCurrentTick(TrackedPlayer player) {
        PlayerPingState state = playerStates.get(player.getUuid());
        return state != null ? state.currentTick : 0;
    }
    public void onPlayerQuit(UUID uuid) {
        playerStates.remove(uuid);
    }
    private PlayerPingState getState(TrackedPlayer player) {
        return playerStates.computeIfAbsent(player.getUuid(), k -> new PlayerPingState());
    }
    private static final class PlayerPingState {
        volatile int currentTick;
        volatile int confirmedTick;
        volatile int lastPreChangeRtt;
        volatile int lastPostChangeRtt;
        final Map<Short, PingRecord> pendingPings = new ConcurrentHashMap<>();
        final Map<Integer, Queue<Runnable>> tickCallbacks = new ConcurrentHashMap<>();
        void sendPing(KeenAC plugin, TrackedPlayer player, boolean isStartPing) {
            long now = System.nanoTime();
            pendingPings.entrySet().removeIf(entry ->
                    now - entry.getValue().sendTimeNanos > 30_000_000_000L);
            try {
                TransactionManager txMgr = plugin.getTransactionManager();
                short id = txMgr.sendPigPongTransaction(player);
                if (id >= 0) {
                    pendingPings.put(id, new PingRecord(currentTick, isStartPing, now));
                }
            } catch (Exception e) {
            }
        }
    }
    private static final class PingRecord {
        final int tick;
        final boolean isStartPing;
        final long sendTimeNanos;
        PingRecord(int tick, boolean isStartPing, long sendTimeNanos) {
            this.tick = tick;
            this.isStartPing = isStartPing;
            this.sendTimeNanos = sendTimeNanos;
        }
    }
}
