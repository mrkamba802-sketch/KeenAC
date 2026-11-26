package dev.keenac.listener;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import dev.keenac.check.InventoryAutoSwapCheck;
import org.bukkit.entity.Player;

/** Packet-only AutoSwap detection. */
public final class PacketAutoSwapListener extends PacketListenerAbstract {
    private final InventoryAutoSwapCheck check;

    public PacketAutoSwapListener(InventoryAutoSwapCheck check) {
        super(PacketListenerPriority.NORMAL);
        this.check = check;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            WrapperPlayClientHeldItemChange held =
                    new WrapperPlayClientHeldItemChange(event);
            check.recordSelectedSlot(player.getUniqueId(), held.getSlot());
            return;
        }

        if (event.getPacketType() != PacketType.Play.Client.CLICK_WINDOW) {
            return;
        }

        WrapperPlayClientClickWindow click = new WrapperPlayClientClickWindow(event);
        if (click.getWindowClickType()
                != WrapperPlayClientClickWindow.WindowClickType.SWAP) {
            return;
        }

        check.recordSwap(player.getUniqueId(), click.getWindowId(),
                click.getSlot(), click.getButton());
    }
}
