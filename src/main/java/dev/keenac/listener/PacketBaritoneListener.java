package dev.keenac.listener;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import dev.keenac.check.BaritoneCheck;
import dev.keenac.check.BaritoneACheck;
import org.bukkit.entity.Player;

/** PacketEvents bridge for the Baritone movement-pattern review. */
public final class PacketBaritoneListener extends PacketListenerAbstract {
    private final BaritoneCheck check;
    private final BaritoneACheck checkA;

    public PacketBaritoneListener(BaritoneCheck check, BaritoneACheck checkA) {
        super(PacketListenerPriority.NORMAL);
        this.check = check;
        this.checkA = checkA;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || !WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            return;
        }

        WrapperPlayClientPlayerFlying movement = new WrapperPlayClientPlayerFlying(event);
        if (!movement.hasPositionChanged() && !movement.hasRotationChanged()) {
            return;
        }

        com.github.retrooper.packetevents.protocol.world.Location location =
                movement.getLocation();
        if (location == null) {
            return;
        }

        if (!movement.hasPositionChanged()) {
            float yaw = location.getYaw();
            float pitch = location.getPitch();
            check.recordRotation(player.getUniqueId(), yaw);
            checkA.recordRotation(player.getUniqueId(), yaw, pitch);
            return;
        }

        check.recordMovement(player.getUniqueId(), location.getX(), location.getZ(),
                location.getYaw(), movement.hasRotationChanged(), movement.isOnGround());
        checkA.recordMovement(player.getUniqueId(), location.getX(), location.getZ(),
                location.getYaw(), location.getPitch(),
                movement.hasRotationChanged(), movement.isOnGround());
    }
}