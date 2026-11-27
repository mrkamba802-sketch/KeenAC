package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.BoundingBox;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PacketEvents reach check with bounded ping compensation and historical target hitboxes.
 * Bukkit data is sampled on the main thread; packet callbacks only access immutable snapshots.
 */
public final class ReachCheck implements Listener {
    private static final long HISTORY_NANOS = 1_500_000_000L;
    private static final long RECENT_PACKET_NANOS = 150_000_000L;
    private static final long ATTACKER_SNAPSHOT_MAX_AGE_NANOS = 250_000_000L;
    private static final int ATTACKER_SNAPSHOT_WINDOW = 5;
    private static final long MAX_FRAME_GAP_NANOS = 200_000_000L;

    private final KeenAC plugin;
    private final Map<UUID, PlayerTrack> players = new ConcurrentHashMap<>();
    private final Map<Integer, TargetTrack> targets = new ConcurrentHashMap<>();
    private final Map<UUID, AttackState> attacks = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private volatile boolean cancelHits;
    private volatile double baseReach;
    private volatile double maxPingBuffer;
    private volatile double pingBufferPerMs;
    private volatile int requiredHits;
    private volatile long windowNanos;
    private volatile long maxRewindNanos;

    public ReachCheck(KeenAC plugin) {
        this.plugin = plugin;
        reloadSettings();
    }

    public void reloadSettings() {
        enabled = plugin.getConfig().getBoolean("checks.reach.enabled", true);
        cancelHits = plugin.getConfig().getBoolean("checks.reach.cancel-hits", true);
        baseReach = clamp(plugin.getConfig().getDouble("checks.reach.base-distance", 3.10), 2.5, 4.5);
        maxPingBuffer = clamp(plugin.getConfig().getDouble("checks.reach.max-ping-buffer", 0.85), 0.0, 1.5);
        pingBufferPerMs = clamp(plugin.getConfig().getDouble("checks.reach.ping-buffer-per-ms", 0.002), 0.0, 0.01);
        requiredHits = Math.max(2, plugin.getConfig().getInt("checks.reach.required-hits", 3));
        windowNanos = Math.max(500L, plugin.getConfig().getLong("checks.reach.window-ms", 2500L)) * 1_000_000L;
        maxRewindNanos = Math.max(0L,
                Math.min(750L, plugin.getConfig().getLong("checks.reach.max-rewind-ms", 500L))) * 1_000_000L;
    }

    /** Refresh player positions and target hitboxes once per server tick. */
    public void tick() {
        if (!enabled) {
            players.clear();
            targets.clear();
            attacks.clear();
            return;
        }

        long now = System.nanoTime();
        java.util.Set<UUID> online = ConcurrentHashMap.newKeySet();
        java.util.Set<Integer> entityIds = ConcurrentHashMap.newKeySet();

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            int entityId = player.getEntityId();
            online.add(id);
            entityIds.add(entityId);

            players.computeIfAbsent(id, ignored -> new PlayerTrack()).refresh(player, now);

            BoundingBox box = player.getBoundingBox();
            TargetFrame frame = new TargetFrame(
                    id, player.getWorld().getUID(), now,
                    box.getMinX(), box.getMinY(), box.getMinZ(),
                    box.getMaxX(), box.getMaxY(), box.getMaxZ(),
                    isEligible(player));
            targets.computeIfAbsent(entityId, ignored -> new TargetTrack()).add(frame);
        }

