package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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
import org.bukkit.GameMode;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.util.Vector;
import org.bukkit.util.BoundingBox;

/**
 * PacketEvents NoFall signal. It compares the client's onGround bit with a
 * main-thread snapshot of the server's fall state, then confirms on the main
 * thread so a legitimate landing is not treated as spoofing.
 */
public final class NoFallCheck implements Listener {
    private final KeenAC plugin;
    private final Map<UUID, NoFallProfile> profiles = new ConcurrentHashMap<>();
    private final Map<UUID, PacketState> packetStates = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> violations = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastSafePositions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSafePositionAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFallDamageNanos = new ConcurrentHashMap<>();
    private final Set<UUID> applyingSetbacks = ConcurrentHashMap.newKeySet();

    private volatile boolean enabled = true;
    private volatile int maxPing = 250;
    private volatile double minimumFallDistance = 3.0;
    private volatile int requiredPackets = 2;
    private volatile long packetWindowNanos = 750_000_000L;
    private volatile long alertCooldownNanos = 2_000_000_000L;
    private volatile boolean setbackEnabled = true;
    private volatile long safePositionMaxAgeMillis = 30_000L;
    private volatile boolean damageEnabled = true;

    public NoFallCheck(KeenAC plugin) {
        this.plugin = plugin;
    }

