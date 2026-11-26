package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NoFall detection based on the server-observed fall trajectory and the
 * expected vanilla FALL damage event. Client onGround packets are recorded
 * as supporting evidence, but never used as the sole detection signal.
 */
public final class NoFallCheck implements Listener {
    private static final long LANDING_CONFIRM_TICKS = 3L;

    private final KeenAC plugin;
    private final Map<UUID, FallEpisode> episodes = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> violations = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastSafePositions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSafePositionAt = new ConcurrentHashMap<>();
    private final Map<UUID, Double> lastSafeY = new ConcurrentHashMap<>();
    private final Set<UUID> applyingSetbacks = ConcurrentHashMap.newKeySet();

    private volatile boolean enabled = true;
    private volatile double minimumFallDistance = 4.0;
    private volatile boolean setbackEnabled = true;
    private volatile long safePositionMaxAgeMillis = 30_000L;
    private volatile boolean damageEnabled = true;
    private volatile long packetWindowNanos = 1_000_000_000L;
    private volatile int requiredGroundPackets = 3;
    private volatile int maxPingMs = 250;

    public NoFallCheck(KeenAC plugin) {
        this.plugin = plugin;
    }

    /**
     * Called once per server tick. The fall origin is preserved across the
     * entire airborne interval; landing does not overwrite it before review.
     */
    public void tick() {
        enabled = plugin.getConfig().getBoolean("checks.no-fall.enabled", true);
        // Never let the configured threshold drop below four blocks.
        minimumFallDistance = Math.max(4.0,
                plugin.getConfig().getDouble("checks.no-fall.minimum-fall-distance", 4.0));
        setbackEnabled = plugin.getConfig().getBoolean("checks.no-fall.setback.enabled", true);
        safePositionMaxAgeMillis = Math.max(1000L,
                plugin.getConfig().getLong("checks.no-fall.setback.safe-position-max-age-ms", 30000L));
        damageEnabled = plugin.getConfig().getBoolean("checks.no-fall.damage.enabled", true);
        packetWindowNanos = Math.max(250L,
                plugin.getConfig().getLong("checks.no-fall.packet-window-ms", 1000L))
                * 1_000_000L;
        requiredGroundPackets = Math.max(2,
                plugin.getConfig().getInt("checks.no-fall.required-ground-packets", 3));
        maxPingMs = Math.max(0,
                plugin.getConfig().getInt("checks.no-fall.max-ping-ms", 250));

        if (!enabled) {
            episodes.clear();
            return;
        }

        long now = System.nanoTime();
        Set<UUID> online = Bukkit.getOnlinePlayers().stream()
                .map(Player::getUniqueId)
                .collect(java.util.stream.Collectors.toSet());

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            Location location = player.getLocation();
            World world = location.getWorld();
            boolean exemptMode = !player.isOnline() || !player.isValid() || player.isDead()
                    || player.isInsideVehicle() || player.isFlying() || player.isGliding()
                    || player.isSwimming() || player.isRiptiding() || player.isClimbing()
                    || player.isInWater() || player.isInLava()
                    || player.getGameMode() == GameMode.CREATIVE
                    || player.getGameMode() == GameMode.SPECTATOR
                    || player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING)
                    || player.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION);

            FallEpisode episode = episodes.computeIfAbsent(id, ignored -> new FallEpisode());
            if (exemptMode) {
                synchronized (episode) {
                    episode.resetTracking();
                }
                continue;
            }

            boolean supported = hasSolidSupport(player);
            boolean safeLanding = supported && isSafeLandingSurface(location);

            synchronized (episode) {
                if (episode.world != null && episode.world != world) {
                    episode.resetTracking();
                }
                if (episode.falling && episode.world == null) {
                    episode.world = world;
                }

                if (!supported) {
                    if (!episode.falling && !episode.landingPending) {
                        Location safe = lastSafePositions.get(id);
                        double estimatedStartY = safe != null && safe.getWorld() == world
                                ? safe.getY()
                                : location.getY() + Math.max(0.0, player.getFallDistance());

                        episode.episodeId++;
                        episode.world = world;
                        episode.falling = true;
                        episode.fallDamageSeen = false;
                        episode.fallStartY = estimatedStartY;
                        episode.peakY = Math.max(estimatedStartY,
                                episode.hasPacketY ? episode.packetPeakY : location.getY());
                        episode.lowestY = Math.min(location.getY(),
                                episode.hasPacketY ? episode.packetLowestY : location.getY());
                        episode.startedAtNanos = now;
                        episode.safePosition = safe == null ? null : safe.clone();
                        episode.safePositionAtMillis = lastSafePositionAt.getOrDefault(id, 0L);
                    } else if (episode.falling) {
                        if (episode.safePosition == null) {
                            episode.safePosition = copyLocation(lastSafePositions.get(id));
                            episode.safePositionAtMillis = lastSafePositionAt.getOrDefault(id, 0L);
                        }
                        episode.peakY = Math.max(episode.peakY, location.getY());
                        episode.lowestY = Math.min(episode.lowestY, location.getY());
                    }

                    if (episode.falling && player.getPing() <= maxPingMs) {
                        double measuredFall = Math.max(0.0,
                                episode.peakY - Math.min(episode.lowestY, location.getY()));
                        evaluateGroundClaims(player, episode, measuredFall, now);
                    }
                    continue;
                }

                if (episode.falling) {
                    double measuredFall = Math.max(
                            player.getFallDistance(),
                            Math.max(0.0, episode.peakY - location.getY()));
                    if (player.getPing() <= maxPingMs) {
                        evaluateGroundClaims(player, episode, measuredFall, now);
                    }
                    episode.falling = false;

                    if (!safeLanding && measuredFall >= minimumFallDistance - 0.05) {
                        episode.landingPending = true;
                        episode.landingFallDistance = measuredFall;
                        episode.landingLocation = location.clone();
                        // Keep the fall episode token stable so a FALL event
                        // queued in this same tick can still be correlated.
                        long token = episode.episodeId;
                        Location safe = episode.safePosition == null
                                ? copyLocation(lastSafePositions.get(id))
                                : episode.safePosition.clone();
                        long safeAt = episode.safePositionAtMillis;
                        World landingWorld = world;

                        Bukkit.getScheduler().runTaskLater(plugin, () -> confirmLanding(
                                id, token, landingWorld, measuredFall, safe, safeAt, now),
                                LANDING_CONFIRM_TICKS);
                    } else {
                        episode.resetTracking();
                    }
                }

                // Do not replace the fall origin while a landing is awaiting
                // the vanilla FALL event.
                if (!episode.landingPending) {
                    lastSafePositions.put(id, location.clone());
                    lastSafePositionAt.put(id, System.currentTimeMillis());
                    lastSafeY.put(id, location.getY());
                    episode.packetPeakY = location.getY();
                    episode.packetLowestY = location.getY();
                    episode.hasPacketY = false;
                    episode.world = world;
                }
            }
        }

        episodes.keySet().removeIf(id -> !online.contains(id));
        violations.keySet().removeIf(id -> !online.contains(id));
        lastSafePositions.keySet().removeIf(id -> !online.contains(id));
        lastSafePositionAt.keySet().removeIf(id -> !online.contains(id));
    }

    /**
     * PacketEvents supplies client ground claims and Y coordinates. Repeated
     * ground claims are checked against the independent server fall trajectory
     * on the main thread before they can raise a violation.
     */
    public void recordPacketOnGround(UUID id, boolean clientOnGround,
                                     boolean hasPosition, double packetY) {
        if (!enabled || id == null) {
            return;
        }
        FallEpisode episode = episodes.computeIfAbsent(id, ignored -> new FallEpisode());
        synchronized (episode) {
            episode.lastClientOnGround = clientOnGround;
            long packetNow = System.nanoTime();
            if (hasPosition && Double.isFinite(packetY)) {
                if (!episode.hasPacketY) {
                    episode.packetPeakY = packetY;
                    episode.packetLowestY = packetY;
                    episode.hasPacketY = true;
                } else {
                    episode.packetPeakY = Math.max(episode.packetPeakY, packetY);
                    episode.packetLowestY = Math.min(episode.packetLowestY, packetY);
                }

                // Detect the start of descent directly from packet Y. This is
                // independent of the client onGround bit, including when it is
                // spoofed true throughout the fall.
                if (!episode.falling && !episode.landingPending) {
                    Double groundedY = lastSafeY.get(id);
                    if (groundedY != null && packetY < groundedY - 0.08) {
                        episode.episodeId++;
                        episode.world = null; // filled from Bukkit on the next tick
                        episode.falling = true;
                        episode.fallDamageSeen = false;
                        episode.fallStartY = groundedY;
                        episode.peakY = Math.max(groundedY, episode.packetPeakY);
                        episode.lowestY = Math.min(groundedY, episode.packetLowestY);
                        episode.safePosition = null; // Bukkit Location captured on the main thread
                        episode.safePositionAtMillis = lastSafePositionAt.getOrDefault(id, 0L);
                        episode.startedAtNanos = System.nanoTime();
                    }
                } else if (episode.falling) {
                    episode.peakY = Math.max(episode.peakY, packetY);
                    episode.lowestY = Math.min(episode.lowestY, packetY);
                }
            }
            if (episode.falling && clientOnGround) {
                episode.totalClientGroundClaims = Math.min(Integer.MAX_VALUE,
                        episode.totalClientGroundClaims + 1);
                if (episode.firstClientGroundClaimNanos == 0L
                        || packetNow - episode.firstClientGroundClaimNanos > packetWindowNanos) {
                    episode.firstClientGroundClaimNanos = packetNow;
                    episode.clientGroundClaims = 1;
                } else {
                    episode.clientGroundClaims = Math.min(Integer.MAX_VALUE,
                            episode.clientGroundClaims + 1);
                }
            } else if (episode.falling) {
                episode.firstClientGroundClaimNanos = 0L;
                episode.clientGroundClaims = 0;
            }
        }
    }

    /**
     * A genuine vanilla fall event is the strongest evidence that damage was
     * not suppressed. Cancelled events are also recorded to avoid penalizing
     * region-protection or other plugins that intentionally cancel damage.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onFallDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }

        FallEpisode episode = episodes.get(player.getUniqueId());
        if (episode == null) {
            return;
        }
        // If Bukkit applied real damage, do not add a second hit even if a
        // spoofed ground packet caused the event slightly before physical contact.
        if (!event.isCancelled() && event.getFinalDamage() > 0.0) {
            synchronized (episode) {
                if (episode.falling || episode.landingPending) {
                    episode.fallDamageSeen = true;
                }
            }
            return;
        }

        // Region/protection plugins may intentionally cancel FALL. Treat that
        // as a normal landing only when physical support is confirmed next tick.
        long token;
        synchronized (episode) {
            if (!episode.falling && !episode.landingPending) {
                return;
            }
            token = episode.episodeId;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player current = Bukkit.getPlayer(player.getUniqueId());
            if (current == null || !current.isOnline() || !hasSolidSupport(current)) {
                return;
            }
            synchronized (episode) {
                if (episode.episodeId == token
                        && (episode.falling || episode.landingPending)) {
                    episode.fallDamageSeen = true;
                }
            }
        }, 1L);
    }

    private void confirmLanding(UUID id, long episodeId, World landingWorld,
                                double measuredFallDistance, Location safePosition,
                                long safePositionAtMillis, long landingAtNanos) {
        Player player = Bukkit.getPlayer(id);
        FallEpisode episode = episodes.get(id);
        if (player == null || !player.isOnline() || player.isDead() || episode == null
                || !plugin.getConfig().getBoolean("checks.no-fall.enabled", true)
                || player.getWorld() != landingWorld
                || player.isInsideVehicle() || player.isFlying() || player.isGliding()
                || player.isSwimming() || player.isRiptiding() || player.isClimbing()
                || player.isInWater() || player.isInLava() || !hasSolidSupport(player)
                || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR
                || player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING)
                || player.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION)
                || isSafeLandingSurface(player.getLocation())) {
            clearPending(episode, episodeId);
            return;
        }

        int vl;
        double finalFallDistance;
        synchronized (episode) {
            if (episode.episodeId != episodeId || !episode.landingPending) {
                return;
            }

            boolean vanillaDamageObserved = episode.fallDamageSeen;
            if (vanillaDamageObserved) {
                episode.resetTracking();
                lastSafePositions.put(id, player.getLocation().clone());
                lastSafePositionAt.put(id, System.currentTimeMillis());
                return;
            }

            // Packet ground claims are only preliminary evidence. A fall is
            // not a NoFall violation unless suspicious claims were observed
            // during the airborne interval AND normal FALL damage did not occur.
            // This avoids flagging legitimate falls due to occasional packet
            // desynchronization or ground-bit noise.
            if (!episode.suspicionFlagged) {
                episode.resetTracking();
                lastSafePositions.put(id, player.getLocation().clone());
                lastSafePositionAt.put(id, System.currentTimeMillis());
                return;
            }

            // Use the complete server-observed drop, not the client's
            // fallDistance field, which the NoFall modification may spoof.
            double serverDrop = safePosition != null && safePosition.getWorld() == landingWorld
                    ? Math.max(0.0, safePosition.getY() - landingPositionY(episode, measuredFallDistance))
                    : measuredFallDistance;
            finalFallDistance = Math.max(measuredFallDistance, serverDrop);

            // Do not flag a drop that is non-damaging under this player's
            // SAFE_FALL_DISTANCE / FALL_DAMAGE_MULTIPLIER attributes.
            if (calculateExpectedFallDamage(player, finalFallDistance) <= 0.0) {
                episode.resetTracking();
                lastSafePositions.put(id, player.getLocation().clone());
                lastSafePositionAt.put(id, System.currentTimeMillis());
                return;
            }

            episode.landingPending = false;
            episode.falling = false;
            episode.violationHandled = true;
            int increment = episode.suspicionFlagged ? 2 : 1;
            vl = violations.merge(id, increment, (current, add) ->
                    current > Integer.MAX_VALUE - add ? Integer.MAX_VALUE : current + add);
        }

        boolean setbackApplied = applySetback(player, safePosition, safePositionAtMillis);
        double appliedDamage = applyFallDamage(player, finalFallDistance);

        plugin.getAlertManager().send("NoFall", player, vl);
        if (plugin.getConfig().getBoolean("checks.no-fall.log-context", true)) {
            plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                    "NoFall confirmed for %s (VL=%d, fallDistance=%.2f, airborneGroundClaims=%d, setback=%s, damage=%.1f)",
                    player.getName(), vl, finalFallDistance, episode.totalClientGroundClaims,
                    setbackApplied, appliedDamage));
        }

        synchronized (episode) {
            episode.resetTracking();
        }
    }

    private void evaluateGroundClaims(Player player, FallEpisode episode,
                                      double measuredFall, long now) {
        if (measuredFall < minimumFallDistance - 0.05
                || episode.clientGroundClaims < requiredGroundPackets
                || episode.firstClientGroundClaimNanos == 0L
                || now - episode.firstClientGroundClaimNanos > packetWindowNanos) {
            return;
        }

        // Do not alert or increase VL while the player is still falling.
        // Legitimate clients can occasionally report onGround during packet
        // desynchronization. Remember the evidence and decide only after
        // landing, when the expected vanilla FALL event can be checked.
        episode.suspicionFlagged = true;
    }

    private double landingPositionY(FallEpisode episode, double fallback) {
        return episode.landingLocation == null ? fallback : episode.landingLocation.getY();
    }

    private void clearPending(FallEpisode episode, long episodeId) {
        if (episode == null) {
            return;
        }
        synchronized (episode) {
            if (episode.episodeId == episodeId) {
                episode.resetTracking();
            }
        }
    }

    private boolean applySetback(Player player, Location safe, long savedAt) {
        if (!setbackEnabled || !plugin.getConfig().getBoolean("checks.no-fall.setback.enabled", true)) {
            return false;
        }
        UUID id = player.getUniqueId();
        if (safe == null || safe.getWorld() == null || safe.getWorld() != player.getWorld()
                || savedAt <= 0L || System.currentTimeMillis() - savedAt > safePositionMaxAgeMillis) {
            return false;
        }

        Location target = safe.clone();
        Location current = player.getLocation();
        target.setYaw(current.getYaw());
        target.setPitch(current.getPitch());
        boolean applied;
        applyingSetbacks.add(id);
        try {
            applied = player.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            applyingSetbacks.remove(id);
        }
        if (applied) {
            player.setFallDistance(0.0f);
            player.setVelocity(new Vector(0.0, 0.0, 0.0));
        }
        return applied;
    }

    /**
     * Reapply the server's fall damage type once after a confirmed suppression.
     * The safe-fall and damage-multiplier attributes are used before dispatching
     * a genuine FALL damage source, preserving normal protection/enchantment handling.
     */
    private double applyFallDamage(Player player, double fallDistance) {
        if (!damageEnabled || !plugin.getConfig().getBoolean("checks.no-fall.damage.enabled", true)
                || player.isDead() || !Double.isFinite(fallDistance)) {
            return 0.0;
        }

        double damage = calculateExpectedFallDamage(player, fallDistance);
        if (damage <= 0.0) {
            return 0.0;
        }

        player.damage(damage, DamageSource.builder(DamageType.FALL).build());
        return damage;
    }

    private double calculateExpectedFallDamage(Player player, double fallDistance) {
        var safeFallAttribute = player.getAttribute(Attribute.SAFE_FALL_DISTANCE);
        var multiplierAttribute = player.getAttribute(Attribute.FALL_DAMAGE_MULTIPLIER);
        double safeFallDistance = safeFallAttribute == null ? 3.0 : safeFallAttribute.getValue();
        double damageMultiplier = multiplierAttribute == null ? 1.0 : multiplierAttribute.getValue();
        return Math.ceil(Math.max(0.0, fallDistance - safeFallDistance)
                * Math.max(0.0, damageMultiplier));
    }

    /**
     * Independent collision-shape support probe. This does not trust the
     * client's onGround flag or Player#isOnGround.
     */
    private boolean hasSolidSupport(Player player) {
        BoundingBox box = player.getBoundingBox();
        double feetY = box.getMinY();

        int minX = floor(box.getMinX() + 0.01);
        int maxX = floor(box.getMaxX() - 0.01);
        int minY = floor(feetY - 0.16) - 1;
        int maxY = floor(feetY + 0.03);
        int minZ = floor(box.getMinZ() + 0.01);
        int maxZ = floor(box.getMaxZ() - 0.01);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = player.getWorld().getBlockAt(x, y, z);
                    for (BoundingBox shape : block.getBlockData()
                            .getCollisionShape(block.getLocation()).getBoundingBoxes()) {
                        double top = shape.getMaxY();
                        // Support must be an actual top face immediately below
                        // the player's feet; touching the side of a wall is not support.
                        if (top > feetY + 0.03 || top < feetY - 0.16) {
                            continue;
                        }
                        boolean overlapsX = shape.getMaxX() > box.getMinX() + 0.01
                                && shape.getMinX() < box.getMaxX() - 0.01;
                        boolean overlapsZ = shape.getMaxZ() > box.getMinZ() + 0.01
                                && shape.getMinZ() < box.getMaxZ() - 0.01;
                        if (overlapsX && overlapsZ) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private int floor(double value) {
        return (int) Math.floor(value);
    }

    /** Only inspect the actual landing block, not blocks several blocks below. */
    private boolean isSafeLandingSurface(Location location) {
        Block feet = location.getBlock();
        Block support = location.clone().subtract(0.0, 0.08, 0.0).getBlock();
        return isFallDamageExemptBlock(feet)
                || isFallDamageExemptBlock(support);
    }

    private boolean isFallDamageExemptBlock(Block block) {
        Material type = block.getType();
        return block.isLiquid()
                || (block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged())
                || isFallDamageExemptMaterial(type);
    }

    private boolean isFallDamageExemptMaterial(Material material) {
        String name = material.name();
        return material == Material.WATER || material == Material.LAVA
                || name.equals("BUBBLE_COLUMN")
                || name.contains("SLIME")
                || name.contains("HONEY")
                || name.contains("COBWEB")
                || name.equals("WEB")
                || name.contains("POWDER_SNOW")
                || name.equals("HAY_BLOCK")
                || name.endsWith("_BED")
                || name.equals("BED")
                || name.equals("SCAFFOLDING")
                || name.contains("VINE")
                || name.equals("LADDER")
                || name.contains("DRIPLEAF");
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (applyingSetbacks.contains(id)) {
            return;
        }
        clear(id);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    public void clear(UUID id) {
        episodes.remove(id);
        violations.remove(id);
        lastSafePositions.remove(id);
        lastSafePositionAt.remove(id);
        lastSafeY.remove(id);
        applyingSetbacks.remove(id);
    }

    public void clearAll() {
        episodes.clear();
        violations.clear();
        lastSafePositions.clear();
        lastSafePositionAt.clear();
        lastSafeY.clear();
        applyingSetbacks.clear();
    }

    private static Location copyLocation(Location location) {
        return location == null ? null : location.clone();
    }

    private static final class FallEpisode {
        private long episodeId;
        private long startedAtNanos;
        private long lastPacketNanos;
        private long firstClientGroundClaimNanos;
        private long safePositionAtMillis;
        private long lastAlertNanos;
        private double fallStartY;
        private double peakY;
        private double lowestY;
        private double landingFallDistance;
        private double packetPeakY;
        private double packetLowestY;
        private boolean falling;
        private boolean landingPending;
        private boolean fallDamageSeen;
        private boolean violationHandled;
        private boolean lastClientOnGround;
        private boolean hasPacketY;
        private int totalClientGroundClaims;
        private int clientGroundClaims;
        private int nextSuspicionClaim;
        private boolean suspicionFlagged;
        private World world;
        private Location safePosition;
        private Location landingLocation;

        private void resetTracking() {
            episodeId++;
            startedAtNanos = 0L;
            fallStartY = 0.0;
            peakY = 0.0;
            lowestY = 0.0;
            landingFallDistance = 0.0;
            packetPeakY = 0.0;
            packetLowestY = 0.0;
            falling = false;
            landingPending = false;
            fallDamageSeen = false;
            violationHandled = false;
            hasPacketY = false;
            totalClientGroundClaims = 0;
            clientGroundClaims = 0;
            firstClientGroundClaimNanos = 0L;
            nextSuspicionClaim = 0;
            suspicionFlagged = false;
            world = null;
            safePosition = null;
            landingLocation = null;
        }
    }
}
