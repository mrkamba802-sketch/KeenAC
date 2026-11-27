package dev.keenac.engine.core.network;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPosition;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientWindowConfirmation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnLivingEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerAbilities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRespawn;
import dev.keenac.KeenAC;
import dev.keenac.engine.core.bedrock.BedrockInfo;
import dev.keenac.engine.core.bedrock.GeyserManager;
import dev.keenac.engine.core.check.CheckManager;
import dev.keenac.engine.core.check.impl.combat.ReachCheck;
import dev.keenac.engine.core.compensation.LatencyCompensator;
import dev.keenac.engine.core.compensation.PingPongManager;
import dev.keenac.engine.core.compensation.TransactionManager;
import dev.keenac.engine.core.compensation.WorldChange;
import dev.keenac.engine.core.player.PlayerManager;
import dev.keenac.engine.core.player.TrackedPlayer;
import dev.keenac.engine.core.util.PacketClock;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import java.util.UUID;
public class PacketListener extends PacketListenerAbstract {
    private final KeenAC plugin;
    private final PlayerManager playerManager;
    private final CheckManager checkManager;
    private final TransactionManager transactionManager;
    private final PingPongManager pingPongManager;
    public PacketListener(KeenAC plugin) {
        this.plugin = plugin;
        this.playerManager = plugin.getPlayerManager();
        this.checkManager = plugin.getCheckManager();
        this.transactionManager = plugin.getTransactionManager();
        this.pingPongManager = plugin.getPingPongManager();
    }
    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        TrackedPlayer tracked = playerManager.get(uuid);
        if (tracked == null || !tracked.isValid()) return;
        PacketTypeCommon type = event.getPacketType();
        boolean transactionMatched = false;
        boolean transactionResponse = false;
        if (type == PacketType.Play.Client.PONG
                || type == PacketType.Play.Client.WINDOW_CONFIRMATION) {
            short responseId = readTransactionResponseId(event, type);
            if (responseId >= 0 && transactionManager.hasPendingTransaction(uuid, responseId)) {
                transactionMatched = transactionManager.processTransaction(tracked, responseId);
                pingPongManager.processPingResponse(tracked, responseId);
                transactionResponse = transactionMatched;
            }
        }
        if (type == PacketType.Play.Client.PONG
                || type == PacketType.Play.Client.WINDOW_CONFIRMATION
                || !isRelevantIncoming(type)) {
            return;
        }
        PacketReceiveEvent copy;
        try {
            copy = event.clone();
        } catch (RuntimeException exception) {
            plugin.getLogger().fine("Could not retain incoming packet for primary-thread processing: "
                    + exception.getMessage());
            return;
        }
        final boolean matched = transactionMatched;
        final boolean wasTransactionResponse = transactionResponse;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    if (!tracked.isValid() || !player.isOnline()) return;
                    PacketClock.enter(copy.getTimestamp());
                    processReceiveOnMain(tracked, copy, wasTransactionResponse, matched);
                } finally {
                    PacketClock.exit();
                    copy.cleanUp();
                }
            });
        } catch (RuntimeException exception) {
            copy.cleanUp();
            plugin.getLogger().fine("Could not queue incoming packet: " + exception.getMessage());
        }
    }
    private void processReceiveOnMain(TrackedPlayer tracked, PacketReceiveEvent event,
                                      boolean transactionResponse, boolean matched) {
        PacketTypeCommon type = event.getPacketType();
        if (tracked.isRespawned()
                && (type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {
            tracked.setRespawned(false);
        }
        if (type == PacketType.Play.Client.PLAYER_POSITION) {
            WrapperPlayClientPlayerPosition wrapper = new WrapperPlayClientPlayerPosition(event);
            Vector3d pos = wrapper.getPosition();
            tracked.setPosition(pos.x, pos.y, pos.z);
            tracked.setOnGround(wrapper.isOnGround());
            tracked.setMovedSinceTick(true);
        } else if (type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            WrapperPlayClientPlayerPositionAndRotation wrapper =
                    new WrapperPlayClientPlayerPositionAndRotation(event);
            Vector3d pos = wrapper.getPosition();
            tracked.setPosition(pos.x, pos.y, pos.z);
            tracked.setYaw(wrapper.getYaw());
            tracked.setPitch(wrapper.getPitch());
            tracked.setOnGround(wrapper.isOnGround());
            tracked.setMovedSinceTick(true);
        } else if (type == PacketType.Play.Client.PLAYER_ROTATION) {
            WrapperPlayClientPlayerRotation wrapper = new WrapperPlayClientPlayerRotation(event);
            tracked.setYaw(wrapper.getYaw());
            tracked.setPitch(wrapper.getPitch());
            tracked.setOnGround(wrapper.isOnGround());
        } else if (type == PacketType.Play.Client.PLAYER_FLYING) {
            WrapperPlayClientPlayerFlying wrapper = new WrapperPlayClientPlayerFlying(event);
            tracked.setOnGround(wrapper.isOnGround());
        } else if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            tracked.setLastAttackTime(PacketClock.currentTimeMillis());
        }
        tracked.getActionData().processReceive(event);
        if (transactionResponse) {
            checkManager.onTransactionResponse(tracked, matched);
        }
        checkManager.onPacketReceive(tracked, event);
    }
    @Override
    public void onPacketSend(PacketSendEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Login.Server.LOGIN_SUCCESS) {
            User loginUser = event.getUser();
            if (loginUser == null) return;
            try {
                Bukkit.getScheduler().runTask(plugin, () -> handleLogin(player, loginUser));
            } catch (RuntimeException exception) {
                plugin.getLogger().fine("Could not queue player tracking initialization: "
                        + exception.getMessage());
            }
            return;
        }
        TrackedPlayer tracked = playerManager.get(player.getUniqueId());
        if (tracked == null || !tracked.isValid() || !isRelevantOutgoing(type)) return;
        PacketSendEvent copy;
        try {
            copy = event.clone();
        } catch (RuntimeException exception) {
            plugin.getLogger().fine("Could not retain outgoing packet for primary-thread processing: "
                    + exception.getMessage());
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    if (!tracked.isValid() || !player.isOnline()) return;
                    PacketClock.enter(copy.getTimestamp());
                    processSendOnMain(tracked, player, copy);
                } finally {
                    PacketClock.exit();
                    copy.cleanUp();
                }
            });
        } catch (RuntimeException exception) {
            copy.cleanUp();
            plugin.getLogger().fine("Could not queue outgoing packet: " + exception.getMessage());
        }
    }
    private void processSendOnMain(TrackedPlayer tracked, Player player, PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.ENTITY_VELOCITY) {
            WrapperPlayServerEntityVelocity wrapper = new WrapperPlayServerEntityVelocity(event);
            if (wrapper.getEntityId() == player.getEntityId()) {
                Vector3d velocity = wrapper.getVelocity();
                tracked.setServerVelocityX(velocity.x);
                tracked.setServerVelocityY(velocity.y);
                tracked.setServerVelocityZ(velocity.z);
                tracked.setVelocityReceived(true);
                LatencyCompensator compensator = plugin.getLatencyCompensator();
                if (compensator != null) {
                    int tick = pingPongManager.getCurrentTick(tracked);
                    compensator.recordChange(player.getUniqueId(), tick,
                            WorldChange.velocity(tick, velocity.x, velocity.y, velocity.z));
                }
            }
        } else if (type == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
            WrapperPlayServerPlayerPositionAndLook wrapper =
                    new WrapperPlayServerPlayerPositionAndLook(event);
            tracked.setTeleportPosition(wrapper.getX(), wrapper.getY(), wrapper.getZ());
        } else if (type == PacketType.Play.Server.RESPAWN) {
            tracked.setPosition(0, 0, 0);
            tracked.setOnGround(true);
            tracked.setServerOnGround(true);
            tracked.setSprinting(false);
            tracked.setSneaking(false);
            tracked.setVelocity(0.0, 0.0, 0.0);
            tracked.setServerVelocityX(0.0);
            tracked.setServerVelocityY(0.0);
            tracked.setServerVelocityZ(0.0);
            tracked.setVelocityReceived(false);
            tracked.setRespawned(true);
        } else if (type == PacketType.Play.Server.PLAYER_ABILITIES) {
            WrapperPlayServerPlayerAbilities wrapper = new WrapperPlayServerPlayerAbilities(event);
            tracked.setFlying(wrapper.isFlying());
            tracked.setAllowFlight(wrapper.isFlightAllowed());
        }
        trackEntities(event, type, player);
        tracked.getActionData().processSend(event);
        checkManager.onPacketSend(tracked, event);
    }
    private boolean isRelevantIncoming(PacketTypeCommon type) {
        return WrapperPlayClientPlayerFlying.isFlying(type)
                || type == PacketType.Play.Client.INTERACT_ENTITY
                || type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT
                || type == PacketType.Play.Client.USE_ITEM
                || type == PacketType.Play.Client.CLICK_WINDOW
                || type == PacketType.Play.Client.CREATIVE_INVENTORY_ACTION
                || type == PacketType.Play.Client.PLAYER_DIGGING
                || type == PacketType.Play.Client.ANIMATION
                || type == PacketType.Play.Client.CHAT_MESSAGE
                || type == PacketType.Play.Client.CLOSE_WINDOW
                || type == PacketType.Play.Client.PLUGIN_MESSAGE
                || type == PacketType.Play.Client.HELD_ITEM_CHANGE
                || type == PacketType.Play.Client.STEER_VEHICLE;
    }
    private boolean isRelevantOutgoing(PacketTypeCommon type) {
        return type == PacketType.Play.Server.ENTITY_VELOCITY
                || type == PacketType.Play.Server.EXPLOSION
                || type == PacketType.Play.Server.WINDOW_CONFIRMATION
                || type == PacketType.Play.Server.OPEN_WINDOW
                || type == PacketType.Play.Server.BLOCK_CHANGE
                || type == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK
                || type == PacketType.Play.Server.RESPAWN
                || type == PacketType.Play.Server.PLAYER_ABILITIES
                || type == PacketType.Play.Server.SPAWN_LIVING_ENTITY
                || type == PacketType.Play.Server.SPAWN_ENTITY
                || type == PacketType.Play.Server.SPAWN_PLAYER
                || type == PacketType.Play.Server.ENTITY_TELEPORT
                || type == PacketType.Play.Server.ENTITY_POSITION_SYNC
                || type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE
                || type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION
                || type == PacketType.Play.Server.DESTROY_ENTITIES;
    }
    private short readTransactionResponseId(PacketReceiveEvent event, PacketTypeCommon type) {
        try {
            if (type == PacketType.Play.Client.PONG) {
                return (short) new WrapperPlayClientPong(event).getId();
            }
            return new WrapperPlayClientWindowConfirmation(event).getActionId();
        } catch (Exception exception) {
            return -1;
        }
    }
    private void handleLogin(Player player, User user) {
        if (playerManager.get(player.getUniqueId()) != null || user == null) return;
        TrackedPlayer tracked = new TrackedPlayer(player, user);
        playerManager.add(tracked);
        ClientVersion version = user.getClientVersion();
        if (version != null) {
            tracked.setClientVersion(version);
            tracked.setProtocolVersion(version.getProtocolVersion());
        }
        GeyserManager geyserManager = plugin.getGeyserManager();
        if (geyserManager != null && geyserManager.isGeyserPresent()) {
            BedrockInfo bedrockInfo = geyserManager.getBedrockInfo(player.getUniqueId());
            if (bedrockInfo != null) tracked.setBedrockInfo(bedrockInfo);
        }
    }
    private void trackEntities(PacketSendEvent event, PacketTypeCommon type, Player viewer) {
        try {
            if (type == PacketType.Play.Server.SPAWN_LIVING_ENTITY) {
                WrapperPlayServerSpawnLivingEntity wrapper = new WrapperPlayServerSpawnLivingEntity(event);
                Vector3d pos = wrapper.getPosition();
                trackSpawn(viewer, wrapper.getEntityId(), wrapper.getEntityType(), pos.x, pos.y, pos.z);
            } else if (type == PacketType.Play.Server.SPAWN_ENTITY) {
                WrapperPlayServerSpawnEntity wrapper = new WrapperPlayServerSpawnEntity(event);
                Vector3d pos = wrapper.getPosition();
                trackSpawn(viewer, wrapper.getEntityId(), wrapper.getEntityType(), pos.x, pos.y, pos.z);
            } else if (type == PacketType.Play.Server.SPAWN_PLAYER) {
                WrapperPlayServerSpawnPlayer wrapper = new WrapperPlayServerSpawnPlayer(event);
                Vector3d pos = wrapper.getPosition();
                trackSpawn(viewer, wrapper.getEntityId(), EntityTypes.PLAYER, pos.x, pos.y, pos.z);
            } else if (type == PacketType.Play.Server.ENTITY_TELEPORT) {
                WrapperPlayServerEntityTeleport wrapper = new WrapperPlayServerEntityTeleport(event);
                applyEntityMove(wrapper.getEntityId(), wrapper.getPosition(), wrapper.getRelativeFlags());
            } else if (type == PacketType.Play.Server.ENTITY_POSITION_SYNC) {
                WrapperPlayServerEntityPositionSync wrapper = new WrapperPlayServerEntityPositionSync(event);
                applyEntityMove(wrapper.getId(), wrapper.getValues().getPosition(), RelativeFlag.NONE);
            } else if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE) {
                WrapperPlayServerEntityRelativeMove wrapper = new WrapperPlayServerEntityRelativeMove(event);
                applyRelativeMove(wrapper.getEntityId(), wrapper.getDeltaX(), wrapper.getDeltaY(),
                        wrapper.getDeltaZ());
            } else if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION) {
                WrapperPlayServerEntityRelativeMoveAndRotation wrapper =
                        new WrapperPlayServerEntityRelativeMoveAndRotation(event);
                applyRelativeMove(wrapper.getEntityId(), wrapper.getDeltaX(), wrapper.getDeltaY(),
                        wrapper.getDeltaZ());
            } else if (type == PacketType.Play.Server.DESTROY_ENTITIES) {
                WrapperPlayServerDestroyEntities wrapper = new WrapperPlayServerDestroyEntities(event);
                for (int entityId : wrapper.getEntityIds()) {
                    ReachCheck.trackRemove(entityId);
                }
            }
        } catch (Exception e) {
        }
    }
    private void trackSpawn(Player viewer, int entityId,
                            com.github.retrooper.packetevents.protocol.entity.type.EntityType type,
                            double x, double y, double z) {
        for (Entity entity : viewer.getWorld().getEntities()) {
            if (entity.getEntityId() != entityId) continue;
            var box = entity.getBoundingBox();
            ReachCheck.trackSpawn(entityId, type, x, y, z,
                    box.getWidthX() / 2.0, box.getWidthZ() / 2.0, box.getHeight());
            return;
        }
        ReachCheck.trackSpawn(entityId, type, x, y, z);
    }
    private void applyEntityMove(int entityId, Vector3d pos, RelativeFlag flags) {
        double x = pos.x;
        double y = pos.y;
        double z = pos.z;
        if (flags != null && (flags.has(RelativeFlag.X) || flags.has(RelativeFlag.Y)
                || flags.has(RelativeFlag.Z))) {
            double[] last = ReachCheck.getTrackedPosition(entityId);
            if (last != null) {
                x = flags.has(RelativeFlag.X) ? last[0] + x : x;
                y = flags.has(RelativeFlag.Y) ? last[1] + y : y;
                z = flags.has(RelativeFlag.Z) ? last[2] + z : z;
            }
        }
        ReachCheck.trackMove(entityId, x, y, z);
    }
    private void applyRelativeMove(int entityId, double dx, double dy, double dz) {
        double[] last = ReachCheck.getTrackedPosition(entityId);
        if (last == null) return;
        ReachCheck.trackMove(entityId, last[0] + dx, last[1] + dy, last[2] + dz);
    }
}
