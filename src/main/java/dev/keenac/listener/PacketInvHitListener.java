package dev.keenac.listener;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import dev.keenac.KeenAC;
import dev.keenac.check.InvHitCheck;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Inspects attack packets before the server applies entity damage.
 */
public final class PacketInvHitListener extends PacketListenerAbstract {
    private final KeenAC plugin;
    private final InvHitCheck check;

    public PacketInvHitListener(KeenAC plugin, InvHitCheck check) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
        this.check = check;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY
                || !plugin.getConfig().getBoolean("checks.inv-hit.enabled", true)) {
            return;
        }

        Object packetPlayer = event.getPlayer();
        if (!(packetPlayer instanceof Player player)) {
            return;
        }

        WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
        if (interact.getAction()
                != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            return;
        }

        // The state is maintained by Bukkit inventory events and uses concurrent
        // collections, so it is safe to read from PacketEvents' network callback.
        if (!check.isInventoryOpen(player.getUniqueId())) {
            return;
        }

        if (check.shouldCancelHits()) {
            event.setCancelled(true);
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.isValid()) {
                check.reportViolation(player);
            }
        });
    }
}
