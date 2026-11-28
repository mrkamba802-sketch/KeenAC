package dev.keenac.listener;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import dev.keenac.check.AimCheck;
import org.bukkit.entity.Player;

/** PacketEvents entry point for Aim rotation samples and attack observations. */
public final class PacketAimListener extends PacketListenerAbstract {
    private final AimCheck check;

    public PacketAimListener(AimCheck check) {
        super(PacketListenerPriority.NORMAL);
        this.check = check;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            WrapperPlayClientPlayerFlying movement =
                    new WrapperPlayClientPlayerFlying(event);
            if (movement.hasRotationChanged()) {
                check.recordRotation(
                        player.getUniqueId(),
                        movement.getLocation().getYaw(),
                        movement.getLocation().getPitch());
            }
            return;
        }

        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
            return;
        }

        WrapperPlayClientInteractEntity interact =
                new WrapperPlayClientInteractEntity(event);
        if (interact.getAction()
                == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            check.recordAttack(player.getUniqueId(), interact.getEntityId());
        }
    }
}
