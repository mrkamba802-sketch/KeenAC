package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.GameMode;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    private volatile boolean enabled = true;
    private volatile int maxPing = 250;
    private volatile double minimumFallDistance = 3.0;
    private volatile int requiredPackets = 2;
    private volatile long packetWindowNanos = 750_000_000L;
    private volatile long alertCooldownNanos = 2_000_000_000L;

    public NoFallCheck(KeenAC plugin) {
        this.plugin = plugin;
    }

    /** Refresh all Bukkit/world data on the server thread. */
    public void tick() {
        enabled = plugin.getConfig().getBoolean("checks.no-fall.enabled", true);
        maxPing = Math.max(50, plugin.getConfig().getInt("checks.no-fall.max-ping-ms", 250));
        minimumFallDistance = Math.max(2.0,
                plugin.getConfig().getDouble("checks.no-fall.minimum-fall-distance", 3.0));
        requiredPackets = Math.max(1,
                plugin.getConfig().getInt("checks.no-fall.required-packets", 2));
        packetWindowNanos = Math.max(250L,
                plugin.getConfig().getLong("checks.no-fall.packet-window-ms", 750L)) * 1_000_000L;
        alertCooldownNanos = Math.max(250L,
                plugin.getConfig().getLong("checks.no-fall.alert-cooldown-ms", 2000L)) * 1_000_000L;

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
            profiles.put(player.getUniqueId(), new NoFallProfile(
                    enabled && !exemptMode,
                    player.isOnGround(),
                    safeSurface,
                    player.getPing(),
                    player.getFallDistance(),
                    location.getWorld()));
        }
        profiles.keySet().removeIf(id -> !online.contains(id));
        packetStates.keySet().removeIf(id -> !online.contains(id));
        violations.keySet().removeIf(id -> !online.contains(id));
    }

    /**
     * Called for every inbound player-flying packet, including rotation-only
     * packets. A candidate is only alerted if the server has not actually
     * landed the player and the location is not a fall-damage-exempt surface.
     */
    public void recordPacketOnGround(UUID id, boolean clientOnGround) {
        NoFallProfile profile = profiles.get(id);
        if (!enabled || profile == null || !profile.eligible || profile.ping > maxPing) {
            packetStates.remove(id);
            return;
        }

        long now = System.nanoTime();
        PacketState state = packetStates.computeIfAbsent(id, ignored -> new PacketState());
        if (!clientOnGround || profile.serverOnGround || profile.safeSurface) {
            synchronized (state) {
                state.reset();
            }
            return;
        }

        boolean candidateFall = profile.fallDistance >= minimumFallDistance;
        boolean shouldConfirm = false;
        double candidateDistance = 0.0;
        synchronized (state) {
            if (state.firstClaimNanos == 0L || now - state.firstClaimNanos > packetWindowNanos) {
                state.reset();
                if (candidateFall) {
                    state.firstClaimNanos = now;
                    state.candidateFallDistance = profile.fallDistance;
                    state.claims = 1;
                }
            } else {
                // Keep the original fall-distance evidence if the server clears
                // it after processing a spoofed onGround bit.
                if (candidateFall) {
                    state.candidateFallDistance = Math.max(
                            state.candidateFallDistance, profile.fallDistance);
                }
                state.claims++;
            }

            if (state.claims >= requiredPackets
                    && state.candidateFallDistance >= minimumFallDistance
                    && now - state.lastAlertNanos >= alertCooldownNanos) {
                state.lastAlertNanos = now;
                candidateDistance = state.candidateFallDistance;
                state.resetCandidateOnly();
                shouldConfirm = true;
            }
        }

        if (shouldConfirm) {
            final double fallDistance = candidateDistance;
            Bukkit.getScheduler().runTask(plugin, () -> confirmViolation(id, fallDistance, profile.world));
        }
    }

    private void confirmViolation(UUID id, double fallDistance, org.bukkit.World packetWorld) {
        Player player = Bukkit.getPlayer(id);
        if (player == null || !player.isOnline()
                || !plugin.getConfig().getBoolean("checks.no-fall.enabled", true)
                || player.getWorld() != packetWorld
                || player.isOnGround()
                || player.isInsideVehicle() || player.isFlying() || player.isGliding()
                || player.isSwimming() || player.isRiptiding() || player.isInWater()
                || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR
                || player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING)
                || player.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION)
                || isSafeLandingSurface(player.getLocation())
                || hasImmediateSolidSupport(player.getLocation())) {
            return;
        }

        int vl = violations.merge(id, 1, (current, one) ->
                current == Integer.MAX_VALUE ? Integer.MAX_VALUE : current + 1);
        plugin.getAlertManager().send("NoFall", player, vl);
        if (plugin.getConfig().getBoolean("checks.no-fall.log-context", true)) {
            plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                    "NoFall review for %s (VL=%d, fallDistance=%.2f, clientOnGround=true while airborne)",
                    player.getName(), vl, fallDistance));
        }
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

    private boolean hasImmediateSolidSupport(Location location) {
        Material belowFeet = location.clone().subtract(0.0, 0.08, 0.0).getBlock().getType();
        return belowFeet.isSolid() && !isFallDamageExemptMaterial(belowFeet);
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
        clear(event.getPlayer().getUniqueId());
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
    }

    public void clearAll() {
        profiles.clear();
        packetStates.clear();
        violations.clear();
    }

    private record NoFallProfile(
            boolean eligible,
            boolean serverOnGround,
            boolean safeSurface,
            int ping,
            double fallDistance,
            org.bukkit.World world) { }

    private static final class PacketState {
        private long firstClaimNanos;
        private long lastAlertNanos;
        private double candidateFallDistance;
        private int claims;

        private void reset() {
            firstClaimNanos = 0L;
            candidateFallDistance = 0.0;
            claims = 0;
        }

        private void resetCandidateOnly() {
            firstClaimNanos = 0L;
            candidateFallDistance = 0.0;
            claims = 0;
        }
    }
}
