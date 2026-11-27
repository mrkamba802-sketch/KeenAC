package dev.keenac.engine.core.check.impl.movement;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import dev.keenac.KeenAC;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.player.TrackedPlayer;
import dev.keenac.engine.core.util.PacketClock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
@CheckData(name = "Invalid Place A", stableKey = "keenac.movement.invalidplace", decay = 0.02, setbackVl = 10)
public class InvalidPlaceCheck extends Check implements PacketCheck {
    private static final int MAX_PLACEMENTS_PER_TICK = 4;
    private static final class PlayerState {
        int placementsThisTick;
        long lastTick;
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
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) return;
        PlayerState state = getState(player);
        long now = PacketClock.currentTimeMillis();
        if (now - state.lastTick > 50) {
            state.placementsThisTick = 0;
            state.lastTick = now;
        }
        state.placementsThisTick++;
        if (state.placementsThisTick > MAX_PLACEMENTS_PER_TICK) {
            increaseBuffer(player, 1.0);
            if (getBuffer(player) > 5.0) {
                flag(player);
                resetBuffer(player);
            }
            return;
        }
        WrapperPlayClientPlayerBlockPlacement wrapper = new WrapperPlayClientPlayerBlockPlacement(event);
        var position = wrapper.getBlockPosition();
        var face = wrapper.getFace();
        if (position == null || face == null || face == com.github.retrooper.packetevents.protocol.world.BlockFace.OTHER) {
            return;
        }
        int[] normal = InvalidPlaceCursorCheck.faceNormal(face);
        int bx = position.getX() + normal[0];
        int by = position.getY() + normal[1];
        int bz = position.getZ() + normal[2];
        try {
            Material type = player.getPlayer().getWorld().getBlockAt(bx, by, bz).getType();
            if (type.isOccluding()) {
                flagDetail(player, "placing in occupied block " + type.name());
                return;
            }
            double px = player.getX();
            double py = player.getY();
            double pz = player.getZ();
            if (px >= bx && px < bx + 1 && pz >= bz && pz < bz + 1
                    && py + player.getHeight() > by && py < by + 1) {
                flagDetail(player, "placing block inside player");
            }
        } catch (Exception e) {
            KeenAC.getInstance().getLogger().fine("InvalidPlaceCheck: world-access exception — " + e.getMessage());
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
    private void flagDetail(TrackedPlayer player, String detail) {
        flag(player);
        var logger = dev.keenac.KeenAC.getInstance().getLogger();
        logger.warning("[Invalid Place A] " + player.getName() + ": " + detail);
    }
}
