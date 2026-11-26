package dev.keenac.listener;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import dev.keenac.check.GhostMenuCheck;
import org.bukkit.entity.Player;

/** Forwards decoded client movement coordinates to GhostMenuCheck. */
public final class PacketGhostMenuListener extends PacketListenerAbstract {
    private final GhostMenuCheck check;

    public PacketGhostMenuListener(GhostMenuCheck check) {
        super(PacketListenerPriority.NORMAL);
        this.check = check;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || !WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            return;
        }

        WrapperPlayClientPlayerFlying movement = new WrapperPlayClientPlayerFlying(event);
        if (!movement.hasPositionChanged()) {
            return;
        }

        var location = movement.getLocation();
        if (location != null) {
            check.recordPacketPosition(
                    player.getUniqueId(), location.getX(), location.getZ());
        }
    }
}
