package dev.keenac.engine.core.check.impl.movement;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.CompatFlag;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.player.TrackedPlayer;
import dev.keenac.engine.core.util.MaterialUtils;
import org.bukkit.World;
@CheckData(name = "Scaffold Support", stableKey = "keenac.movement.scaffoldsupport", decay = 0.02, setbackVl = 10,
    compat = {CompatFlag.RELAX_ON_MISMATCH}, relaxMultiplier = 1.3)
public class ScaffoldSupportCheck extends Check implements PacketCheck {
    private static final double BUFFER_THRESHOLD = 4.0;
    private static final int[][] NEIGHBOUR_OFFSETS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
    @Override
    public void onPacketReceive(TrackedPlayer player, PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) return;
        WrapperPlayClientPlayerBlockPlacement wrapper = new WrapperPlayClientPlayerBlockPlacement(event);
        if (wrapper.getFace() == null || wrapper.getFace() == BlockFace.OTHER) return;
        Vector3i clicked = wrapper.getBlockPosition();
        if (clicked == null) return;
        int[] normal = InvalidPlaceCursorCheck.faceNormal(wrapper.getFace());
        int tx = clicked.getX() + normal[0];
        int ty = clicked.getY() + normal[1];
        int tz = clicked.getZ() + normal[2];
        try {
            World world = player.getPlayer().getWorld();
            if (!world.isChunkLoaded(tx >> 4, tz >> 4)) {
                reward(player);
                return;
            }
            boolean supported = false;
            for (int[] offset : NEIGHBOUR_OFFSETS) {
                if (!MaterialUtils.isAirLike(
                        world.getBlockAt(tx + offset[0], ty + offset[1], tz + offset[2]).getType())) {
                    supported = true;
                    break;
                }
            }
            if (supported) {
                reward(player);
                return;
            }
            increaseBuffer(player, 1.0);
            if (getBuffer(player) > BUFFER_THRESHOLD) {
                flag(player);
                resetBuffer(player);
            }
        } catch (Exception e) {
            reward(player);
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
}