    /** Refresh all Bukkit/world data on the server thread. */
    public void tick() {
        enabled = plugin.getConfig().getBoolean("checks.no-fall.enabled", true);
        maxPing = Math.max(50, plugin.getConfig().getInt("checks.no-fall.max-ping-ms", 250));
        minimumFallDistance = Math.max(2.0,
                plugin.getConfig().getDouble("checks.no-fall.minimum-fall-distance", 3.0));
        requiredPackets = Math.max(2,
                plugin.getConfig().getInt("checks.no-fall.required-packets", 2));
        packetWindowNanos = Math.max(250L,
                plugin.getConfig().getLong("checks.no-fall.packet-window-ms", 750L)) * 1_000_000L;
        alertCooldownNanos = Math.max(250L,
                plugin.getConfig().getLong("checks.no-fall.alert-cooldown-ms", 2000L)) * 1_000_000L;
        setbackEnabled = plugin.getConfig().getBoolean("checks.no-fall.setback.enabled", true);
        safePositionMaxAgeMillis = Math.max(1000L,
                plugin.getConfig().getLong("checks.no-fall.setback.safe-position-max-age-ms", 30000L));
        damageEnabled = plugin.getConfig().getBoolean("checks.no-fall.damage.enabled", true);

        if (!enabled) {
            // Do not retain pending suspicions across a disabled/re-enabled check.
            profiles.clear();
            packetStates.clear();
            return;
        }

        var online = Bukkit.getOnlinePlayers().stream()
                .map(Player::getUniqueId)
                .collect(java.util.stream.Collectors.toSet());
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location location = player.getLocation();
            boolean exemptMode = !player.isOnline() || !player.isValid() || player.isDead()
                    || player.isInsideVehicle() || player.isFlying() || player.isGliding()
                    || player.isSwimming() || player.isRiptiding() || player.isInWater()
                    || player.getGameMode() == GameMode.CREATIVE
                    || player.getGameMode() == GameMode.SPECTATOR
                    || player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING)
                    || player.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION);
            boolean safeSurface = isSafeLandingSurface(location);
            boolean physicallySupported = hasSolidSupport(player);
            UUID id = player.getUniqueId();
            if (physicallySupported && !safeSurface && !exemptMode) {
                lastSafePositions.put(id, location.clone());
                lastSafePositionAt.put(id, System.currentTimeMillis());
            }
            profiles.put(id, new NoFallProfile(
                    enabled && !exemptMode,
                    physicallySupported,
                    safeSurface,
                    player.getPing(),
                    player.getFallDistance(),
                    location.getY(),
                    location.getWorld(),
                    copyLocation(lastSafePositions.get(id)),
                    lastSafePositionAt.getOrDefault(id, 0L)));
        }
        profiles.keySet().removeIf(id -> !online.contains(id));
        packetStates.keySet().removeIf(id -> !online.contains(id));
        violations.keySet().removeIf(id -> !online.contains(id));
        lastSafePositions.keySet().removeIf(id -> !online.contains(id));
        lastSafePositionAt.keySet().removeIf(id -> !online.contains(id));
        lastFallDamageNanos.keySet().removeIf(id -> !online.contains(id));
    }

    /**
     * Called for every inbound player-flying packet, including rotation-only
     * packets. A candidate is only alerted if the server has not actually
     * landed the player and the location is not a fall-damage-exempt surface.
     */
    public void recordPacketOnGround(UUID id, boolean clientOnGround,
                                     boolean hasPosition, double packetY) {
        NoFallProfile profile = profiles.get(id);
        if (!enabled || profile == null || !profile.eligible || profile.ping > maxPing) {
            packetStates.remove(id);
            return;
        }

        long now = System.nanoTime();
        PacketState state = packetStates.computeIfAbsent(id, ignored -> new PacketState());
        boolean shouldSchedule = false;
        long episodeId = 0L;
        double candidateDistance = 0.0;
        long suspectedAtNanos = 0L;
        Location candidateSafePosition = null;
        long candidateSafePositionAt = 0L;

        synchronized (state) {
            if (profile.safeSurface) {
                state.resetForGround();
                return;
            }

            if (hasPosition && Double.isFinite(packetY)) {
                if (!state.hasPacketY) {
                    double safeY = profile.lastSafePosition != null
                            && profile.lastSafePosition.getWorld() == profile.world
                            ? profile.lastSafePosition.getY() : profile.currentY;
                    state.peakPacketY = Math.max(Math.max(packetY, safeY), profile.currentY);
                    state.lastPacketY = packetY;
                    state.hasPacketY = true;
                } else {
                    state.peakPacketY = Math.max(
                            Math.max(state.peakPacketY, packetY), profile.currentY);
                    state.lastPacketY = packetY;
                }
            } else if (state.hasPacketY) {
                state.peakPacketY = Math.max(state.peakPacketY, profile.currentY);
                state.lastPacketY = Math.min(state.lastPacketY, profile.currentY);
            }

            state.lastPacketNanos = now;
            state.lastClientOnGround = clientOnGround;

            // A grounded packet after a long descent is reviewed instead of being
            // discarded immediately. A genuine landing generates a FALL event;
            // a NoFall spoof commonly suppresses that event while asserting onGround.
            if (profile.physicallySupported && !clientOnGround) {
                state.resetForGround();
                return;
            }
            if (profile.physicallySupported && clientOnGround) {
                if (state.confirmationPending) {
                    return;
                }
                if (state.violationHandled) {
                    state.resetForGround();
                    return;
                }
                double landingDistance = observedFallDistance(profile, state);
                if (landingDistance <= minimumFallDistance) {
                    state.resetForGround();
                    return;
                }
                state.resetCandidateOnly();
                state.firstClaimNanos = now;
                state.candidateFallDistance = landingDistance;
                state.suspectedAtNanos = now;
                state.candidateSafePosition = copyLocation(profile.lastSafePosition);
                state.candidateSafePositionAt = profile.lastSafePositionAt;
                state.claims = requiredPackets;
                state.confirmationPending = true;
                episodeId = state.episodeId;
                candidateDistance = landingDistance;
                suspectedAtNanos = now;
                candidateSafePosition = copyLocation(profile.lastSafePosition);
                candidateSafePositionAt = profile.lastSafePositionAt;
                shouldSchedule = true;
            } else if (!clientOnGround) {
                // Cancel delayed punishment as soon as the client stops spoofing,
                // but keep the tracked vertical trajectory for this fall.
                state.resetCandidateOnly();
                state.confirmationPending = false;
                return;
            } else {
                // Require repeated onGround packets while the independent server
                // collision check says the player is airborne.
                if (state.violationHandled || state.confirmationPending) {
                    return;
                }

                double observedFallDistance = observedFallDistance(profile, state);
                if (observedFallDistance <= minimumFallDistance) {
                    state.resetCandidateOnly();
                    return;
                }

                if (state.firstClaimNanos == 0L
                        || now - state.firstClaimNanos > packetWindowNanos) {
                    state.resetCandidateOnly();
                    state.firstClaimNanos = now;
                    state.candidateFallDistance = observedFallDistance;
                    state.suspectedAtNanos = now;
                    state.candidateSafePosition = copyLocation(profile.lastSafePosition);
                    state.candidateSafePositionAt = profile.lastSafePositionAt;
                    state.claims = 1;
                } else {
                    state.candidateFallDistance = Math.max(
                            state.candidateFallDistance, observedFallDistance);
                    state.claims++;
                }

                if (state.claims >= requiredPackets) {
                    state.confirmationPending = true;
                    episodeId = state.episodeId;
                    candidateDistance = state.candidateFallDistance;
                    suspectedAtNanos = state.suspectedAtNanos;
                    candidateSafePosition = copyLocation(state.candidateSafePosition);
                    candidateSafePositionAt = state.candidateSafePositionAt;
                    shouldSchedule = true;
                }
            }
        }

        if (shouldSchedule) {
            final long token = episodeId;
            final double fallDistance = candidateDistance;
            final long suspectedAt = suspectedAtNanos;
            final Location safePosition = candidateSafePosition;
            final long safePositionAt = candidateSafePositionAt;
            Bukkit.getScheduler().runTaskLater(plugin, () -> confirmViolation(
                    id, token, fallDistance, profile.world, suspectedAt,
                    safePosition, safePositionAt), 2L);
        }
    }

    /**
     * Confirms one suspicion per fall episode. The client must still claim
     * onGround; damage is resolved at landing so the full fall height is used.
     */
    private void confirmViolation(UUID id, long episodeId, double fallDistance,
                                  org.bukkit.World packetWorld, long suspectedAtNanos,
                                  Location safePosition, long safePositionAtMillis) {
        Player player = Bukkit.getPlayer(id);
        PacketState state = packetStates.get(id);
        if (player == null || !player.isOnline() || state == null
                || !plugin.getConfig().getBoolean("checks.no-fall.enabled", true)
                || player.getWorld() != packetWorld
                || player.isInsideVehicle() || player.isFlying() || player.isGliding()
                || player.isSwimming() || player.isRiptiding() || player.isInWater()
                || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR
                || player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING)
                || player.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION)
                || isSafeLandingSurface(player.getLocation())) {
            cancelPending(state, episodeId);
            return;
        }

        synchronized (state) {
            if (state.episodeId != episodeId || !state.confirmationPending
                    || !state.lastClientOnGround || state.violationHandled
                    || System.nanoTime() - state.lastPacketNanos > packetWindowNanos) {
                cancelPending(state, episodeId);
                return;
            }
        }

        long now = System.nanoTime();
        long vanillaFallDamageAt = lastFallDamageNanos.getOrDefault(id, 0L);
        if (vanillaFallDamageAt >= suspectedAtNanos - 100_000_000L
                && vanillaFallDamageAt <= now) {
            synchronized (state) {
                if (state.episodeId == episodeId) {
                    state.violationHandled = true;
                    state.confirmationPending = false;
                    state.resetCandidateOnly();
                }
            }
            return;
        }

        // Keep following the vertical trajectory until it reaches a real
        // landing. This preserves the full height on large drops instead of
        // applying only the first few blocks of damage mid-fall.
        if (!hasSolidSupport(player)
                && now - suspectedAtNanos < MAX_CONFIRMATION_WAIT_NANOS) {
            synchronized (state) {
                if (state.episodeId != episodeId || !state.confirmationPending
                        || !state.lastClientOnGround || state.violationHandled) {
                    return;
                }
                double currentY = player.getLocation().getY();
                double packetDrop = state.hasPacketY
                        ? Math.max(0.0, state.peakPacketY - Math.min(state.lastPacketY, currentY))
                        : 0.0;
                state.candidateFallDistance = Math.max(state.candidateFallDistance, packetDrop);
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> confirmViolation(
                    id, episodeId, fallDistance, packetWorld, suspectedAtNanos,
                    safePosition, safePositionAtMillis), 2L);
            return;
        }

        int vl;
        double confirmedFallDistance;
        synchronized (state) {
            if (state.episodeId != episodeId || !state.confirmationPending
                    || !state.lastClientOnGround || state.violationHandled) {
                return;
            }
            double currentY = player.getLocation().getY();
            double packetDrop = state.hasPacketY
                    ? Math.max(0.0, state.peakPacketY - Math.min(state.lastPacketY, currentY))
                    : 0.0;
            double anchoredDrop = safePosition != null && safePosition.getWorld() == player.getWorld()
                    ? Math.max(0.0, safePosition.getY() - currentY) : 0.0;
            confirmedFallDistance = Math.max(Math.max(fallDistance, state.candidateFallDistance),
                    Math.max(player.getFallDistance(), Math.max(packetDrop, anchoredDrop)));
            state.confirmationPending = false;
            state.violationHandled = true;
            state.resetCandidateOnly();
            vl = violations.merge(id, 1, (current, one) ->
                    current == Integer.MAX_VALUE ? Integer.MAX_VALUE : current + 1);
        }

        boolean setbackApplied = applySetback(player, safePosition, safePositionAtMillis);
        // Damage scales with the full measured drop (vanilla formula: ceil(height - 3)).
        double appliedDamage = applyFallDamage(player, confirmedFallDistance);

        if (now - state.lastAlertNanos >= alertCooldownNanos) {
            state.lastAlertNanos = now;
            plugin.getAlertManager().send("NoFall", player, vl);
        }
        if (plugin.getConfig().getBoolean("checks.no-fall.log-context", true)) {
            plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                    "NoFall confirmed for %s (VL=%d, fallDistance=%.2f, setback=%s, damage=%.1f)",
                    player.getName(), vl, confirmedFallDistance, setbackApplied, appliedDamage));
        }
    }

    private double observedFallDistance(NoFallProfile profile, PacketState state) {
        double anchoredDrop = profile.lastSafePosition != null
                && profile.lastSafePosition.getWorld() == profile.world
                ? Math.max(0.0, profile.lastSafePosition.getY() - profile.currentY) : 0.0;
        double packetDrop = state.hasPacketY
                ? Math.max(0.0, state.peakPacketY - Math.min(state.lastPacketY, profile.currentY))
                : 0.0;
        return Math.max(profile.fallDistance, Math.max(anchoredDrop, packetDrop));
    }

    private void cancelPending(PacketState state, long episodeId) {
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.episodeId == episodeId && !state.violationHandled) {
                state.confirmationPending = false;
                state.resetCandidateOnly();
            }
        }
    }

    /**
     * Return the player to the last known safe, grounded position. Internal
     * teleports are excluded from lifecycle cleanup so the NoFall VL persists.
     */
    private boolean applySetback(Player player, Location safe, long savedAt) {
        if (!setbackEnabled || !plugin.getConfig().getBoolean("checks.no-fall.setback.enabled", true)) {
            return false;
        }
        UUID id = player.getUniqueId();
        if (safe == null || safe.getWorld() == null || safe.getWorld() != player.getWorld()
                || System.currentTimeMillis() - savedAt > safePositionMaxAgeMillis) {
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
     * Apply the vanilla-style base fall damage (one point per block beyond
     * three blocks) when a confirmed NoFall spoof prevented the fall damage.
     */
    private double applyFallDamage(Player player, double fallDistance) {
        if (!damageEnabled || !plugin.getConfig().getBoolean("checks.no-fall.damage.enabled", true)
                || player.isDead()) {
            return 0.0;
        }
        if (!Double.isFinite(fallDistance)) {
            return 0.0;
        }
        double damage = Math.ceil(Math.max(0.0, fallDistance - 3.0));
        if (damage <= 0.0) {
            return 0.0;
        }
        player.damage(damage);
        return damage;
    }

    /**
     * Detect physical block support independently of the client's onGround bit.
     * Player#isOnGround can follow movement packet state on some server paths,
     * which must not be the sole gate for a NoFall review.
     */
    private boolean hasSolidSupport(Player player) {
        BoundingBox box = player.getBoundingBox();
        double feetY = box.getMinY();
        BoundingBox probe = new BoundingBox(
                box.getMinX() + 0.01, feetY - 0.06, box.getMinZ() + 0.01,
                box.getMaxX() - 0.01, feetY + 0.001, box.getMaxZ() - 0.01);

        int minX = floor(probe.getMinX());
        int maxX = floor(probe.getMaxX());
        int minY = floor(probe.getMinY()) - 1;
        int maxY = floor(probe.getMaxY());
        int minZ = floor(probe.getMinZ());
        int maxZ = floor(probe.getMaxZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = player.getWorld().getBlockAt(x, y, z);
                    if (block.getCollisionShape().overlaps(probe)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private int floor(double value) {
        return (int) Math.floor(value);
    }

    private boolean isSafeLandingSurface(Location location) {
        // Inspect the feet block and the next two blocks below. This covers the
        // short landing window for blocks that cancel/reduce fall damage.
        for (int quarterBlocks = 0; quarterBlocks <= 8; quarterBlocks++) {
            Location probe = location.clone().subtract(0.0, quarterBlocks * 0.25, 0.0);
            Block block = probe.getBlock();
            Material type = block.getType();
            if (block.isLiquid()
                    || (block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged())
                    || isFallDamageExemptMaterial(type)) {
                return true;
            }
        }
        return false;
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player
                && event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            lastFallDamageNanos.put(player.getUniqueId(), System.nanoTime());
        }
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
        profiles.remove(id);
        packetStates.remove(id);
        violations.remove(id);
        lastSafePositions.remove(id);
        lastSafePositionAt.remove(id);
        lastFallDamageNanos.remove(id);
        applyingSetbacks.remove(id);
    }

    public void clearAll() {
        profiles.clear();
        packetStates.clear();
        violations.clear();
        lastSafePositions.clear();
        lastSafePositionAt.clear();
        lastFallDamageNanos.clear();
        applyingSetbacks.clear();
    }

    private static Location copyLocation(Location location) {
        return location == null ? null : location.clone();
    }

    private record NoFallProfile(
            boolean eligible,
            boolean physicallySupported,
            boolean safeSurface,
            int ping,
            double fallDistance,
            double currentY,
            org.bukkit.World world,
            Location lastSafePosition,
            long lastSafePositionAt) { }

    private static final long MAX_CONFIRMATION_WAIT_NANOS = 30_000_000_000L;

    private static final class PacketState {
        private long episodeId;
        private long firstClaimNanos;
        private long lastAlertNanos;
        private long lastPacketNanos;
        private double candidateFallDistance;
        private long suspectedAtNanos;
        private Location candidateSafePosition;
        private long candidateSafePositionAt;
        private int claims;
        private boolean hasPacketY;
        private double lastPacketY;
        private double peakPacketY;
        private boolean lastClientOnGround;
        private boolean confirmationPending;
        private boolean violationHandled;

        private void resetForGround() {
            episodeId++;
            resetCandidateOnly();
            hasPacketY = false;
            lastPacketY = 0.0;
            peakPacketY = 0.0;
            lastClientOnGround = false;
            confirmationPending = false;
            violationHandled = false;
            lastPacketNanos = 0L;
        }

        private void resetCandidateOnly() {
            firstClaimNanos = 0L;
            candidateFallDistance = 0.0;
            suspectedAtNanos = 0L;
            candidateSafePosition = null;
            candidateSafePositionAt = 0L;
            claims = 0;
        }
    }
}
