package dev.keenac.engine.core.compensation;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowConfirmation;
import dev.keenac.KeenAC;
import dev.keenac.engine.core.player.TrackedPlayer;
import org.bukkit.Bukkit;
import java.util.Queue;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
public final class TransactionManager {
    private final KeenAC plugin;
    private final Map<UUID, TransactionState> playerTransactions = new ConcurrentHashMap<>();
    private final AtomicInteger skippedTransactions = new AtomicInteger(0);
    private final AtomicInteger unknownResponses = new AtomicInteger(0);
    private final AtomicInteger noResponseCount = new AtomicInteger(0);
    public TransactionManager(KeenAC plugin) {
        this.plugin = plugin;
    }
    public void sendTransaction(TrackedPlayer player) {
        if (!player.isValid()) return;
        TransactionState state = playerTransactions.computeIfAbsent(
                player.getUuid(), k -> new TransactionState());
        short id = state.nextTransactionId();
        long sendTime = System.nanoTime();
        state.pendingTransactions.add(new PendingTransaction(id, sendTime));
        try {
            if (player.getProtocolVersion() >= 756) {
                WrapperPlayServerPing ping = new WrapperPlayServerPing((int) id);
                PacketEvents.getAPI().getPlayerManager().sendPacket(player.getPlayer(), ping);
            } else {
                WrapperPlayServerWindowConfirmation confirm = new WrapperPlayServerWindowConfirmation(
                        0, id, false);
                PacketEvents.getAPI().getPlayerManager().sendPacket(player.getPlayer(), confirm);
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("KeenAC: Failed to send transaction packet: " + e.getMessage());
        }
    }
    public boolean hasPendingTransaction(UUID uuid, short id) {
        TransactionState state = playerTransactions.get(uuid);
        if (state == null) return false;
        for (PendingTransaction transaction : state.pendingTransactions) {
            if (transaction.id == id) return true;
        }
        return false;
    }
    public boolean processTransaction(TrackedPlayer player, short id) {
        TransactionState state = playerTransactions.get(player.getUuid());
        if (state == null) return false;
        long receiveTime = System.nanoTime();
        PendingTransaction matched = null;
        Queue<PendingTransaction> remaining = new ConcurrentLinkedQueue<>();
        while (!state.pendingTransactions.isEmpty()) {
            PendingTransaction tx = state.pendingTransactions.poll();
            if (tx.id == id) {
                matched = tx;
            } else {
                remaining.add(tx);
            }
        }
        state.pendingTransactions.addAll(remaining);
        if (matched != null) {
            long pingNanos = receiveTime - matched.sendTime;
            player.setTransactionPing((int) (pingNanos / 1_000_000));
        } else {
            unknownResponses.incrementAndGet();
        }
        Runnable callback = state.callbacks.remove(id);
        if (callback != null) {
            try {
                callback.run();
            } catch (Exception e) {
                plugin.getLogger().warning(
                    "KeenAC: transaction callback for " + player.getName() + " failed: " + e.getMessage());
            }
        }
        return matched != null;
    }
    public int sweepTimedOutTransactions(UUID uuid, long timeoutMs) {
        TransactionState state = playerTransactions.get(uuid);
        if (state == null) return 0;
        long deadline = System.nanoTime() - (timeoutMs * 1_000_000L);
        int timedOut = 0;
        Queue<PendingTransaction> remaining = new ConcurrentLinkedQueue<>();
        PendingTransaction tx;
        while ((tx = state.pendingTransactions.poll()) != null) {
            if (tx.sendTime < deadline) {
                timedOut++;
                state.callbacks.remove(tx.id);
            } else {
                remaining.add(tx);
            }
        }
        state.pendingTransactions.addAll(remaining);
        if (timedOut > 0) {
            skippedTransactions.addAndGet(timedOut);
            noResponseCount.addAndGet(timedOut);
        }
        return timedOut;
    }
    public void addCallback(TrackedPlayer player, short transactionId, Runnable callback) {
        TransactionState state = playerTransactions.computeIfAbsent(
                player.getUuid(), k -> new TransactionState());
        state.callbacks.put(transactionId, callback);
    }
    public short sendTransactionWithCallback(TrackedPlayer player, Runnable callback) {
        TransactionState state = playerTransactions.computeIfAbsent(
                player.getUuid(), k -> new TransactionState());
        short id = state.nextTransactionId();
        long sendTime = System.nanoTime();
        state.pendingTransactions.add(new PendingTransaction(id, sendTime));
        state.callbacks.put(id, callback);
        try {
            if (player.getProtocolVersion() >= 756) {
                WrapperPlayServerPing ping = new WrapperPlayServerPing((int) id);
                PacketEvents.getAPI().getPlayerManager().sendPacket(player.getPlayer(), ping);
            } else {
                WrapperPlayServerWindowConfirmation confirm = new WrapperPlayServerWindowConfirmation(
                        0, id, false);
                PacketEvents.getAPI().getPlayerManager().sendPacket(player.getPlayer(), confirm);
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("KeenAC: Failed to send transaction packet: " + e.getMessage());
        }
        return id;
    }
    public short sendPigPongTransaction(TrackedPlayer player) {
        if (!player.isValid()) return -1;
        TransactionState state = playerTransactions.computeIfAbsent(
                player.getUuid(), k -> new TransactionState());
        short id = state.nextTransactionId();
        long sendTime = System.nanoTime();
        state.pendingTransactions.add(new PendingTransaction(id, sendTime));
        try {
            if (player.getProtocolVersion() >= 756) {
                WrapperPlayServerPing ping = new WrapperPlayServerPing((int) id);
                PacketEvents.getAPI().getPlayerManager().sendPacket(player.getPlayer(), ping);
            } else {
                WrapperPlayServerWindowConfirmation confirm = new WrapperPlayServerWindowConfirmation(
                        0, id, false);
                PacketEvents.getAPI().getPlayerManager().sendPacket(player.getPlayer(), confirm);
            }
            return id;
        } catch (Exception e) {
            Bukkit.getLogger().warning("KeenAC: Failed to send ping-pong transaction: " + e.getMessage());
            return -1;
        }
    }
    public void onPlayerQuit(UUID uuid) {
        playerTransactions.remove(uuid);
    }
    public void incrementSkippedTransactions() {
        skippedTransactions.incrementAndGet();
    }
    public void incrementNoResponseCount() {
        noResponseCount.incrementAndGet();
    }
    public int getSkippedTransactions() {
        return skippedTransactions.get();
    }
    public int getUnknownResponses() {
        return unknownResponses.get();
    }
    public int getNoResponseCount() {
        return noResponseCount.get();
    }
    public int getPendingCount(UUID uuid) {
        TransactionState state = playerTransactions.get(uuid);
        return state != null ? state.pendingTransactions.size() : 0;
    }
    private static final class PendingTransaction {
        final short id;
        final long sendTime;
        PendingTransaction(short id, long sendTime) {
            this.id = id;
            this.sendTime = sendTime;
        }
    }
    private static final class TransactionState {
        final Queue<PendingTransaction> pendingTransactions = new ConcurrentLinkedQueue<>();
        final Map<Short, Runnable> callbacks = new ConcurrentHashMap<>();
        final AtomicInteger transactionCounter = new AtomicInteger(0);
    short nextTransactionId() {
            return (short) (transactionCounter.incrementAndGet() & 0x7FFF);
        }
    }
}
