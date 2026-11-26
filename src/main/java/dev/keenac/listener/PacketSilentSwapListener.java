package dev.keenac.listener;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPunch;
import dev.keenac.check.SilentSwapCheck;
import org.bukkit.entity.Player;

/**
 * PacketEvents-only input for SilentSwap.
 *
 * Handles legacy and newer attack packet formats, plus swing packets. Inventory
 * click packets only invalidate a pending sequence; they can never raise VL.
 */
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
        PacketType.Play.Client type = (PacketType.Play.Client) event.getPacketType();

        if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            WrapperPlayClientHeldItemChange held =
                    new WrapperPlayClientHeldItemChange(event);
            check.recordSelectedSlot(playerId, held.getSlot());
            return;
        }

        // Inventory changes are not SilentSwap evidence. Discard any incomplete
        // slot/action candidate and do not count clicks or moved stacks.
        if (type == PacketType.Play.Client.CLICK_WINDOW
                || type == PacketType.Play.Client.CREATIVE_INVENTORY_ACTION) {
            check.recordInventoryMutation(playerId);
            return;
        }

        // Legacy swing packet (1.21.x through 26.2).
        if (type == PacketType.Play.Client.ANIMATION) {
            WrapperPlayClientAnimation animation = new WrapperPlayClientAnimation(event);
            if (animation.getHand() == InteractionHand.MAIN_HAND) {
                check.recordMainHandSwing(playerId);
            }
            return;
        }

        // Newer protocol replaces the swing packet with PUNCH.
        if (type == PacketType.Play.Client.PUNCH) {
            new WrapperPlayClientPunch(event);
            check.recordMainHandSwing(playerId);
            return;
        }

        // Newer protocol separates attacks from entity interactions (26.1+).
        if (type == PacketType.Play.Client.ATTACK) {
            new WrapperPlayClientAttack(event);
            check.recordAttack(playerId);
            return;
        }

        // Legacy protocol (1.21.x-26.0) carries ATTACK in INTERACT_ENTITY.
        if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity interact =
                    new WrapperPlayClientInteractEntity(event);
            if (interact.getAction()
                    == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                check.recordAttack(playerId);
            }
        }
    }
}
