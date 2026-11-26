package dev.keenac.listener;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import dev.keenac.KeenAC;
import dev.keenac.check.NoSlowCheck;
import dev.keenac.check.NoWebCheck;
import dev.keenac.check.ScaffoldCheck;
import dev.keenac.check.WallHitCheck;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Central PacketEvents bridge for client movement, item-use, block-placement
 * and attack input. Bukkit world/entity reads are deferred to the primary
 * thread; packet callbacks only decode and queue primitive values.
 */
public final class PacketGameplayListener extends PacketListenerAbstract {
    private final KeenAC plugin;
    private final NoSlowCheck noSlow;
    private final NoWebCheck noWeb;
    private final ScaffoldCheck scaffold;
    private final WallHitCheck wallHit;

    public PacketGameplayListener(KeenAC plugin, NoSlowCheck noSlow, NoWebCheck noWeb,
                                  ScaffoldCheck scaffold, WallHitCheck wallHit) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
        this.noSlow = noSlow;
        this.noWeb = noWeb;
        this.scaffold = scaffold;
        this.wallHit = wallHit;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        var playerId = player.getUniqueId();
        var type = event.getPacketType();

        if (WrapperPlayClientPlayerFlying.isFlying(type)) {
            WrapperPlayClientPlayerFlying movement = new WrapperPlayClientPlayerFlying(event);
            if (movement.hasPositionChanged()) {
                com.github.retrooper.packetevents.protocol.world.Location location =
                        movement.getLocation();
                if (location != null) {
                    noWeb.recordPacketPosition(playerId, location.getX(), location.getY(),
                            location.getZ(), location.getYaw(), location.getPitch());
                    noSlow.recordPacketMovement(playerId, location.getX(), location.getY(),
                            location.getZ());
                }
            }
            return;
        }

        if (type == PacketType.Play.Client.USE_ITEM) {
            new WrapperPlayClientUseItem(event);
            noSlow.recordUseIntent(playerId);
            return;
        }

        if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            WrapperPlayClientPlayerBlockPlacement placement =
                    new WrapperPlayClientPlayerBlockPlacement(event);
            noSlow.recordUseIntent(playerId);

            var position = placement.getBlockPosition();
            var face = placement.getFace();
            if (position != null && face != null) {
                int x = position.getX();
                int y = position.getY();
                int z = position.getZ();
                int faceX = face.getModX();
                int faceY = face.getModY();
                int faceZ = face.getModZ();
                boolean mainHand = placement.getHand() == InteractionHand.MAIN_HAND;
                Bukkit.getScheduler().runTask(plugin, () ->
                        scaffold.handlePacketPlacement(playerId, x, y, z,
                                faceX, faceY, faceZ, mainHand));
            }
            return;
        }

        if (type == PacketType.Play.Client.PLAYER_DIGGING) {
            WrapperPlayClientPlayerDigging digging =
                    new WrapperPlayClientPlayerDigging(event);
            if (digging.getAction() == DiggingAction.RELEASE_USE_ITEM) {
                noSlow.recordUseRelease(playerId);
            }
            return;
        }

        if (type == PacketType.Play.Client.ATTACK) {
            WrapperPlayClientAttack attack = new WrapperPlayClientAttack(event);
            int targetId = attack.getEntityId();
            Bukkit.getScheduler().runTask(plugin,
                    () -> wallHit.handlePacketAttack(playerId, targetId));
            return;
        }

        if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity interact =
                    new WrapperPlayClientInteractEntity(event);
            if (interact.getAction()
                    == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                int targetId = interact.getEntityId();
                Bukkit.getScheduler().runTask(plugin,
                        () -> wallHit.handlePacketAttack(playerId, targetId));
            }
        }
    }
}
