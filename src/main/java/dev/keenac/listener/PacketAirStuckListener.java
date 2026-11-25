package dev.keenac.listener;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import dev.keenac.KeenAC;
import dev.keenac.check.AirStuckCheck;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

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
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            WrapperPlayClientPlayerFlying movement = new WrapperPlayClientPlayerFlying(event);
            if (!movement.hasPositionChanged()) {
                return;
            }

            com.github.retrooper.packetevents.protocol.world.Location packetLocation =
                    movement.getLocation();
            if (packetLocation == null) {
                return;
            }

            double x = packetLocation.getX();
            double y = packetLocation.getY();
            double z = packetLocation.getZ();

            // Bukkit world/player state is accessed only on the main thread.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && player.isValid()) {
                    check.recordPacketPosition(
                            player, new Location(player.getWorld(), x, y, z));
                }
            });
            return;
        }

        // Prevent melee damage at the packet boundary while AirStuck is confirmed.
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity interact =
                    new WrapperPlayClientInteractEntity(event);
            if (interact.getAction()
                    == WrapperPlayClientInteractEntity.InteractAction.ATTACK
                    && check.isFlagged(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }
}