        players.keySet().removeIf(id -> !online.contains(id));
        targets.keySet().removeIf(id -> !entityIds.contains(id));
        attacks.keySet().removeIf(id -> !online.contains(id));
    }

    /** Receives absolute coordinates from incoming PacketEvents movement packets. */
    public void recordPacketPosition(UUID playerId, double x, double y, double z) {
        if (!enabled || playerId == null) return;
        PlayerTrack track = players.get(playerId);
        if (track != null) track.recordPacketPosition(x, y, z, System.nanoTime());
    }

    /**
     * @return true only after several out-of-range attack packets; a single sample
     * never cancels an attack or raises an alert.
     */
    public boolean handleAttack(UUID attackerId, int targetEntityId) {
        if (!enabled || attackerId == null) return false;

        PlayerTrack attackerTrack = players.get(attackerId);
        TargetTrack targetTrack = targets.get(targetEntityId);
        if (attackerTrack == null || targetTrack == null) return false;

        PlayerFrame attacker = attackerTrack.snapshot;
        if (attacker == null || !attacker.eligible) return false;

        long now = System.nanoTime();
        long rewind = Math.min(maxRewindNanos,
                Math.max(0L, attacker.pingMillis) * 1_000_000L);
        TargetFrame target = targetTrack.at(now - rewind, now);
        if (target == null || !target.eligible
                || attacker.playerId.equals(target.playerId)
                || !attacker.worldId.equals(target.worldId)) {
            return false;
        }

        double eyeX = attacker.x;
        double eyeY = attacker.y + attacker.eyeHeight;
        double eyeZ = attacker.z;
        double distance = distanceToBox(eyeX, eyeY, eyeZ, target);

        // Windfall-style short attacker history supplements KeenAC's existing
        // target-hitbox rewind. It is intentionally bounded to five snapshots
        // and 250 ms, and uses the closest plausible recent eye position.
        for (PlayerFrame sample : attackerTrack.recentFrames(now)) {
            if (!sample.eligible || !sample.worldId.equals(attacker.worldId)
                    || now - sample.timestampNanos > ATTACKER_SNAPSHOT_MAX_AGE_NANOS) {
                continue;
            }
            double snapshotDistance = distanceToBox(
                    sample.x, sample.y + sample.eyeHeight, sample.z, target);
            if (snapshotDistance < distance) distance = snapshotDistance;
        }

        // The check remains active at every ping. Both rewind and added tolerance
        // are bounded so high latency cannot disable Reach or create an unlimited range.
        double latencyBuffer = Math.min(maxPingBuffer,
                Math.max(0, attacker.pingMillis) * pingBufferPerMs);
        double allowedReach = baseReach + latencyBuffer;

        AttackState state = attacks.computeIfAbsent(attackerId, ignored -> new AttackState());
        boolean sendAlert = false;
        int vl = 0;
        synchronized (state) {
            if (distance <= allowedReach) {
                state.suspiciousHits.clear();
                return false;
            }

            while (!state.suspiciousHits.isEmpty()
                    && now - state.suspiciousHits.peekFirst() > windowNanos) {
                state.suspiciousHits.removeFirst();
            }
            state.suspiciousHits.addLast(now);
            if (state.suspiciousHits.size() < requiredHits) return false;

            state.suspiciousHits.clear();
            state.violationLevel++;
            vl = state.violationLevel;
            sendAlert = true;
        }

        if (sendAlert && plugin.isEnabled()) {
            final int alertVl = vl;
            final double measured = distance;
            final double limit = allowedReach;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(attackerId);
                if (player != null && player.isOnline()) {
                    plugin.getAlertManager().send("Reach", player, alertVl,
                            String.format(java.util.Locale.ROOT,
                                    "Attack distance %.3f blocks exceeded the allowed limit %.3f blocks.",
                                    measured, limit));
                    plugin.getLogger().fine(String.format(java.util.Locale.ROOT,
                            "Reach review for %s (VL=%d, distance=%.3f, limit=%.3f)",
                            player.getName(), alertVl, measured, limit));
                }
            });
        }
        return cancelHits;
    }

    private static double distanceToBox(double x, double y, double z, TargetFrame box) {
        double dx = x - clamp(x, box.minX, box.maxX);
        double dy = y - clamp(y, box.minY, box.maxY);
        double dz = z - clamp(z, box.minZ, box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean isEligible(Player player) {
        GameMode mode = player.getGameMode();
        return player.isOnline() && player.isValid() && !player.isDead()
                && !player.isInsideVehicle()
                && mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        if (!event.isCancelled()) reset(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        reset(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        reset(event.getPlayer());
    }

    private void reset(Player player) {
        UUID id = player.getUniqueId();
        players.remove(id);
        targets.remove(player.getEntityId());
        AttackState state = attacks.get(id);
        if (state != null) {
            synchronized (state) {
                state.suspiciousHits.clear();
            }
        }
    }

    private void forget(Player player) {
        UUID id = player.getUniqueId();
        players.remove(id);
        targets.remove(player.getEntityId());
        attacks.remove(id);
    }

    public void clearAll() {
        players.clear();
        targets.clear();
        attacks.clear();
    }

    private static final class PlayerTrack {
        private volatile PlayerFrame snapshot;
        private volatile long lastPacketPositionNanos;
        private final Deque<PlayerFrame> attackerSnapshots = new ArrayDeque<>();

        private synchronized void refresh(Player player, long now) {
            Location location = player.getLocation();
            PlayerFrame previous = snapshot;
            boolean preservePacketPosition = previous != null
                    && now - lastPacketPositionNanos <= RECENT_PACKET_NANOS
                    && previous.worldId.equals(player.getWorld().getUID());

            snapshot = new PlayerFrame(
                    player.getUniqueId(), player.getWorld().getUID(),
                    preservePacketPosition ? previous.x : location.getX(),
                    preservePacketPosition ? previous.y : location.getY(),
                    preservePacketPosition ? previous.z : location.getZ(),
                    player.getEyeHeight(), Math.max(0, player.getPing()),
                    isEligible(player), now);
            remember(snapshot, now);
        }

        private synchronized void recordPacketPosition(double x, double y, double z, long now) {
            PlayerFrame previous = snapshot;
            if (previous == null) return;
            lastPacketPositionNanos = now;
            snapshot = new PlayerFrame(previous.playerId, previous.worldId,
                    x, y, z, previous.eyeHeight, previous.pingMillis,
                    previous.eligible, now);
            remember(snapshot, now);
        }

        private void remember(PlayerFrame frame, long now) {
            attackerSnapshots.addLast(frame);
            while (attackerSnapshots.size() > ATTACKER_SNAPSHOT_WINDOW) {
                attackerSnapshots.removeFirst();
            }
            while (!attackerSnapshots.isEmpty()
                    && now - attackerSnapshots.peekFirst().timestampNanos
                    > ATTACKER_SNAPSHOT_MAX_AGE_NANOS) {
                attackerSnapshots.removeFirst();
            }
        }

        private synchronized Deque<PlayerFrame> recentFrames(long now) {
            while (!attackerSnapshots.isEmpty()
                    && now - attackerSnapshots.peekFirst().timestampNanos
                    > ATTACKER_SNAPSHOT_MAX_AGE_NANOS) {
                attackerSnapshots.removeFirst();
            }
            return new ArrayDeque<>(attackerSnapshots);
        }
    }

    private record PlayerFrame(UUID playerId, UUID worldId,
                               double x, double y, double z,
                               double eyeHeight, int pingMillis,
                               boolean eligible, long timestampNanos) { }

    private record TargetFrame(UUID playerId, UUID worldId, long timestampNanos,
                               double minX, double minY, double minZ,
                               double maxX, double maxY, double maxZ,
                               boolean eligible) {
        private TargetFrame interpolate(TargetFrame other, double progress, long timestamp) {
            if (!worldId.equals(other.worldId) || !playerId.equals(other.playerId)) return null;
            return new TargetFrame(playerId, worldId, timestamp,
                    lerp(minX, other.minX, progress), lerp(minY, other.minY, progress),
                    lerp(minZ, other.minZ, progress), lerp(maxX, other.maxX, progress),
                    lerp(maxY, other.maxY, progress), lerp(maxZ, other.maxZ, progress),
                    eligible && other.eligible);
        }
    }

    private static final class TargetTrack {
        private final Deque<TargetFrame> frames = new ArrayDeque<>();

        private synchronized void add(TargetFrame frame) {
            TargetFrame last = frames.peekLast();
            if (last != null && (!last.worldId.equals(frame.worldId)
                    || !last.playerId.equals(frame.playerId)
                    || movedDiscontinuously(last, frame))) {
                frames.clear();
            }
            frames.addLast(frame);

            long cutoff = frame.timestampNanos - HISTORY_NANOS;
            while (frames.size() > 2) {
                Iterator<TargetFrame> iterator = frames.iterator();
                iterator.next();
                TargetFrame second = iterator.next();
                if (second.timestampNanos < cutoff) frames.removeFirst();
                else break;
            }
        }

        private synchronized TargetFrame at(long timestamp, long now) {
            if (frames.isEmpty()) return null;
            TargetFrame previous = null;
            for (TargetFrame current : frames) {
                if (current.timestampNanos == timestamp) return current;
                if (current.timestampNanos < timestamp) {
                    previous = current;
                    continue;
                }
                if (previous == null) {
                    return current.timestampNanos - timestamp <= 100_000_000L ? current : null;
                }
                long gap = current.timestampNanos - previous.timestampNanos;
                if (gap <= 0L || gap > MAX_FRAME_GAP_NANOS) return null;
                double progress = (double) (timestamp - previous.timestampNanos) / gap;
                return previous.interpolate(current, progress, timestamp);
            }
            return previous != null && now - previous.timestampNanos <= 150_000_000L
                    ? previous : null;
        }

        private static boolean movedDiscontinuously(TargetFrame a, TargetFrame b) {
            long elapsed = b.timestampNanos - a.timestampNanos;
            if (elapsed <= 0L || elapsed > 250_000_000L) return false;
            double ax = (a.minX + a.maxX) * 0.5;
            double ay = (a.minY + a.maxY) * 0.5;
            double az = (a.minZ + a.maxZ) * 0.5;
            double bx = (b.minX + b.maxX) * 0.5;
            double by = (b.minY + b.maxY) * 0.5;
            double bz = (b.minZ + b.maxZ) * 0.5;
            double dx = bx - ax, dy = by - ay, dz = bz - az;
            return dx * dx + dy * dy + dz * dz > 144.0;
        }
    }

    private static final class AttackState {
        private final Deque<Long> suspiciousHits = new ArrayDeque<>();
        private int violationLevel;
    }

    private static double lerp(double a, double b, double progress) {
        return a + (b - a) * progress;
    }
}
