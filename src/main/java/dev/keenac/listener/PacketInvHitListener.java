package dev.keenac.listener;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import dev.keenac.KeenAC;
import dev.keenac.check.InvHitCheck;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Observes personal-inventory clicks and attack packets. Click state is recorded
 * immediately so a following attack cannot overtake a queued Bukkit task.
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
        Object packetPlayer = event.getPlayer();
        if (!(packetPlayer instanceof Player player)
                || !plugin.getConfig().getBoolean("checks.inv-hit.enabled", true)) {
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.CLICK_WINDOW) {
            WrapperPlayClientClickWindow click = new WrapperPlayClientClickWindow(event);
            if (click.getWindowId() == 0) {
                check.recordPersonalInventoryPacket(player.getUniqueId());
            }
            return;
        }

        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
            return;
        }

        WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
        if (interact.getAction()
                != WrapperPlayClientInteractEntity.InteractAction.ATTACK
                || !check.isInventoryOpen(player.getUniqueId())) {
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
