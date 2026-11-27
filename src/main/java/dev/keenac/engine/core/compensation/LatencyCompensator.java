package dev.keenac.engine.core.compensation;
import dev.keenac.engine.core.player.TrackedPlayer;
import dev.keenac.engine.core.util.MaterialUtils;
import org.bukkit.Material;
import org.bukkit.World;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
public final class LatencyCompensator {
    private static final long MAX_DEFER_MS = 2000;
    private static final int MAX_TICK_HISTORY = 100;
    private final Map<UUID, Queue<BlockChange>> deferredChanges = new ConcurrentHashMap<>();
    private final Map<UUID, CompensatedWorld> worldMap = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> latencyCache = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Integer, List<WorldChange>>> tickChanges = new ConcurrentHashMap<>();
    public void onBlockChange(UUID uuid, int x, int y, int z, Material material, int tick, World world) {
        long timestamp = System.currentTimeMillis();
        BlockChange change = new BlockChange(x, y, z, material, timestamp);
        Queue<BlockChange> queue = deferredChanges.computeIfAbsent(uuid, k -> new ConcurrentLinkedQueue<>());
        queue.add(change);
        while (queue.size() > 1000) {
            queue.poll();
        }
        boolean removal = material == null || MaterialUtils.isAirLike(material);
        recordTickChange(uuid, tick, removal
            ? WorldChange.blockBreak(tick, x, y, z)
            : WorldChange.blockPlace(tick, x, y, z, material));
    }
    public void onBlockChange(UUID uuid, int x, int y, int z, Material material, World world) {
        onBlockChange(uuid, x, y, z, material, 0, world);
    }
    public void recordChange(UUID uuid, int tick, WorldChange change) {
        recordTickChange(uuid, tick, change);
    }
    public List<WorldChange> getUnconfirmedChanges(UUID uuid, int confirmedTick, int currentTick) {
        Map<Integer, List<WorldChange>> playerTicks = tickChanges.get(uuid);
        if (playerTicks == null || playerTicks.isEmpty()) return java.util.Collections.emptyList();
        List<WorldChange> result = new ArrayList<>();
        for (int t = confirmedTick + 1; t < currentTick; t++) {
            List<WorldChange> changes = playerTicks.get(t);
            if (changes != null) {
                result.addAll(changes);
            }
        }
        return result;
    }
    public void updateLatency(UUID uuid, int latencyMs) {
        latencyCache.put(uuid, latencyMs);
    }
    public void processDeferredChanges(UUID uuid, TrackedPlayer player) {
        Queue<BlockChange> queue = deferredChanges.get(uuid);
        if (queue == null || queue.isEmpty()) return;
        int latencyMs = latencyCache.getOrDefault(uuid, player.getTransactionPing());
        long cutoffTime = System.currentTimeMillis() - latencyMs;
        CompensatedWorld world = worldMap.get(uuid);
        if (world == null) return;
        Queue<BlockChange> remaining = new ConcurrentLinkedQueue<>();
        while (!queue.isEmpty()) {
            BlockChange change = queue.poll();
            if (change.timestamp <= cutoffTime || System.currentTimeMillis() - change.timestamp > MAX_DEFER_MS) {
                world.onBlockChange(change.x, change.y, change.z, change.material);
            } else {
                remaining.add(change);
            }
        }
        queue.addAll(remaining);
    }
    public boolean isBlockBroken(UUID uuid, int x, int y, int z) {
        CompensatedWorld world = worldMap.get(uuid);
        if (world == null) return false;
        Material mat = world.getBlock(x, y, z);
        return mat == null || mat == Material.AIR;
    }
    public void onChunkUnload(UUID uuid, int chunkX, int chunkZ) {
        CompensatedWorld compensated = worldMap.get(uuid);
        if (compensated != null) compensated.onChunkUnload(chunkX, chunkZ);
    }
    public CompensatedWorld getWorld(UUID uuid, World bukkitWorld) {
        return worldMap.computeIfAbsent(uuid, k -> new CompensatedWorld(bukkitWorld));
    }
    public int getPendingChangeCount(UUID uuid) {
        Queue<BlockChange> queue = deferredChanges.get(uuid);
        return queue != null ? queue.size() : 0;
    }
    public void pruneTickHistory(int currentTick) {
        int cutoff = currentTick - MAX_TICK_HISTORY;
        for (Map<Integer, List<WorldChange>> playerTicks : tickChanges.values()) {
            playerTicks.keySet().removeIf(t -> t < cutoff);
        }
    }
    public void onPlayerQuit(UUID uuid) {
        deferredChanges.remove(uuid);
        worldMap.remove(uuid);
        latencyCache.remove(uuid);
        tickChanges.remove(uuid);
    }
    private void recordTickChange(UUID uuid, int tick, WorldChange change) {
        if (tick <= 0) return;
        Map<Integer, List<WorldChange>> playerTicks =
            tickChanges.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        List<WorldChange> changes = playerTicks.computeIfAbsent(tick, k -> new ArrayList<>());
        changes.add(change);
    }
    private static final class BlockChange {
        final int x, y, z;
        final Material material;
        long timestamp;
        BlockChange(int x, int y, int z, Material material, long timestamp) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.material = material;
            this.timestamp = timestamp;
        }
    }
}
