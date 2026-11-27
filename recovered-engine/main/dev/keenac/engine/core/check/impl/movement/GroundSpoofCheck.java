package dev.keenac.engine.core.check.impl.movement;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import dev.keenac.KeenAC;
import dev.keenac.engine.compat.world.GhostBlockResolver;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.CompatFlag;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.physics.PredictionContext;
import dev.keenac.engine.core.physics.PredictionEngine;
import dev.keenac.engine.core.platform.FoliaCompat;
import dev.keenac.engine.core.player.TrackedPlayer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@CheckData(name = "Ground Spoof A", stableKey = "keenac.movement.groundspoof", decay = 0.01, setbackVl = 20, compat = {CompatFlag.PAPER_CHUNK_DEPENDENT, CompatFlag.RELAX_ON_MISMATCH}, relaxMultiplier = 1.3)
public class GroundSpoofCheck extends Check implements PacketCheck {
    private static final double MIN_AIR_TIME_FOR_GROUND = 3.0;
    private static final int TICKS_PER_SECOND = 20;
    private static final int MIN_FALSE_GROUND_FLAGS = 5;
    private static final double FALLING_VELOCITY_THRESHOLD = 0.3;
    private static final double MIN_FALL_DISTANCE = 2.0;
    private static final class PlayerState {
        int falseGroundCount;
        int airTicks;
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
        boolean claimsGround = ctx.onGround;
        double deltaY = ctx.deltaY;
        boolean isFalling = deltaY < -FALLING_VELOCITY_THRESHOLD;
        double fallDistance = ctx.lastY - ctx.y;
        if (!claimsGround) {
            state.airTicks++;
            return;
        }
        if (state.ghostGraceTicks > 0) {
            state.ghostGraceTicks--;
            decreaseBuffer(player, 0.1);
            return;
        }
        if (ghostBlocks.shouldResync(ghostWorldView(player),
                (int) Math.floor(ctx.x), (int) Math.floor(ctx.y - 0.5D), (int) Math.floor(ctx.z))) {
            state.ghostGraceTicks = 10;
            decreaseBuffer(player, 0.1);
            return;
        }
        if (isFalling && fallDistance > MIN_FALL_DISTANCE) {
            state.falseGroundCount++;
            if (state.falseGroundCount >= MIN_FALSE_GROUND_FLAGS) {
                flag(player);
                resetBuffer(player);
                state.falseGroundCount = 0;
            }
            return;
        }
        if (state.airTicks > MIN_AIR_TIME_FOR_GROUND * TICKS_PER_SECOND) {
            if (Math.abs(deltaY) > 0.5) {
                increaseBuffer(player, 1.0);
                if (getBuffer(player) > 3.0) {
                    flag(player);
                    resetBuffer(player);
                }
            }
        }
        state.airTicks = 0;
        state.falseGroundCount = Math.max(0, state.falseGroundCount - 1);
        decreaseBuffer(player, 0.1);
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
