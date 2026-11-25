package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class AirStuckCheck {

    private static final double SUPPORT_EPSILON = 0.06;

    private final KeenAC plugin;
    private final Map<UUID, PlayerState> states = new HashMap<>();
    private final Map<UUID, Long> pearlGraceUntilTick = new HashMap<>();
    private long serverTick;

    public AirStuckCheck(KeenAC plugin) {
        this.plugin = plugin;
    }

    /**
     * Called by the PacketEvents listener on the Bukkit main thread with coordinates
     * decoded from an incoming client movement packet.
     */
    public void recordPacketPosition(Player player, Location packetLocation) {
        if (packetLocation == null || packetLocation.getWorld() == null) {
            return;
        }
        PlayerState state = states.computeIfAbsent(
                player.getUniqueId(), ignored -> new PlayerState(player.getLocation()));
        if (!state.worldId.equals(packetLocation.getWorld().getUID())) {
            state.worldId = packetLocation.getWorld().getUID();
            state.anchor = packetLocation.clone();
            state.packetPosition = packetLocation.clone();
            clearSuspicion(player, state);
            return;
        }
        state.packetPosition = packetLocation.clone();
    }

    public void tick() {
        serverTick++;

        if (!plugin.getConfig().getBoolean("checks.air-stuck.enabled", true)) {
            for (Map.Entry<UUID, PlayerState> entry : states.entrySet()) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null) {
                    clearSuspicion(player, entry.getValue());
                } else {
                    clearSuspicion(entry.getValue());
                }
            }
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            inspect(player);
        }

        pearlGraceUntilTick.keySet().removeIf(id -> Bukkit.getPlayer(id) == null
                || serverTick > pearlGraceUntilTick.getOrDefault(id, 0L));

        Iterator<UUID> iterator = states.keySet().iterator();
        while (iterator.hasNext()) {
            if (Bukkit.getPlayer(iterator.next()) == null) {
                iterator.remove();
            }
        }
    }

    private void inspect(Player player) {
        PlayerState state = states.computeIfAbsent(
                player.getUniqueId(), ignored -> new PlayerState(player.getLocation()));

        if (hasPearlGrace(player)) {
            // A pearl throw may leave the player temporarily stationary in mid-air,
            // especially when the pearl hits a nearby block or tree. Keep the normal
            // AirStuck check paused briefly, refreshing its movement baseline each tick.
            state.worldId = player.getWorld().getUID();
            Location baseline = state.packetPosition;
            state.anchor = baseline != null && baseline.getWorld() != null
                    && baseline.getWorld().getUID().equals(state.worldId)
                    ? baseline.clone() : player.getLocation().clone();
            clearSuspicion(player, state);
            return;
        }

        if (isExemptMovementState(player)) {
            state.worldId = player.getWorld().getUID();
            state.anchor = player.getLocation().clone();
            state.packetPosition = null;
            clearSuspicion(player, state);
            return;
        }

        Location current = state.packetPosition;
        if (current == null) {
            // Wait for the first decoded movement packet after join/respawn/teleport.
            state.anchor = player.getLocation().clone();
            return;
        }

        if (!state.worldId.equals(current.getWorld().getUID())) {
            state.worldId = current.getWorld().getUID();
            state.anchor = current.clone();
            clearSuspicion(player, state);
            return;
        }

        double movementTolerance = Math.max(0.001,
                plugin.getConfig().getDouble("checks.air-stuck.movement-tolerance", 0.02));
        boolean moved = state.anchor.distanceSquared(current)
                > movementTolerance * movementTolerance;

        // Grounded players must never be evaluated by AirStuck. isOnGround()
        // covers ordinary standing/walking; the collision probe is a fallback
        // for server implementations where the ground flag is briefly stale.
        if (player.isOnGround() || hasSolidSupport(player) || moved) {
            state.anchor = current.clone();
            clearSuspicion(player, state);
            return;
        }

        // Lack of position updates alone is not proof of AirStuck. Only count
        // stationary ticks while the server says the player is actively falling.
        // This avoids flags at jump apexes, during ordinary idle time and while
        // a player is merely pressed against a horizontal obstacle.
        double velocityTolerance = Math.max(0.08,
                plugin.getConfig().getDouble("checks.air-stuck.velocity-tolerance", 0.08));
        Vector velocity = player.getVelocity();
        if (velocity.getY() >= -velocityTolerance) {
            state.anchor = current.clone();
            clearSuspicion(player, state);
            return;
        }

        state.stationaryTicks++;

        int requiredTicks = Math.max(1,
                plugin.getConfig().getInt("checks.air-stuck.stationary-ticks", 10));

        if (state.stationaryTicks >= requiredTicks) {
            flag(player, state);
        }

        if (state.flagged) {
            applyShake(player, state);
        }
    }


    /**
     * Grants a short AirStuck grace period after a legitimate Ender Pearl launch.
     * The grace is intentionally time-limited and does not change accumulated VL.
     */
    public void grantPearlGrace(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        long graceTicks = Math.max(1L,
                plugin.getConfig().getLong("checks.air-stuck.pearl-grace-ticks", 80L));
        UUID id = player.getUniqueId();
        pearlGraceUntilTick.put(id, Math.max(
                pearlGraceUntilTick.getOrDefault(id, 0L),
                serverTick + graceTicks));

        PlayerState state = states.computeIfAbsent(id,
                ignored -> new PlayerState(player.getLocation()));
        state.worldId = player.getWorld().getUID();
        state.anchor = player.getLocation().clone();
        clearSuspicion(player, state);
    }

    private boolean hasPearlGrace(Player player) {
        Long untilTick = pearlGraceUntilTick.get(player.getUniqueId());
        if (untilTick == null) {
            return false;
        }
        if (serverTick > untilTick) {
            pearlGraceUntilTick.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    private boolean isExemptMovementState(Player player) {
        // OP status is intentionally not checked: operators are tested normally.
        if (player.isDead() || !player.isValid()) {
            return true;
        }

        if (player.getGameMode() == GameMode.SPECTATOR
                || player.isFlying()
                || player.isInsideVehicle()
                || player.isGliding()
                || player.isSwimming()
                || player.isClimbing()
                || player.isRiptiding()
                || player.isInWater()
                || player.isInLava()
                || player.isSleeping()) {
            return true;
        }

        return player.hasPotionEffect(PotionEffectType.LEVITATION)
                || player.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                || isInsideCobweb(player);
    }

    /**
     * A player descending through a cobweb can have almost no packet displacement
     * for several ticks. Treat any cobweb intersecting the actual player hitbox
     * as a movement exemption, and clear only the active suspicion (not the VL).
     */
    private boolean isInsideCobweb(Player player) {
        BoundingBox box = player.getBoundingBox();
        int minX = floor(box.getMinX());
        int maxX = floor(box.getMaxX() - 1.0E-6);
        int minY = floor(box.getMinY());
        int maxY = floor(box.getMaxY() - 1.0E-6);
        int minZ = floor(box.getMinZ());
        int maxZ = floor(box.getMaxZ() - 1.0E-6);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (player.getWorld().getBlockAt(x, y, z).getType() == Material.COBWEB) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasSolidSupport(Player player) {
        BoundingBox playerBox = player.getBoundingBox();
        double feetY = playerBox.getMinY();

        BoundingBox footProbe = new BoundingBox(
                playerBox.getMinX() + 0.01, feetY - SUPPORT_EPSILON, playerBox.getMinZ() + 0.01,
                playerBox.getMaxX() - 0.01, feetY + 0.001, playerBox.getMaxZ() - 0.01
        );

        int minBlockX = floor(footProbe.getMinX());
        int maxBlockX = floor(footProbe.getMaxX());
        int minBlockY = floor(footProbe.getMinY()) - 1;
        int maxBlockY = floor(footProbe.getMaxY());
        int minBlockZ = floor(footProbe.getMinZ());
        int maxBlockZ = floor(footProbe.getMaxZ());

        for (int x = minBlockX; x <= maxBlockX; x++) {
            for (int y = minBlockY; y <= maxBlockY; y++) {
                for (int z = minBlockZ; z <= maxBlockZ; z++) {
                    Block block = player.getWorld().getBlockAt(x, y, z);
                    if (block.getCollisionShape().overlaps(footProbe)) {
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

    private void flag(Player player, PlayerState state) {
        if (!state.flagged) {
            state.flagged = true;
            state.violationLevel++;
            state.nextAlertTick = serverTick;
            state.alertedDuringCurrentFlag = false;
        }

        if (serverTick < state.nextAlertTick) {
            return;
        }

        long cooldownTicks = Math.max(0L,
                plugin.getConfig().getLong("alerts.delay-seconds", 3L)) * 20L;

        if (state.hasAlerted && serverTick - state.lastAlertTick < cooldownTicks) {
            state.nextAlertTick = state.lastAlertTick + cooldownTicks;
            return;
        }

        if (state.alertedDuringCurrentFlag) {
            state.violationLevel++;
        }

        plugin.getAlertManager().send("AirStuck", player, state.violationLevel);

        state.hasAlerted = true;
        state.lastAlertTick = serverTick;
        state.nextAlertTick = serverTick + cooldownTicks;
        state.alertedDuringCurrentFlag = true;
    }

    private void applyShake(Player player, PlayerState state) {
        if (!plugin.getConfig().getBoolean("checks.air-stuck.shake-player", true)) {
            restoreShake(player, state);
            return;
        }

        if (!state.shakeActive) {
            state.shakeActive = true;
            state.shakeBaseYaw = player.getYaw();
            state.shakeBasePitch = player.getPitch();
        }

        double configuredStrength = plugin.getConfig().getDouble(
                "checks.air-stuck.shake-strength", 28.0);
        float strength = (float) Math.max(0.0, Math.min(80.0, configuredStrength));
        if (strength <= 0.0F) {
            restoreShake(player, state);
            return;
        }

        // Fast alternating yaw/pitch offsets create a strong camera shake
        // without changing the player's coordinates or affecting movement checks.
        float yawOffset = (float) (Math.sin(serverTick * 2.4) * strength);
        float pitchOffset = (float) (Math.cos(serverTick * 2.8) * strength * 0.65);
        float pitch = Math.max(-89.0F,
                Math.min(89.0F, state.shakeBasePitch + pitchOffset));

        player.setRotation(state.shakeBaseYaw + yawOffset, pitch);
    }

    private void restoreShake(Player player, PlayerState state) {
        if (!state.shakeActive) {
            return;
        }
        if (player != null && player.isOnline() && player.isValid()) {
            player.setRotation(state.shakeBaseYaw, state.shakeBasePitch);
        }
        state.shakeActive = false;
    }

    private void clearSuspicion(Player player, PlayerState state) {
        restoreShake(player, state);
        clearSuspicion(state);
    }

    private void clearSuspicion(PlayerState state) {
        state.flagged = false;
        state.stationaryTicks = 0;
        state.alertedDuringCurrentFlag = false;
        state.nextAlertTick = 0L;
        state.shakeActive = false;
    }

    public boolean isFlagged(Player player) {
        PlayerState state = states.get(player.getUniqueId());
        return state != null && state.flagged
                && plugin.getConfig().getBoolean("checks.air-stuck.cancel-actions", true);
    }

    public void reset(Player player) {
        // Keep an active pearl grace through the pearl's teleport event. The
        // grace expires on its own timer, even if the impact did not teleport.
        PlayerState state = states.remove(player.getUniqueId());
        if (state != null) {
            clearSuspicion(player, state);
        }
    }

    public void forget(UUID playerId) {
        pearlGraceUntilTick.remove(playerId);
        PlayerState state = states.remove(playerId);
        if (state != null) {
            clearSuspicion(Bukkit.getPlayer(playerId), state);
        }
    }

    public void clearAll() {
        for (Map.Entry<UUID, PlayerState> entry : states.entrySet()) {
            clearSuspicion(Bukkit.getPlayer(entry.getKey()), entry.getValue());
        }
        states.clear();
        pearlGraceUntilTick.clear();
    }

    private static final class PlayerState {
        private UUID worldId;
        private Location anchor;
        private Location packetPosition;
        private int stationaryTicks;
        private int violationLevel;
        private boolean flagged;
        private boolean hasAlerted;
        private boolean alertedDuringCurrentFlag;
        private long lastAlertTick;
        private long nextAlertTick;
        private boolean shakeActive;
        private float shakeBaseYaw;
        private float shakeBasePitch;

        private PlayerState(Location initialLocation) {
            this.worldId = initialLocation.getWorld().getUID();
            this.anchor = initialLocation.clone();
        }
    }
}
