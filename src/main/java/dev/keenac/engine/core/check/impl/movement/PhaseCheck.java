package dev.keenac.engine.core.check.impl.movement;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import dev.keenac.KeenAC;
import dev.keenac.engine.compat.world.GhostBlockResolver;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.CompatFlag;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.compensation.SimulationEngine;
import dev.keenac.engine.core.physics.PredictionContext;
import dev.keenac.engine.core.physics.PredictionEngine;
import dev.keenac.engine.core.platform.FoliaCompat;
import dev.keenac.engine.core.player.TrackedPlayer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@CheckData(name = "Phase A", stableKey = "keenac.movement.phase", decay = 0.01, setbackVl = 20, compat = {CompatFlag.RELAX_ON_MISMATCH}, relaxMultiplier = 1.4)
public class PhaseCheck extends Check implements PacketCheck {
    private static final double MAX_BLOCK_CLIP = 0.1;
    private static final double MAX_VELOCITY_INSIDE_BLOCK = 0.01;
    private static final int MIN_CLIPPING_TICKS = 3;
    private static final class PlayerState {
        int clippingTicks;
        int ghostGraceTicks;
    }
    private final GhostBlockResolver ghostBlocks = new GhostBlockResolver(false, 2);
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
        if (!PredictionEngine.isMovementPacket(event)) return;
        PlayerState state = getState(player);
        PredictionContext ctx = new PredictionContext(player);
        try {
            if (state.ghostGraceTicks > 0) {
                state.ghostGraceTicks--;
                decreaseBuffer(player, 0.1);
                return;
            }
            if (ghostBlocks.shouldResync(ghostWorldView(player),
                    (int) Math.floor(ctx.x), (int) Math.floor(ctx.y), (int) Math.floor(ctx.z))) {
                state.ghostGraceTicks = 10;
                decreaseBuffer(player, 0.1);
                return;
            }
            org.bukkit.Location loc = new org.bukkit.Location(player.getPlayer().getWorld(), ctx.x, ctx.y, ctx.z);
            org.bukkit.block.Block feetBlock = loc.getBlock();
            org.bukkit.block.Block headBlock = loc.clone().add(0, 1.6, 0).getBlock();
            boolean feetInside = feetBlock.getType().isSolid();
            boolean headInside = headBlock.getType().isSolid();
            if (feetInside || headInside) {
                SimulationEngine simEngine = KeenAC.getInstance().getSimulationEngine();
                if (simEngine != null && simEngine.needsSimulation(player)) {
                    SimulationEngine.SimulationResult result = simEngine.simulate(
                        player, ctx.x, ctx.y, ctx.z);
                    if (result.matches) {
                        state.clippingTicks = Math.max(0, state.clippingTicks - 1);
                        decreaseBuffer(player, 0.3);
                        return;
                    }
                }
                state.clippingTicks++;
                if (state.clippingTicks >= MIN_CLIPPING_TICKS) {
                    if (ctx.horizontalSpeed > MAX_VELOCITY_INSIDE_BLOCK
                            || Math.abs(ctx.deltaY) > MAX_VELOCITY_INSIDE_BLOCK) {
                        increaseBuffer(player, 1.5);
                        if (getBuffer(player) > 3.0) {
                            flag(player);
                            resetBuffer(player);
                            state.clippingTicks = 0;
                        }
                    }
                }
            } else {
                state.clippingTicks = Math.max(0, state.clippingTicks - 1);
                decreaseBuffer(player, 0.2);
            }
        } catch (Exception e) {
            KeenAC.getInstance().getLogger().fine("PhaseCheck: chunk-load or world-access exception — " + e.getMessage());
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
    private GhostBlockResolver.WorldView ghostWorldView(TrackedPlayer player) {
        org.bukkit.World world = player.getPlayer().getWorld();
        FoliaCompat folia = KeenAC.getInstance().getFoliaCompat();
        boolean safeThread = !folia.isFolia() || folia.isOwnedByCurrentRegion(player.getPlayer());
        return new GhostBlockResolver.WorldView() {
            @Override
            public boolean isChunkLoaded(int chunkX, int chunkZ) {
                if (!safeThread || world == null) return false;
                return world.isChunkLoaded(chunkX, chunkZ);
            }
            @Override
            public boolean isAir(int x, int y, int z) {
                if (!safeThread || world == null) return false;
                return world.getBlockAt(x, y, z).getType() == org.bukkit.Material.AIR;
            }
        };
    }
}
