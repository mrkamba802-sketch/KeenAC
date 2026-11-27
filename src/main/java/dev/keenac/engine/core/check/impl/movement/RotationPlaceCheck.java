package dev.keenac.engine.core.check.impl.movement;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.player.TrackedPlayer;
@CheckData(name = "Rotation Place", stableKey = "keenac.movement.rotationplace", decay = 0.02, setbackVl = 10)
public class RotationPlaceCheck extends Check implements PacketCheck {
    private static final float MAX_YAW_DEVIATION = 45.0f;
    private static final float MAX_PITCH_DEVIATION = 45.0f;
    private static final int BUFFER_THRESHOLD = 5;
    @Override
    public void onPacketReceive(TrackedPlayer player, PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type != PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) return;
        WrapperPlayClientPlayerBlockPlacement wrapper = new WrapperPlayClientPlayerBlockPlacement(event);
        if (wrapper.getFace() == null) return;
        int blockX = wrapper.getBlockPosition().getX();
        int blockY = wrapper.getBlockPosition().getY();
        int blockZ = wrapper.getBlockPosition().getZ();
        double eyeX = player.getX();
        double eyeY = player.getY() + player.getHeight();
        double eyeZ = player.getZ();
        double dx = (blockX + 0.5) - eyeX;
        double dy = (blockY + 0.5) - eyeY;
        double dz = (blockZ + 0.5) - eyeZ;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float expectedYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float expectedPitch = (float) Math.toDegrees(Math.atan2(-dy, distXZ));
        float deltaYaw = Math.abs(player.getYaw() - expectedYaw);
        if (deltaYaw > 180) deltaYaw = 360 - deltaYaw;
        float deltaPitch = Math.abs(player.getPitch() - expectedPitch);
        if (deltaYaw > MAX_YAW_DEVIATION || deltaPitch > MAX_PITCH_DEVIATION) {
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
