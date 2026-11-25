package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Collects legitimate movement context and aggregates externally supplied
 * motion-suspicion samples for staff review.
 *
 * This class deliberately does not decide whether movement is invalid and
 * never changes, teleports, or restricts a player's movement.
 */
public final class MotionCheck implements Listener {
    private final KeenAC plugin;
    private final Map<UUID, Deque<Long>> suspicionTimes = new HashMap<>();
    private final Map<UUID, Long> reviewUntil = new HashMap<>();
    private final Map<UUID, Long> lastVelocityAt = new HashMap<>();

    public MotionCheck(KeenAC plugin) {
        this.plugin = plugin;
    }

    /**
     * Submit a suspicion signal from a trusted movement-analysis component.
     * Bukkit state is read on the main server thread. No movement punishment
     * is applied. This is an integration point, not a movement heuristic.
     */
    public void recordSuspicion(Player player, String reason) {
        if (player == null) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> recordSuspicion(player, reason));
            return;
        }
        if (!player.isOnline()
                || !plugin.getConfig().getBoolean("checks.motion.enabled", true)) {
            return;
        }

        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        long sampleWindow = Math.max(1L,
                plugin.getConfig().getLong("checks.motion.suspicion-window-ms", 3000L));
        int required = Math.max(1,
                plugin.getConfig().getInt("checks.motion.required-suspicions", 3));

        Deque<Long> samples = suspicionTimes.computeIfAbsent(id, ignored -> new ArrayDeque<>());
        while (!samples.isEmpty() && now - samples.peekFirst() > sampleWindow) {
            samples.removeFirst();
        }
        samples.addLast(now);

        if (samples.size() < required) {
            return;
        }

        long reviewWindow = Math.max(1L,
                plugin.getConfig().getLong("checks.motion.review-window-ms", 3000L));
        long activeUntil = reviewUntil.getOrDefault(id, 0L);

        // Further signals refresh the informational review window without
        // sending repeated alerts or applying any player-side effect.
        if (now >= activeUntil) {
            plugin.getAlertManager().send("Motion", player, samples.size());
            if (plugin.getConfig().getBoolean("checks.motion.log-context", true)) {
                plugin.getLogger().info("Motion review for " + player.getName()
                        + " (signals=" + samples.size() + ", reason="
                        + sanitizeReason(reason) + "): "
                        + describeMovementContext(player, now));
            }
        }
        reviewUntil.put(id, now + reviewWindow);
    }

    /**
     * Returns a diagnostic snapshot. The effective movement attribute,
     * configured walk/fly speeds, potion effects, recent velocity event and
     * common movement states are exposed for human review.
     */
    public String describeMovementContext(Player player) {
        return describeMovementContext(player, System.currentTimeMillis());
    }

    private String describeMovementContext(Player player, long now) {
        AttributeInstance movementAttribute = player.getAttribute(Attribute.MOVEMENT_SPEED);
        String movementValue = movementAttribute == null
                ? "unavailable"
                : String.format(java.util.Locale.ROOT, "%.4f", movementAttribute.getValue());

        String effects = player.getActivePotionEffects().stream()
                .sorted(Comparator.comparing(effect -> effect.getType().getKey().toString()))
                .map(this::formatEffect)
                .collect(Collectors.joining(","));
        if (effects.isEmpty()) {
            effects = "none";
        }

        long velocityAt = lastVelocityAt.getOrDefault(player.getUniqueId(), 0L);
        long velocityGrace = Math.max(0L,
                plugin.getConfig().getLong("checks.motion.velocity-grace-ms", 1200L));
        boolean recentVelocity = velocityAt > 0L && now - velocityAt <= velocityGrace;
        Vector velocity = player.getVelocity();

        return "movementAttribute=" + movementValue
                + ", walkSpeed=" + String.format(java.util.Locale.ROOT, "%.3f", player.getWalkSpeed())
                + ", flySpeed=" + String.format(java.util.Locale.ROOT, "%.3f", player.getFlySpeed())
                + ", effects=[" + effects + "]"
                + ", velocity=" + String.format(java.util.Locale.ROOT, "(%.3f,%.3f,%.3f)",
                        velocity.getX(), velocity.getY(), velocity.getZ())
                + ", recentVelocityEvent=" + recentVelocity
                + ", onGround=" + player.isOnGround()
                + ", sprinting=" + player.isSprinting()
                + ", flying=" + player.isFlying()
                + ", gliding=" + player.isGliding()
                + ", swimming=" + player.isSwimming()
                + ", riptiding=" + player.isRiptiding()
                + ", inVehicle=" + player.isInsideVehicle()
                + ", gameMode=" + player.getGameMode()
                + ", fallDistance=" + String.format(java.util.Locale.ROOT, "%.2f", player.getFallDistance());
    }

    private String formatEffect(PotionEffect effect) {
        return effect.getType().getKey().getKey() + ":" + (effect.getAmplifier() + 1);
    }

    private String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unspecified";
        }
        String safe = reason.replace('\n', ' ').replace('\r', ' ').trim();
        return safe.length() > 120 ? safe.substring(0, 120) : safe;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        lastVelocityAt.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    public void clear(UUID id) {
        suspicionTimes.remove(id);
        reviewUntil.remove(id);
        lastVelocityAt.remove(id);
    }

    public void clearAll() {
        suspicionTimes.clear();
        reviewUntil.clear();
        lastVelocityAt.clear();
    }
}
