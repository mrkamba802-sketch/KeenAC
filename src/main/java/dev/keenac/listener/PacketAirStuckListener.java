package dev.keenac.listener;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import dev.keenac.KeenAC;
import dev.keenac.check.AirStuckCheck;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Reads incoming movement packets with PacketEvents.
 *
 * Packet callbacks may run on a Netty thread. Only primitive coordinates are
 * copied there; all Bukkit world/player access is deferred to the main thread.
 */
public final class PacketAirStuckListener extends PacketListenerAbstract {

    private final KeenAC plugin;
    private final AirStuckCheck check;

    public PacketAirStuckListener(KeenAC plugin, AirStuckCheck check) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
        this.check = check;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            return;
        }

        Object packetPlayer = event.getPlayer();
        if (!(packetPlayer instanceof Player player)) {
            return;
        }

        WrapperPlayClientPlayerFlying movement = new WrapperPlayClientPlayerFlying(event);
        if (!movement.hasPositionChanged()) {
            return;
        }

        com.github.retrooper.packetevents.protocol.world.Location packetLocation = movement.getLocation();
        if (packetLocation == null) {
            return;
        }

        double x = packetLocation.getX();
        double y = packetLocation.getY();
        double z = packetLocation.getZ();

        // Do not touch Bukkit state on the packet/Netty thread.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !player.isValid()) {
                return;
            }
            check.recordPacketPosition(
                    player, new Location(player.getWorld(), x, y, z));
        });
    }
}
