package dev.keenac.listener;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import dev.keenac.check.AirStuckCheck;
import dev.keenac.check.MotionCheck;
import org.bukkit.entity.Player;

public final class PacketAirStuckListener extends PacketListenerAbstract {
    private final AirStuckCheck airStuckCheck;
    private final MotionCheck motionCheck;

    public PacketAirStuckListener(AirStuckCheck airStuckCheck, MotionCheck motionCheck) {
        super(PacketListenerPriority.NORMAL);
        this.airStuckCheck = airStuckCheck;
        this.motionCheck = motionCheck;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            WrapperPlayClientPlayerFlying movement = new WrapperPlayClientPlayerFlying(event);
            var playerId = player.getUniqueId();
            com.github.retrooper.packetevents.protocol.world.Location packetLocation =
                    movement.getLocation();
            boolean hasPosition = movement.hasPositionChanged() && packetLocation != null;
            if (!hasPosition) {
                return;
            }

            double x = packetLocation.getX();
            double y = packetLocation.getY();
            double z = packetLocation.getZ();
            // These methods store immutable values only; no Bukkit API is touched
            // from PacketEvents' network thread.
            airStuckCheck.recordPacketPosition(playerId, x, y, z);
            motionCheck.recordElytraPacketPosition(playerId, x, y, z);
            motionCheck.recordPacketMovement(playerId, x, y, z, movement.isOnGround());
            return;
        }

        // Block breaking is initiated by serverbound digging packets. Reject
        // the start/finish packet itself once AirStuck has been confirmed.
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            if (airStuckCheck.shouldCancelBlockActions()
                    && airStuckCheck.isFlagged(player.getUniqueId())) {
                WrapperPlayClientPlayerDigging digging =
                        new WrapperPlayClientPlayerDigging(event);
                DiggingAction action = digging.getAction();
                if (action == DiggingAction.START_DIGGING
                        || action == DiggingAction.FINISHED_DIGGING
                        || action == DiggingAction.CANCELLED_DIGGING) {
                    event.setCancelled(true);
                }
            }
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity interact =
                    new WrapperPlayClientInteractEntity(event);
            if (interact.getAction()
                    == WrapperPlayClientInteractEntity.InteractAction.ATTACK
                    && airStuckCheck.isFlagged(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }
}
