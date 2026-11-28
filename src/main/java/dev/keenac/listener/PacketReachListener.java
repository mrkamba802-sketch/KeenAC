package dev.keenac.listener;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import dev.keenac.check.ReachCheck;
import org.bukkit.entity.Player;

/** PacketEvents entry point for Reach movement snapshots and attack packets. */
public final class PacketReachListener extends PacketListenerAbstract {
    private final ReachCheck check;

    public PacketReachListener(ReachCheck check) {
        super(PacketListenerPriority.NORMAL);
        this.check = check;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            WrapperPlayClientPlayerFlying movement = new WrapperPlayClientPlayerFlying(event);
            if (movement.hasPositionChanged()) {
                com.github.retrooper.packetevents.protocol.world.Location location =
                        movement.getLocation();
                if (location != null) {
                    check.recordPacketPosition(player.getUniqueId(),
                            location.getX(), location.getY(), location.getZ());
                }
            }
            return;
        }

        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) return;
        WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
        if (interact.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK
                && check.handleAttack(player.getUniqueId(), interact.getEntityId())) {
            event.setCancelled(true);
        }
    }
}
