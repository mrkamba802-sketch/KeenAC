package dev.keenac.listener;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import dev.keenac.check.SilentSwapCheck;
import org.bukkit.entity.Player;

/** PacketEvents bridge for SilentSwap's action-bound hotbar switch analysis. */
public final class PacketSilentSwapListener extends PacketListenerAbstract {
    private final SilentSwapCheck check;

    public PacketSilentSwapListener(SilentSwapCheck check) {
        super(PacketListenerPriority.NORMAL);
        this.check = check;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        var playerId = player.getUniqueId();
        if (event.getPacketType() == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            WrapperPlayClientHeldItemChange held = new WrapperPlayClientHeldItemChange(event);
            check.recordSelectedSlot(playerId, held.getSlot());
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
            if (interact.getAction()
                    == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                check.recordMainHandAction(playerId);
            }
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.USE_ITEM) {
            WrapperPlayClientUseItem use = new WrapperPlayClientUseItem(event);
            if (use.getHand() == InteractionHand.MAIN_HAND) {
                check.recordMainHandAction(playerId);
            }
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            WrapperPlayClientPlayerBlockPlacement place =
                    new WrapperPlayClientPlayerBlockPlacement(event);
            if (place.getHand() == InteractionHand.MAIN_HAND) {
                check.recordMainHandAction(playerId);
            }
        }
    }
}
