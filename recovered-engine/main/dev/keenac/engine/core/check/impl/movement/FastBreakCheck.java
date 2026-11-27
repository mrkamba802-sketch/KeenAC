package dev.keenac.engine.core.check.impl.movement;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.player.TrackedPlayer;
import dev.keenac.engine.core.util.PacketClock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
@CheckData(name = "Fast Break A", stableKey = "keenac.movement.fastbreak", decay = 0.02, setbackVl = 20)
public class FastBreakCheck extends Check implements PacketCheck {
    private static final double MAX_FAST_BREAK_MULTIPLIER = 0.85;
    private static final int BUFFER_THRESHOLD = 3;
    private static final class PlayerState {
        long breakStartTime;
        boolean breaking;
        int blockX;
        int blockY;
        int blockZ;
        Material blockType;
        void reset() {
            breaking = false;
            breakStartTime = 0;
            blockType = null;
        }
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
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_DIGGING) return;
        WrapperPlayClientPlayerDigging wrapper = new WrapperPlayClientPlayerDigging(event);
        DiggingAction action = wrapper.getAction();
        PlayerState state = getState(player);
        if (action == DiggingAction.START_DIGGING) {
            state.breaking = true;
            state.breakStartTime = PacketClock.currentTimeMillis();
            state.blockX = wrapper.getBlockPosition().getX();
            state.blockY = wrapper.getBlockPosition().getY();
            state.blockZ = wrapper.getBlockPosition().getZ();
            try {
                state.blockType = player.getPlayer().getWorld()
                    .getBlockAt(state.blockX, state.blockY, state.blockZ).getType();
            } catch (Exception e) {
                state.blockType = Material.STONE;
            }
        } else if (action == DiggingAction.CANCELLED_DIGGING) {
            state.reset();
        } else if (action == DiggingAction.FINISHED_DIGGING) {
            if (!state.breaking || state.blockType == null) {
                state.reset();
                return;
            }
            long elapsed = PacketClock.currentTimeMillis() - state.breakStartTime;
            double vanillaTime = getVanillaBreakTime(state.blockType);
            double maxAllowed = vanillaTime * MAX_FAST_BREAK_MULTIPLIER * 1000.0;
            if (elapsed < maxAllowed && vanillaTime > 0) {
                increaseBuffer(player, 1.0);
                if (getBuffer(player) > BUFFER_THRESHOLD) {
                    flag(player);
                    resetBuffer(player);
                }
            } else {
                decreaseBuffer(player, 1.0);
            }
            state.reset();
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
    private double getVanillaBreakTime(Material material) {
        String name = material.name();
        if (name.equals("OBSIDIAN") || name.equals("END_PORTAL_FRAME")) return 50.0;
        if (name.equals("ENCHANTING_TABLE") || name.equals("ANVIL")) return 5.0;
        if (name.equals("IRON_ORE") || name.equals("DEEPSLATE_IRON_ORE")) return 3.0;
        if (name.equals("DIAMOND_ORE") || name.equals("DEEPSLATE_DIAMOND_ORE")) return 5.0;
        if (name.equals("EMERALD_ORE") || name.equals("DEEPSLATE_EMERALD_ORE")) return 5.0;
        if (name.equals("STONE") || name.equals("COBBLESTONE") || name.equals("DEEPSLATE")) return 1.5;
        if (name.equals("DIRT") || name.equals("GRASS_BLOCK") || name.equals("SAND")) return 0.5;
        if (name.equals("WOOD") || name.contains("PLANKS") || name.contains("_LOG")) return 2.0;
        if (material.isBlock() && material.isSolid()) return 1.0;
        return 0.5;
    }
}
