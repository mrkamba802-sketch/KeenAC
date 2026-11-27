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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
@CheckData(name = "Air Liquid Break", stableKey = "keenac.movement.airliquidbreak", decay = 0.02, setbackVl = 10)
public class AirLiquidBreakCheck extends Check implements PacketCheck {
    private static final int BUFFER_THRESHOLD = 3;
    @Override
    public void onPacketReceive(TrackedPlayer player, PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_DIGGING) return;
        WrapperPlayClientPlayerDigging wrapper = new WrapperPlayClientPlayerDigging(event);
        if (wrapper.getAction() != DiggingAction.START_DIGGING) return;
        if (!player.getPlayer().isOnline()) return;
        Location feetLoc = player.getPlayer().getLocation();
        Block feetBlock = feetLoc.getBlock();
        Material feetType = feetBlock.getType();
        boolean inLiquid = feetBlock.isLiquid();
        boolean inAir = !feetType.isSolid() && !inLiquid;
        boolean falling = player.getDeltaY() < -0.5;
        if (inLiquid || (inAir && falling)) {
            increaseBuffer(player, 1.0);
            if (getBuffer(player) > BUFFER_THRESHOLD) {
                flag(player);
                resetBuffer(player);
            }
        } else {
            decreaseBuffer(player, 0.5);
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
}
