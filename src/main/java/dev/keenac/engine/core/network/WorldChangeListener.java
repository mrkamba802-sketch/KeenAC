package dev.keenac.engine.core.network;
import dev.keenac.KeenAC;
import dev.keenac.engine.core.compensation.LatencyCompensator;
import dev.keenac.engine.core.compensation.PingPongManager;
import dev.keenac.engine.core.player.PlayerManager;
import dev.keenac.engine.core.player.TrackedPlayer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import java.util.UUID;
public final class WorldChangeListener implements Listener {
    private static final double OBSERVE_RADIUS_SQUARED = 64.0 * 64.0;
    private final KeenAC plugin;
    public WorldChangeListener(KeenAC plugin) {
        this.plugin = plugin;
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        recordBlock(event.getBlock().getLocation(), Material.AIR);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Location location = event.getBlock().getLocation();
        recordBlock(location, location.getBlock().getType());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        recordBlock(event.getBlock().getLocation(), Material.AIR);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        recordBlock(event.getBlock().getLocation(), event.getNewState().getType());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        recordBlock(event.getBlock().getLocation(), event.getNewState().getType());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        recordBlock(event.getBlock().getLocation(), event.getNewState().getType());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFlow(BlockFromToEvent event) {
        recordBlock(event.getToBlock().getLocation(), event.getBlock().getType());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        recordBlock(event.getBlock().getLocation(), event.getTo());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block block : event.blockList()) recordBlock(block.getLocation(), Material.AIR);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block block : event.blockList()) recordBlock(block.getLocation(), Material.AIR);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        recordPistonMove(event.getBlocks(), event.getDirection());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        recordPistonMove(event.getBlocks(), event.getDirection().getOppositeFace());
    }
    private void recordPistonMove(java.util.List<Block> blocks, BlockFace direction) {
        for (Block block : blocks) {
            Location from = block.getLocation();
            Material moved = block.getType();
            Location to = from.clone().add(
                    direction.getModX(), direction.getModY(), direction.getModZ());
            recordBlock(from, Material.AIR);
            recordBlock(to, moved);
        }
    }
    private void recordBlock(Location location, Material material) {
        broadcast(location, (compensator, uuid, tick) ->
                compensator.onBlockChange(uuid,
                        location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                        material, tick, location.getWorld()));
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        PlayerManager playerManager = plugin.getPlayerManager();
        LatencyCompensator compensator = plugin.getLatencyCompensator();
        if (playerManager == null || compensator == null) return;
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        for (Player online : event.getWorld().getPlayers()) {
            if (online.getLocation().getChunk().getX() != chunkX
                    || online.getLocation().getChunk().getZ() != chunkZ) continue;
            if (playerManager.get(online.getUniqueId()) != null) {
                compensator.onChunkUnload(online.getUniqueId(), chunkX, chunkZ);
            }
        }
    }
    private void broadcast(Location location, PerPlayerAction action) {
        PlayerManager playerManager = plugin.getPlayerManager();
        LatencyCompensator compensator = plugin.getLatencyCompensator();
        PingPongManager pingPong = plugin.getPingPongManager();
        World world = location.getWorld();
        if (playerManager == null || compensator == null || pingPong == null || world == null) return;
        for (Player online : world.getPlayers()) {
            UUID uuid = online.getUniqueId();
            TrackedPlayer wp = playerManager.get(uuid);
            if (wp == null || !wp.isValid()) continue;
            if (online.getLocation().distanceSquared(location) > OBSERVE_RADIUS_SQUARED) continue;
            action.apply(compensator, uuid, pingPong.getCurrentTick(wp));
        }
    }
    private interface PerPlayerAction {
        void apply(LatencyCompensator compensator, UUID uuid, int tick);
    }
}
