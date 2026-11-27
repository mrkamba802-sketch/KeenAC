package dev.keenac.engine.core.check.impl.combat;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.CompatFlag;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.physics.BoundingBox;
import dev.keenac.engine.core.physics.VersionPhysics;
import dev.keenac.engine.core.player.TrackedPlayer;
@CheckData(name = "Interact Cursor", stableKey = "keenac.combat.interactcursor", decay = 0.01, setbackVl = 15,
    compat = {CompatFlag.VIAVERSION_SENSITIVE, CompatFlag.RELAX_ON_MISMATCH}, relaxMultiplier = 1.5)
public class InteractCursorCheck extends Check implements PacketCheck {
    private static final double CURSOR_TOLERANCE = 0.3;
    private static final int MAX_CONSECUTIVE_MISSES = 4;
    private static final double BUFFER_THRESHOLD = 3.0;
    @Override
    public void onPacketReceive(TrackedPlayer player, PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type != PacketType.Play.Client.INTERACT_ENTITY) return;
        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
        if (wrapper.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) return;
        int targetId = wrapper.getEntityId();
        double[] pos = ReachCheck.getTrackedPosition(targetId);
        if (pos == null) {
            reward(player);
            return;
        }
        BoundingBox target = entityBox(pos, player.getProtocolVersion());
        double eyeX = player.getX();
        double eyeY = player.getY() + player.getEyeHeight();
        double eyeZ = player.getZ();
        float yaw = player.getYaw();
        float pitch = player.getPitch();
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);
        double dirX = -Math.sin(yawRad) * cosPitch;
        double dirY = -Math.sin(pitchRad);
        double dirZ = Math.cos(yawRad) * cosPitch;
        double range = VersionPhysics.getMaxReach(player.getProtocolVersion())
            + VersionPhysics.getSprintReachBonus(player.getProtocolVersion())
            + 1.0;
        boolean onTarget = target.intersectsRay(eyeX, eyeY, eyeZ, dirX, dirY, dirZ, range);
        if (onTarget) {
            reward(player);
            return;
        }
        increaseBuffer(player, 1.0);
        if (getBuffer(player) > BUFFER_THRESHOLD) {
            flag(player);
            resetBuffer(player);
        }
    }
    static BoundingBox entityBox(double[] pos, int protocol) {
        double halfWidth = VersionPhysics.getPlayerWidth(protocol) * 0.5;
        double height = VersionPhysics.getPlayerHeight(false, protocol);
        return new BoundingBox(
            pos[0] - halfWidth, pos[1], pos[2] - halfWidth,
            pos[0] + halfWidth, pos[1] + height, pos[2] + halfWidth
        ).expand(CURSOR_TOLERANCE);
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
}
