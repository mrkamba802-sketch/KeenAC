package dev.keenac.check;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Collects movement context and aggregates externally supplied motion-suspicion
 * samples. At the configured threshold it returns the player to the latest
 * jump origin and prevents positional movement for a short review period.
 */
public final class MotionCheck implements Listener {
    private final KeenAC plugin;
    private final Map<UUID, Deque<Long>> suspicionTimes = new HashMap<>();
    private final Map<UUID, Long> reviewUntil = new HashMap<>();
    private final Map<UUID, Long> lastVelocityAt = new HashMap<>();
    private final Map<UUID, Location> lastJumpOrigin = new HashMap<>();
    private final Map<UUID, Long> lastJumpAt = new HashMap<>();
    private final Map<UUID, Long> frozenUntil = new HashMap<>();
    private final Set<UUID> pendingSetbacks = new HashSet<>();
    private final Set<UUID> applyingSetbacks = new HashSet<>();

    public MotionCheck(KeenAC plugin) {
        this.plugin = plugin;
    }

    /**
     * Submit a suspicion signal from a trusted movement-analysis component.
     * Reaching the configured threshold schedules a return to the last jump
     * origin followed by a strict positional freeze.
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
                plugin.getConfig().getInt("checks.motion.required-suspicions", 5));

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

        // Further signals refresh the review window without repeating the
        // alert or scheduling another setback during the active window.
        if (now >= activeUntil) {
            plugin.getAlertManager().send("Motion", player, samples.size());
            if (plugin.getConfig().getBoolean("checks.motion.log-context", true)) {
                plugin.getLogger().info("Motion review for " + player.getName()
                        + " (signals=" + samples.size() + ", reason="
                        + sanitizeReason(reason) + "): "
                        + describeMovementContext(player, now));
            }
            if (plugin.getConfig().getBoolean("checks.motion.setback.enabled", true)) {
                scheduleHardSetback(player);
            }
        }
        reviewUntil.put(id, now + reviewWindow);
    }

    /**
     * Diagnostic snapshot for staff review.
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

    /**
     * Paper reports the exact location the player jumped from. Preserve it
     * until the next jump or until the player's movement context is reset.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onJump(PlayerJumpEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (isFrozen(id, System.currentTimeMillis())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordJumpOrigin(PlayerJumpEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastJumpOrigin.put(id, event.getFrom().clone());
        lastJumpAt.put(id, System.currentTimeMillis());
    }

    /**
     * During the freeze, reject every positional delta, including sub-block
     * movement. Rotation is retained so the player can still look around.
     * Teleports are handled separately and are not treated as ordinary moves.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void enforceFreeze(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) {
            return;
        }

        UUID id = event.getPlayer().getUniqueId();
        if (!isFrozen(id, System.currentTimeMillis())) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()) {
            return;
        }

        boolean positionChanged = Double.compare(from.getX(), to.getX()) != 0
                || Double.compare(from.getY(), to.getY()) != 0
                || Double.compare(from.getZ(), to.getZ()) != 0;
        if (!positionChanged) {
            return;
        }

        Location locked = from.clone();
        locked.setYaw(to.getYaw());
        locked.setPitch(to.getPitch());
        event.setTo(locked);
    }

    private boolean isFrozen(UUID id, long now) {
        Long until = frozenUntil.get(id);
        if (until == null) {
            return false;
        }
        if (now >= until) {
            frozenUntil.remove(id, until);
            return false;
        }
        return true;
    }

    private void scheduleHardSetback(Player player) {
        UUID id = player.getUniqueId();
        if (!pendingSetbacks.add(id)) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            pendingSetbacks.remove(id);
            Player online = Bukkit.getPlayer(id);
            if (online == null || !online.isOnline()
                    || !plugin.getConfig().getBoolean("checks.motion.enabled", true)
                    || !plugin.getConfig().getBoolean("checks.motion.setback.enabled", true)) {
                return;
            }

            // Do not fight expected server-side movement modes.
            if (online.isInsideVehicle() || online.isFlying() || online.isGliding()
                    || online.isSwimming() || online.isRiptiding()
                    || online.getGameMode() == org.bukkit.GameMode.CREATIVE
                    || online.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                return;
            }

            long now = System.currentTimeMillis();
            long velocityAt = lastVelocityAt.getOrDefault(id, 0L);
            long velocityGrace = Math.max(0L,
                    plugin.getConfig().getLong("checks.motion.velocity-grace-ms", 1200L));
            if (velocityAt > 0L && now - velocityAt <= velocityGrace) {
                return;
            }

            Location correction = lastJumpOrigin.get(id);
            long jumpAt = lastJumpAt.getOrDefault(id, 0L);
            long jumpMaxAge = Math.max(1L,
                    plugin.getConfig().getLong("checks.motion.setback.jump-origin-max-age-ms", 3000L));
            if (correction == null || now - jumpAt > jumpMaxAge
                    || correction.getWorld() == null
                    || correction.getWorld() != online.getWorld()) {
                return;
            }

            correction = correction.clone();
            Location current = online.getLocation();
            correction.setYaw(current.getYaw());
            correction.setPitch(current.getPitch());

            applyingSetbacks.add(id);
            boolean applied;
            try {
                applied = online.teleport(correction, PlayerTeleportEvent.TeleportCause.PLUGIN);
            } finally {
                applyingSetbacks.remove(id);
            }

            if (applied) {
                long freezeDuration = Math.max(1L,
                        plugin.getConfig().getLong("checks.motion.setback.freeze-duration-ms", 1500L));
                frozenUntil.put(id, System.currentTimeMillis() + freezeDuration);
                online.setVelocity(new Vector(0.0, 0.0, 0.0));
                lastJumpOrigin.remove(id);
                lastJumpAt.remove(id);
            } else {
                plugin.getLogger().fine("Motion hard setback could not be applied to "
                        + online.getName() + ".");
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onVelocity(PlayerVelocityEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (isFrozen(id, System.currentTimeMillis())) {
            event.setVelocity(new Vector(0.0, 0.0, 0.0));
            return;
        }
        lastVelocityAt.put(id, System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (applyingSetbacks.contains(id)) {
            return;
        }
        clear(id);
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
        lastJumpOrigin.remove(id);
        lastJumpAt.remove(id);
        frozenUntil.remove(id);
        pendingSetbacks.remove(id);
        applyingSetbacks.remove(id);
    }

    public void clearAll() {
        suspicionTimes.clear();
        reviewUntil.clear();
        lastVelocityAt.clear();
        lastJumpOrigin.clear();
        lastJumpAt.clear();
        frozenUntil.clear();
        pendingSetbacks.clear();
        applyingSetbacks.clear();
    }
}
