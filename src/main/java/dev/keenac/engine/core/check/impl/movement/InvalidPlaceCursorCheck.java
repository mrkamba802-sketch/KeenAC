package dev.keenac.engine.core.check.impl.movement;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.CompatFlag;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.physics.BoundingBox;
import dev.keenac.engine.core.player.TrackedPlayer;
@CheckData(name = "Invalid Place Cursor", stableKey = "keenac.movement.invalidplacecursor", decay = 0.02, setbackVl = 10,
    compat = {CompatFlag.VIAVERSION_SENSITIVE, CompatFlag.RELAX_ON_MISMATCH}, relaxMultiplier = 1.4)
public class InvalidPlaceCursorCheck extends Check implements PacketCheck {
    private static final double BLOCK_TOLERANCE = 0.15;
    private static final double FACE_ANGLE_SLACK_DEGREES = 15.0;
    private static final double BUFFER_THRESHOLD = 4.0;
    private static final double RAY_LENGTH = 6.0;
    @Override
    public void onPacketReceive(TrackedPlayer player, PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) return;
        WrapperPlayClientPlayerBlockPlacement wrapper = new WrapperPlayClientPlayerBlockPlacement(event);
        if (wrapper.getFace() == null || wrapper.getFace() == BlockFace.OTHER) return;
        Vector3i place = wrapper.getBlockPosition();
        if (place == null) return;
        int[] normal = faceNormal(wrapper.getFace());
        int clickX = place.getX();
        int clickY = place.getY();
        int clickZ = place.getZ();
        double eyeX = player.getX();
        double eyeY = player.getY() + player.getEyeHeight();
        double eyeZ = player.getZ();
        double yawRad = Math.toRadians(player.getYaw());
        double pitchRad = Math.toRadians(player.getPitch());
        double cosPitch = Math.cos(pitchRad);
        double dirX = -Math.sin(yawRad) * cosPitch;
        double dirY = -Math.sin(pitchRad);
        double dirZ = Math.cos(yawRad) * cosPitch;
        BoundingBox blockBox = new BoundingBox(clickX, clickY, clickZ, clickX + 1.0, clickY + 1.0, clickZ + 1.0)
            .expand(BLOCK_TOLERANCE);
        boolean onBlock = blockBox.intersectsRay(eyeX, eyeY, eyeZ, dirX, dirY, dirZ, RAY_LENGTH);
        Vector3f cursor = wrapper.getCursorPosition();
        if (!onBlock && cursor != null) {
            double hitX = clickX + cursor.getX();
            double hitY = clickY + cursor.getY();
            double hitZ = clickZ + cursor.getZ();
            double vx = hitX - eyeX;
            double vy = hitY - eyeY;
            double vz = hitZ - eyeZ;
            double along = vx * dirX + vy * dirY + vz * dirZ;
            if (along >= 0.0 && along <= RAY_LENGTH) {
                double perpX = vx - along * dirX;
                double perpY = vy - along * dirY;
                double perpZ = vz - along * dirZ;
                double perpSq = perpX * perpX + perpY * perpY + perpZ * perpZ;
                onBlock = perpSq <= BLOCK_TOLERANCE * BLOCK_TOLERANCE;
            }
        }
        if (!onBlock) {
            increaseBuffer(player, 1.0);
            if (getBuffer(player) > BUFFER_THRESHOLD) {
                flag(player);
                resetBuffer(player);
            }
            return;
        }
        double dot = dirX * normal[0] + dirY * normal[1] + dirZ * normal[2];
        double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
        if (angle < 90.0 - FACE_ANGLE_SLACK_DEGREES) {
            increaseBuffer(player, 1.0);
            if (getBuffer(player) > BUFFER_THRESHOLD) {
                flag(player);
                resetBuffer(player);
            }
            return;
        }
        reward(player);
    }
    static int[] faceNormal(BlockFace face) {
        if (face == null) return new int[]{0, 1, 0};
        switch (face) {
            case NORTH:
                return new int[]{0, 0, -1};
            case SOUTH:
                return new int[]{0, 0, 1};
            case EAST:
                return new int[]{1, 0, 0};
            case WEST:
                return new int[]{-1, 0, 0};
            case DOWN:
                return new int[]{0, -1, 0};
            case UP:
            default:
                return new int[]{0, 1, 0};
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
}
