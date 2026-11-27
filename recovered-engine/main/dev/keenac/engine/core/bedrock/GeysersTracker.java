package dev.keenac.engine.core.bedrock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
public class GeysersTracker {
    private static final int SCAN_RANGE_XZ = 2;
    private static final int SCAN_DEPTH = 32;
    private static final int SCAN_HEIGHT = 4;
    private static final long TICK_INTERVAL = 1L;
    private final Map<UUID, GeyserState> playerStates = new ConcurrentHashMap<>();
    private static final class GeyserState {
        final boolean beingPushed;
        final long lastCheckTick;
        final int geyserCount;
        GeyserState(boolean beingPushed, long lastCheckTick, int geyserCount) {
            this.beingPushed = beingPushed;
            this.lastCheckTick = lastCheckTick;
            this.geyserCount = geyserCount;
        }
    }
    public boolean isBeingPushed(Player player) {
        if (player == null) return false;
        UUID uuid = player.getUniqueId();
        GeyserState state = playerStates.get(uuid);
        if (state == null) {
            state = new GeyserState(false, 0, 0);
            playerStates.put(uuid, state);
        }
        long currentTick = player.getWorld().getFullTime();
        if (currentTick - state.lastCheckTick < TICK_INTERVAL) {
            return state.beingPushed;
        }
        try {
            Material potentSulfur = Material.matchMaterial("POTENT_SULFUR");
            if (potentSulfur == null) {
                playerStates.put(uuid, new GeyserState(false, currentTick, 0));
                return false;
            }
        } catch (Throwable e) {
            playerStates.put(uuid, new GeyserState(false, currentTick, 0));
            return false;
        }
        Location loc = player.getLocation();
        int playerX = loc.getBlockX();
        int playerY = loc.getBlockY();
        int playerZ = loc.getBlockZ();
        int geyserCount = 0;
        boolean beingPushed = false;
        for (int dx = -SCAN_RANGE_XZ; dx <= SCAN_RANGE_XZ; dx++) {
            for (int dz = -SCAN_RANGE_XZ; dz <= SCAN_RANGE_XZ; dz++) {
                for (int dy = -SCAN_DEPTH; dy <= SCAN_HEIGHT; dy++) {
                    Block block = player.getWorld().getBlockAt(
                        playerX + dx,
                        playerY + dy,
                        playerZ + dz
                    );
                    if (isGeyserBlock(block)) {
                        geyserCount++;
                        beingPushed = true;
                        playerStates.put(uuid, new GeyserState(true, currentTick, geyserCount));
                        return true;
                    }
                }
            }
        }
        playerStates.put(uuid, new GeyserState(false, currentTick, 0));
        return false;
    }
    private boolean isGeyserBlock(Block block) {
        if (block == null || block.getType() == Material.AIR) return false;
        Material material = block.getType();
        String name = material.name();
        if (name.equals("POTENT_SULFUR")) {
            try {
                java.lang.reflect.Method getBlockData = block.getClass().getMethod("getBlockData");
                Object blockData = getBlockData.invoke(block);
                java.lang.reflect.Method getAsString = blockData.getClass().getMethod("getAsString");
                String blockDataStr = ((String) getAsString.invoke(blockData)).toUpperCase();
                if (blockDataStr.contains("ERUPTING") || blockDataStr.contains("CONTINUOUS")) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
    public void removePlayer(UUID uuid) {
        playerStates.remove(uuid);
    }
    public int getGeyserCount(Player player) {
        GeyserState state = playerStates.get(player.getUniqueId());
        return state != null ? state.geyserCount : 0;
    }
    public double getToleranceMultiplier(Player player) {
        return isBeingPushed(player) ? 1.5 : 1.0;
    }
}
