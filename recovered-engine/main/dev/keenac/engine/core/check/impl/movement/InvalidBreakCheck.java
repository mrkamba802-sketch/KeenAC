package dev.keenac.engine.core.check.impl.movement;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import dev.keenac.KeenAC;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.player.TrackedPlayer;
import org.bukkit.Material;
@CheckData(name = "Invalid Break A", stableKey = "keenac.movement.invalidbreak", decay = 0.02, setbackVl = 10)
public class InvalidBreakCheck extends Check implements PacketCheck {
    @Override
    public void onPacketReceive(TrackedPlayer player, PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_DIGGING) return;
        WrapperPlayClientPlayerDigging wrapper = new WrapperPlayClientPlayerDigging(event);
        if (wrapper.getAction() != DiggingAction.START_DIGGING) return;
        int x = wrapper.getBlockPosition().getX();
        int y = wrapper.getBlockPosition().getY();
        int z = wrapper.getBlockPosition().getZ();
        try {
            Material type = player.getPlayer().getWorld().getBlockAt(x, y, z).getType();
            if (type == Material.AIR) {
                flagDetail(player, "breaking air at " + x + "," + y + "," + z);
                return;
            }
            String name = type.name();
            if (name.equals("BEDROCK") || name.equals("BARRIER") || name.equals("END_PORTAL")
                    || name.equals("END_PORTAL_FRAME") || name.equals("COMMAND_BLOCK")
                    || name.equals("STRUCTURE_BLOCK") || name.equals("JIGSAW_BLOCK")) {
                flagDetail(player, "breaking protected block " + name);
                return;
            }
        } catch (Exception e) {
            KeenAC.getInstance().getLogger().fine("InvalidBreakCheck: world-access exception — " + e.getMessage());
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
    private void flagDetail(TrackedPlayer player, String detail) {
        flag(player);
        var logger = dev.keenac.KeenAC.getInstance().getLogger();
        logger.warning("[Invalid Break A] " + player.getName() + ": " + detail);
    }
}
