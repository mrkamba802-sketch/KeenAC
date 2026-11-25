package dev.keenac.listener;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow.WindowClickType;
import dev.keenac.KeenAC;
import dev.keenac.check.InventoryAutoSwapCheck;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Observes raw client inventory-click packets before Bukkit inventory events
 * are processed. Bukkit access is deferred to the main thread.
 */
public final class PacketAutoSwapListener extends PacketListenerAbstract {
    private final KeenAC plugin;
    private final InventoryAutoSwapCheck check;

    public PacketAutoSwapListener(KeenAC plugin, InventoryAutoSwapCheck check) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
        this.check = check;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType()
                != com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client.CLICK_WINDOW) {
            return;
        }

        Object packetPlayer = event.getPlayer();
        if (!(packetPlayer instanceof Player player)) {
            return;
        }

        WrapperPlayClientClickWindow click = new WrapperPlayClientClickWindow(event);
        WindowClickType clickType = click.getWindowClickType();

        // SWAP is the direct hotbar/slot exchange operation. QUICK_MOVE and
        // PICKUP sequences are also counted at lower weight because automated
        // inventory rearrangement can be represented by those normal actions.
        int score = switch (clickType) {
            case SWAP -> 3;
            case QUICK_MOVE -> 2;
            case PICKUP, PICKUP_ALL -> 1;
            default -> 0;
        };
        if (score == 0) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.isValid()) {
                check.recordPacketOperation(player, score);
            }
        });
    }
}
