package dev.keenac.engine.core.check.impl.combat;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.CompatFlag;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.physics.VersionPhysics;
import dev.keenac.engine.core.player.TrackedPlayer;
import dev.keenac.engine.core.util.PacketClock;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerRotation;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@CheckData(name = "Reach A", stableKey = "keenac.combat.reach", decay = 0.05, setbackVl = 10, compat = {CompatFlag.VIAVERSION_SENSITIVE, CompatFlag.RELAX_ON_MISMATCH}, relaxMultiplier = 1.3)
public class ReachCheck extends Check implements PacketCheck {
    private static final double TOLERANCE = 0.1;
    private static final double PROTOCOL_MARGIN_LEGACY = 0.10;
    private static final double PROTOCOL_MARGIN_MODERN = 0.03;
    private static final double REACH_LEGACY = 4.0;
    private static final double REACH_1_9_BASE = 3.0;
    private static final double REACH_1_9_COOLDOWN_BONUS = 0.5;
    private static final double REACH_MODERN = 3.0;
    private static final double PLAYER_WIDTH = 0.6;
    private static final double PLAYER_HEIGHT = 1.8;
    private static final double PLAYER_SNEAK_HEIGHT_1_14 = 1.5;
    private static final double PLAYER_SNEAK_HEIGHT_LEGACY = 1.62;
    private static final double ENTITY_DEFAULT_SIZE = 0.25;
    private static final int ROLLING_WINDOW = 20;
    private static final int ATTACKER_SNAPSHOT_WINDOW = 5;
    private static final long ATTACKER_SNAPSHOT_MAX_AGE_MS = 250L;
    private static final ConcurrentHashMap<Integer, TrackedEntity> trackedEntities = new ConcurrentHashMap<>();
    private static final class PlayerState {
        final ArrayDeque<Double> reachSamples = new ArrayDeque<>();
        final ArrayDeque<AttackerSnapshot> attackerSnapshots = new ArrayDeque<>();
    }
    private static final class AttackerSnapshot {
        final double eyeX, eyeY, eyeZ;
        final long timestamp;
        AttackerSnapshot(double eyeX, double eyeY, double eyeZ, long timestamp) {
            this.eyeX = eyeX;
            this.eyeY = eyeY;
            this.eyeZ = eyeZ;
            this.timestamp = timestamp;
        }
    }
    private final ConcurrentHashMap<UUID, PlayerState> stateMap = new ConcurrentHashMap<>();
    private PlayerState getState(TrackedPlayer player) {
        return stateMap.computeIfAbsent(player.getUuid(), k -> new PlayerState());
    }
    @Override
    public void removePlayer(UUID uuid) {
        stateMap.remove(uuid);
    }
    public static void trackSpawn(int entityId, EntityType type, double x, double y, double z) {
        trackedEntities.put(entityId, new TrackedEntity(type, x, y, z,
                PacketClock.currentTimeMillis(), defaultHalfWidth(type), defaultHeight(type)));
    }
    public static void trackSpawn(int entityId, EntityType type, double x, double y, double z,
                                  double halfWidthX, double halfWidthZ, double height) {
        if (!Double.isFinite(halfWidthX) || !Double.isFinite(halfWidthZ)
                || !Double.isFinite(height) || halfWidthX <= 0.0
                || halfWidthZ <= 0.0 || height <= 0.0) {
            trackSpawn(entityId, type, x, y, z);
            return;
        }
        trackedEntities.put(entityId, new TrackedEntity(type, x, y, z,
                PacketClock.currentTimeMillis(), halfWidthX, halfWidthZ, height));
    }
    private static double defaultHalfWidth(EntityType type) {
        return type == EntityTypes.PLAYER ? PLAYER_WIDTH / 2.0 : ENTITY_DEFAULT_SIZE / 2.0;
    }
    private static double defaultHeight(EntityType type) {
        return type == EntityTypes.PLAYER ? PLAYER_HEIGHT : ENTITY_DEFAULT_SIZE;
    }
    public static void trackMove(int entityId, double x, double y, double z) {
        trackedEntities.compute(entityId, (ignored, old) -> {
            if (old == null) return new TrackedEntity(null, x, y, z, PacketClock.currentTimeMillis(), PLAYER_WIDTH / 2.0, PLAYER_WIDTH / 2.0, PLAYER_HEIGHT);
            old.update(x, y, z, PacketClock.currentTimeMillis());
            return old;
        });
    }
    public static void trackRemove(int entityId) {
        trackedEntities.remove(entityId);
    }
    public static double[] getTrackedPosition(int entityId) {
        TrackedEntity te = trackedEntities.get(entityId);
        return te != null ? new double[]{te.x, te.y, te.z} : null;
    }
    public static void cleanup(long maxAgeMs) {
        long now = PacketClock.currentTimeMillis();
        trackedEntities.entrySet().removeIf(e -> now - e.getValue().timestamp > maxAgeMs);
    }
    @Override
    public void onPacketReceive(TrackedPlayer player, PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Client.PLAYER_ROTATION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            recordAttackerSnapshot(player);
            return;
        }
        if (type != PacketType.Play.Client.INTERACT_ENTITY) return;
        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
        if (wrapper.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) return;
        PlayerState state = getState(player);
        int targetId = wrapper.getEntityId();
        double[] compensated = getCompensatedSamples(player, targetId);
        double[] targetBB;
        if (compensated != null) {
            TrackedEntity te = trackedEntities.get(targetId);
            double halfWidthX = te != null ? te.halfWidthX : ENTITY_DEFAULT_SIZE / 2.0;
            double halfWidthZ = te != null ? te.halfWidthZ : ENTITY_DEFAULT_SIZE / 2.0;
            double bbHeight = te != null ? te.height : ENTITY_DEFAULT_SIZE;
            targetBB = new double[]{
                    compensated[0] - halfWidthX, compensated[1], compensated[2] - halfWidthZ,
                    compensated[0] + halfWidthX, compensated[1] + bbHeight, compensated[2] + halfWidthZ
            };
        } else {
            targetBB = getEntityBoundingBox(targetId);
        }
        if (targetBB == null) return;
        double bestReach = Double.MAX_VALUE;
        long now = PacketClock.currentTimeMillis();
        ArrayDeque<AttackerSnapshot> snapshots = state.attackerSnapshots;
        if (snapshots.isEmpty()) {
            bestReach = computeReachFromPosition(
                    player.getX(), player.getY() + getEyeHeight(player), player.getZ(),
                    targetBB);
        } else {
            for (AttackerSnapshot snap : snapshots) {
                long age = now - snap.timestamp;
                if (age > ATTACKER_SNAPSHOT_MAX_AGE_MS) continue;
                double reach = computeReachFromPosition(snap.eyeX, snap.eyeY, snap.eyeZ, targetBB);
                if (reach < bestReach) {
                    bestReach = reach;
                }
            }
            double currentReach = computeReachFromPosition(
                    player.getX(), player.getY() + getEyeHeight(player), player.getZ(),
                    targetBB);
            if (currentReach < bestReach) {
                bestReach = currentReach;
            }
        }
        state.reachSamples.addLast(bestReach);
        if (state.reachSamples.size() > ROLLING_WINDOW) {
            state.reachSamples.removeFirst();
        }
        double limit = getReachLimit(player);
        double pingTolerance = Math.min(player.getTransactionPing() * 0.001, 0.3);
        double protocolMargin = getProtocolMargin(player);
        double effectiveLimit = limit + TOLERANCE + pingTolerance + protocolMargin;
        if (bestReach > effectiveLimit) {
            flag(player);
            return;
        }
        double avgReach = state.reachSamples.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
        if (avgReach > limit + 0.05 && state.reachSamples.size() >= ROLLING_WINDOW / 2) {
            increaseBuffer(player, 0.5);
            if (getBuffer(player) > 3.0) {
                flag(player);
                resetBuffer(player);
            }
        } else {
            decreaseBuffer(player, 0.1);
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
    private void recordAttackerSnapshot(TrackedPlayer player) {
        PlayerState state = getState(player);
        double eyeX = player.getX();
        double eyeY = player.getY() + getEyeHeight(player);
        double eyeZ = player.getZ();
        state.attackerSnapshots.addLast(new AttackerSnapshot(eyeX, eyeY, eyeZ, PacketClock.currentTimeMillis()));
        while (state.attackerSnapshots.size() > ATTACKER_SNAPSHOT_WINDOW) {
            state.attackerSnapshots.removeFirst();
        }
    }
    private double computeReachFromPosition(double eyeX, double eyeY, double eyeZ, double[] targetBB) {
        return calculateReachDistance(
                eyeX, eyeY, eyeZ,
                targetBB[0], targetBB[1], targetBB[2],
                targetBB[3], targetBB[4], targetBB[5]);
    }
    private double getProtocolMargin(TrackedPlayer player) {
        return player.getProtocolVersion() < 107 ? PROTOCOL_MARGIN_LEGACY : PROTOCOL_MARGIN_MODERN;
    }
    private double[] getCompensatedSamples(TrackedPlayer player, int entityId) {
        int oneWayLatencyMs = Math.max(0, player.getTransactionPing() / 2);
        KeenAC plugin = KeenAC.getInstance();
        if (plugin != null && plugin.getPingPongManager() != null) {
            oneWayLatencyMs = plugin.getPingPongManager().getEstimatedLatencyMs(player);
        }
        oneWayLatencyMs = Math.max(0, Math.min(250, oneWayLatencyMs));
        long targetTimestamp = PacketClock.currentTimeMillis() - oneWayLatencyMs;
        return getEntityPositionAtTime(entityId, targetTimestamp);
    }
    private double[] getEntityPositionAtTime(int entityId, long targetTimestamp) {
        TrackedEntity entity = trackedEntities.get(entityId);
        if (entity == null) return null;
        return entity.positionAt(targetTimestamp);
    }
    private double getReachLimit(TrackedPlayer player) {
        int protocol = player.getProtocolVersion();
        double protocolReach = VersionPhysics.getMaxReach(protocol);
        double attributeReach = player.getAttributeSnapshot() != null
                ? player.getAttributeSnapshot().entityInteractionRange() : 3.0;
        double baseReach = Math.max(protocolReach, Math.min(6.0, attributeReach));
        if (VersionPhysics.hasAttackCooldown(protocol)) {
            int cooldown = player.getAttackCooldown();
            double cooldownModifier = Math.min(cooldown / 20.0, 1.0);
            return baseReach + (VersionPhysics.getCooldownReachBonus(protocol) * cooldownModifier);
        }
        return baseReach;
    }
    private double getEyeHeight(TrackedPlayer player) {
        int protocol = player.getProtocolVersion();
        return VersionPhysics.getPlayerEyeHeight(player.isSneaking(), protocol);
    }
    private double calculateReachDistance(double eyeX, double eyeY, double eyeZ,
                                          double bbMinX, double bbMinY, double bbMinZ,
                                          double bbMaxX, double bbMaxY, double bbMaxZ) {
        double closestX = clamp(eyeX, bbMinX, bbMaxX);
        double closestY = clamp(eyeY, bbMinY, bbMaxY);
        double closestZ = clamp(eyeZ, bbMinZ, bbMaxZ);
        double dx = eyeX - closestX;
        double dy = eyeY - closestY;
        double dz = eyeZ - closestZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    private double[] getEntityBoundingBox(int entityId) {
        TrackedEntity te = trackedEntities.get(entityId);
        if (te == null) return null;
        return new double[]{
                te.x - te.halfWidthX, te.y, te.z - te.halfWidthZ,
                te.x + te.halfWidthX, te.y + te.height, te.z + te.halfWidthZ
        };
    }
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
    private static final class TrackedEntity {
        private static final int MAX_HISTORY = 40;
        final EntityType type;
        volatile double x, y, z;
        volatile long timestamp;
        private final ArrayDeque<EntityPosition> history = new ArrayDeque<>();
        TrackedEntity(EntityType type, double x, double y, double z, long timestamp) {
            this.type = type;
            update(x, y, z, timestamp);
        }
        synchronized void update(double x, double y, double z, long timestamp) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.timestamp = timestamp;
            history.addLast(new EntityPosition(x, y, z, timestamp));
            while (history.size() > MAX_HISTORY) history.removeFirst();
        }
        synchronized double[] positionAt(long targetTimestamp) {
            if (history.isEmpty()) return new double[]{x, y, z};
            EntityPosition previous = null;
            for (EntityPosition next : history) {
                if (next.timestamp >= targetTimestamp) {
                    if (previous == null || next.timestamp <= previous.timestamp) {
                        return new double[]{next.x, next.y, next.z};
                    }
                    double span = next.timestamp - previous.timestamp;
                    if (span <= 0L) return new double[]{next.x, next.y, next.z};
                    double t = Math.max(0.0, Math.min(1.0,
                            (targetTimestamp - previous.timestamp) / (double) span));
                    return new double[]{
                            previous.x + (next.x - previous.x) * t,
                            previous.y + (next.y - previous.y) * t,
                            previous.z + (next.z - previous.z) * t
                    };
                }
                previous = next;
            }
            EntityPosition last = history.peekLast();
            return last == null ? new double[]{x, y, z}
                    : new double[]{last.x, last.y, last.z};
        }
    }
    private record EntityPosition(double x, double y, double z, long timestamp) { }
}
