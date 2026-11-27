package dev.keenac.context;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Shared source of latency and movement context for evidence and staff review.
 * Bukkit state is only read from the primary thread.
 */
public final class PlayerContextManager implements Listener {
    private final Map<UUID, Long> lastTeleportNanos = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastVelocityNanos = new ConcurrentHashMap<>();

    public PlayerContextSnapshot snapshot(Player player) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Player context must be captured on the Bukkit primary thread.");
        }

        long nowNanos = System.nanoTime();
        UUID id = player.getUniqueId();
        double[] tpsSamples = Bukkit.getTPS();
        double tps = tpsSamples.length == 0 ? 20.0 : tpsSamples[0];
        if (!Double.isFinite(tps)) tps = 20.0;

        var location = player.getLocation();
        var velocity = player.getVelocity();
        String effects = player.getActivePotionEffects().stream()
                .map(effect -> effect.getType().getName().toLowerCase(Locale.ROOT))
                .sorted()
                .collect(Collectors.joining(","));

        return new PlayerContextSnapshot(
                System.currentTimeMillis(),
                Math.max(0, player.getPing()),
                tps,
                player.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(),
                velocity.getX(), velocity.getY(), velocity.getZ(),
                player.isOnGround(),
                player.isSprinting(),
                player.isSneaking(),
                player.isSwimming(),
                player.isInWater(),
                player.isFlying(),
                player.isGliding(),
                player.isInsideVehicle(),
                player.getGameMode().name(),
                effects,
                ageMillis(nowNanos, lastTeleportNanos.get(id)),
                ageMillis(nowNanos, lastVelocityNanos.get(id))
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        markTeleport(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        markTeleport(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        markTeleport(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVelocity(PlayerVelocityEvent event) {
        lastVelocityNanos.put(event.getPlayer().getUniqueId(), System.nanoTime());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastTeleportNanos.remove(id);
        lastVelocityNanos.remove(id);
    }

    private void markTeleport(Player player) {
        lastTeleportNanos.put(player.getUniqueId(), System.nanoTime());
    }

    /** Returns whether a server velocity event occurred within the supplied grace window. */
    public boolean isWithinVelocityGrace(UUID playerId, long referenceNanos, long graceMillis) {
        if (playerId == null || graceMillis <= 0L) return false;
        Long eventNanos = lastVelocityNanos.get(playerId);
        if (eventNanos == null || referenceNanos < eventNanos) return false;
        return referenceNanos - eventNanos <= graceMillis * 1_000_000L;
    }

    /** Returns the age of the most recent observed teleport, or -1 when unknown. */
    public long teleportAgeMillis(UUID playerId) {
        return ageMillis(System.nanoTime(), lastTeleportNanos.get(playerId));
    }

    private long ageMillis(long nowNanos, Long eventNanos) {
        return eventNanos == null ? -1L : Math.max(0L, (nowNanos - eventNanos) / 1_000_000L);
    }

    public void clearAll() {
        lastTeleportNanos.clear();
        lastVelocityNanos.clear();
    }
}
