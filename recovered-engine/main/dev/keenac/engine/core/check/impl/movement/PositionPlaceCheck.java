package dev.keenac.engine.core.check.impl.movement;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.player.TrackedPlayer;
@CheckData(name = "Position Place", stableKey = "keenac.movement.positionplace", decay = 0.01, setbackVl = 15)
public class PositionPlaceCheck extends Check implements PacketCheck {
    private static final double MAX_REACH_SQ = 25.0;
    private static final double TOLERANCE = 0.5;
    private static final int BUFFER_THRESHOLD = 3;
    @Override
    public void onPacketReceive(TrackedPlayer player, PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) return;
        WrapperPlayClientPlayerBlockPlacement wrapper = new WrapperPlayClientPlayerBlockPlacement(event);
        if (wrapper.getFace() == null) return;
        int blockX = wrapper.getBlockPosition().getX();
        int blockY = wrapper.getBlockPosition().getY();
        int blockZ = wrapper.getBlockPosition().getZ();
        double centerX = blockX + 0.5;
        double centerY = blockY + 0.5;
        double centerZ = blockZ + 0.5;
        double eyeY = player.getY() + player.getHeight();
        double dx = player.getX() - centerX;
        double dy = eyeY - centerY;
        double dz = player.getZ() - centerZ;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq > MAX_REACH_SQ + TOLERANCE) {
            increaseBuffer(player, 1.0);
            if (getBuffer(player) > BUFFER_THRESHOLD) {
                flag(player);
                resetBuffer(player);
            }
        } else if (distSq > MAX_REACH_SQ) {
            increaseBuffer(player, 0.3);
            if (getBuffer(player) > 5.0) {
                flag(player);
                resetBuffer(player);
            }
        } else {
            decreaseBuffer(player, 0.1);
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
}
