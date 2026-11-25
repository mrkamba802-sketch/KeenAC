package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conservative PacketEvents-driven PvP direction check.
 * Entity snapshots are refreshed on the main thread; packet callbacks only
 * read immutable snapshots and per-player synchronized attack history.
 */
public final class AuraCheck implements Listener {
    private final KeenAC plugin;
    private final Map<UUID, AimState> aims = new ConcurrentHashMap<>();
    private final Map<Integer, TargetSnapshot> targets = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private volatile double minimumDot;
    private volatile int requiredHits;
    private volatile long windowNanos;
    private volatile long flagDurationNanos;
    private volatile long alertCooldownNanos;
    private volatile double maxDistanceSquared;

    public AuraCheck(KeenAC plugin) {
        this.plugin = plugin;
        reloadSettings();
    }

    public void reloadSettings() {
        enabled = plugin.getConfig().getBoolean("checks.aura.enabled", true);
        double degrees = plugin.getConfig().getDouble(
                "checks.aura.max-angle-degrees", 110.0);
        degrees = Math.max(90.0, Math.min(175.0, degrees));
        minimumDot = Math.cos(Math.toRadians(degrees));
        requiredHits = Math.max(2,
                plugin.getConfig().getInt("checks.aura.required-hits", 3));
        windowNanos = Math.max(250L,
                plugin.getConfig().getLong("checks.aura.window-ms", 1500L))
                * 1_000_000L;
        flagDurationNanos = Math.max(100L,
                plugin.getConfig().getLong("checks.aura.flag-duration-ms", 900L))
                * 1_000_000L;
        alertCooldownNanos = Math.max(1000L,
                plugin.getConfig().getLong("checks.aura.alert-cooldown-ms", 5000L))
                * 1_000_000L;
        double maxDistance = Math.max(3.0,
                plugin.getConfig().getDouble("checks.aura.max-distance", 5.0));
        maxDistanceSquared = maxDistance * maxDistance;
    }

    /** Refresh PvP target positions on the Bukkit main thread once per tick. */
    public void tick() {
        if (!enabled) {
            return;
        }
        long now = System.nanoTime();
        java.util.Set<Integer> onlineIds = new java.util.HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location location = player.getLocation();
            int entityId = player.getEntityId();
            onlineIds.add(entityId);

            AimState aim = aims.computeIfAbsent(
                    player.getUniqueId(), ignored -> new AimState());
            synchronized (aim) {
                aim.worldId = player.getWorld().getUID();
                aim.x = location.getX();
                aim.z = location.getZ();
                // Prefer the latest client rotation packet. Fall back to Bukkit
                // yaw after a short idle interval (join, teleport, or packet gap).
                if (now - aim.lastPacketRotationNanos > 1_500_000_000L) {
                    aim.yaw = location.getYaw();
                }
            }

            targets.put(entityId, new TargetSnapshot(
                    player.getUniqueId(), player.getWorld().getUID(),
                    location.getX(), location.getZ()));
        }
        targets.keySet().removeIf(id -> !onlineIds.contains(id));
    }

    /** Called from PacketEvents for incoming rotation-bearing movement packets. */
    public void recordRotation(UUID playerId, float yaw) {
        AimState aim = aims.computeIfAbsent(playerId, ignored -> new AimState());
        synchronized (aim) {
            aim.yaw = yaw;
            aim.lastPacketRotationNanos = System.nanoTime();
        }
    }

    /**
     * Returns true only after repeated side/back-facing attacks. The triggering
     * packet and the short confirmed-flag window are cancelled.
     */
    public boolean shouldCancelAttack(UUID attackerId, int targetEntityId) {
        if (!enabled) {
            return false;
        }

        AimState attacker = aims.get(attackerId);
        TargetSnapshot target = targets.get(targetEntityId);
        if (attacker == null || target == null
                || attackerId.equals(target.playerId)) {
            return false;
        }

        long now = System.nanoTime();
        boolean alert = false;
        int violationLevel = 0;
        synchronized (attacker) {
            if (now < attacker.flaggedUntilNanos) {
                return true;
            }
            if (attacker.worldId == null || !attacker.worldId.equals(target.worldId)) {
                attacker.suspiciousHits.clear();
                return false;
            }

            double dx = target.x - attacker.x;
            double dz = target.z - attacker.z;
            double distanceSquared = dx * dx + dz * dz;
            if (distanceSquared < 0.04 || distanceSquared > maxDistanceSquared) {
                attacker.suspiciousHits.clear();
                return false;
            }

            double inverseLength = 1.0 / Math.sqrt(distanceSquared);
            double radians = Math.toRadians(attacker.yaw);
            double lookX = -Math.sin(radians);
            double lookZ = Math.cos(radians);
            double dot = lookX * (dx * inverseLength) + lookZ * (dz * inverseLength);

            if (dot >= minimumDot) {
                attacker.suspiciousHits.clear();
                return false;
            }

            while (!attacker.suspiciousHits.isEmpty()
                    && now - attacker.suspiciousHits.peekFirst() > windowNanos) {
                attacker.suspiciousHits.removeFirst();
            }
            attacker.suspiciousHits.addLast(now);
            if (attacker.suspiciousHits.size() < requiredHits) {
                return false;
            }

            attacker.suspiciousHits.clear();
            attacker.flaggedUntilNanos = now + flagDurationNanos;
            attacker.violationLevel++;
            violationLevel = attacker.violationLevel;
            if (now - attacker.lastAlertNanos >= alertCooldownNanos) {
                attacker.lastAlertNanos = now;
                alert = true;
            }
        }

        if (alert) {
            final int vl = violationLevel;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(attackerId);
                if (player != null && player.isOnline()) {
                    plugin.getAlertManager().send("Aura", player, vl);
                }
            });
        }
        return true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        aims.remove(id);
        targets.remove(event.getPlayer().getEntityId());
    }

    public void clearAll() {
        aims.clear();
        targets.clear();
    }

    private static final class AimState {
        private final Deque<Long> suspiciousHits = new ArrayDeque<>();
        private UUID worldId;
        private double x;
        private double z;
        private float yaw;
        private long lastPacketRotationNanos;
        private long flaggedUntilNanos;
        private long lastAlertNanos;
        private int violationLevel;
    }

    private record TargetSnapshot(UUID playerId, UUID worldId, double x, double z) { }
}
