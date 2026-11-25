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
import org.bukkit.event.inventory.InventoryType;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AirStuckCheck {

    private static final double SUPPORT_EPSILON = 0.06;

    private final KeenAC plugin;
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final Map<UUID, PacketPosition> packetPositions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pearlGraceUntilTick = new HashMap<>();
    private long serverTick;

    public AirStuckCheck(KeenAC plugin) {
        this.plugin = plugin;
    }

    /**
     * PacketEvents callback. Stores only primitive packet coordinates because this
     * method runs on the network thread; Bukkit world/location access stays on tick().
     */
    public void recordPacketPosition(UUID playerId, double x, double y, double z) {
        packetPositions.put(playerId, new PacketPosition(x, y, z));
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

        UUID currentWorldId = player.getWorld().getUID();
        if (!state.worldId.equals(currentWorldId)) {
            state.worldId = currentWorldId;
            state.anchor = player.getLocation().clone();
            state.packetPosition = null;
            packetPositions.remove(player.getUniqueId());
            clearSuspicion(player, state);
            return;
        }

        PacketPosition packet = packetPositions.get(player.getUniqueId());
        if (packet != null) {
            state.packetPosition = new Location(player.getWorld(), packet.x, packet.y, packet.z);
        }

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
        if (player.isOnGround() || hasSolidSupport(player) || moved
                || player.getOpenInventory().getType() != InventoryType.CRAFTING) {
            state.anchor = current.clone();
            clearSuspicion(player, state);
            return;
        }

        // A missing position update is not proof by itself. Require a meaningful
        // downward velocity, a clear movement path, a stable connection and a
        // longer confirmation interval before declaring a real air freeze.
        int maxPing = Math.max(100,
                plugin.getConfig().getInt("checks.air-stuck.max-ping-ms", 250));
        if (player.getPing() > maxPing) {
            state.anchor = current.clone();
            clearSuspicion(player, state);
            return;
        }

        double velocityTolerance = Math.max(0.15,
                plugin.getConfig().getDouble("checks.air-stuck.velocity-tolerance", 0.18));
        Vector velocity = player.getVelocity();
        if (velocity.getY() >= -velocityTolerance || isMovementPathBlocked(player, velocity)) {
            state.anchor = current.clone();
            clearSuspicion(player, state);
            return;
        }

        state.stationaryTicks++;

        int requiredTicks = Math.max(30,
                plugin.getConfig().getInt("checks.air-stuck.stationary-ticks", 40));

        if (state.stationaryTicks >= requiredTicks) {
            state.confirmationSamples++;
            int requiredConfirmSamples = Math.max(1,
                    plugin.getConfig().getInt("checks.air-stuck.confirm-samples", 5));
            if (state.confirmationSamples >= requiredConfirmSamples) {
                flag(player, state);
            }
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

        long graceTicks = Math.max(200L,
                plugin.getConfig().getLong("checks.air-stuck.pearl-grace-ticks", 200L));
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

    /**
     * Returns true when the next server-velocity displacement would intersect
     * a block collision shape. A player pressed against terrain is not frozen.
     */
    private boolean isMovementPathBlocked(Player player, Vector velocity) {
        double dx = Math.max(-1.25, Math.min(1.25, velocity.getX()));
        double dy = Math.max(-1.25, Math.min(1.25, velocity.getY()));
        double dz = Math.max(-1.25, Math.min(1.25, velocity.getZ()));
        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) < 1.0E-5) {
            return false;
        }

        BoundingBox box = player.getBoundingBox();
        BoundingBox expected = new BoundingBox(
                box.getMinX() + dx, box.getMinY() + dy, box.getMinZ() + dz,
                box.getMaxX() + dx, box.getMaxY() + dy, box.getMaxZ() + dz
        );

        int minX = floor(expected.getMinX());
        int maxX = floor(expected.getMaxX() - 1.0E-6);
        int minY = floor(expected.getMinY());
        int maxY = floor(expected.getMaxY() - 1.0E-6);
        int minZ = floor(expected.getMinZ());
        int maxZ = floor(expected.getMaxZ() - 1.0E-6);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = player.getWorld().getBlockAt(x, y, z);
                    if (block.getCollisionShape().overlaps(expected)) {
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

    private void clearSuspicion(Player player, PlayerState state) {
        // AirStuck is alert-only. It must never change player rotation or input.
        clearSuspicion(state);
    }

    private void clearSuspicion(PlayerState state) {
        state.flagged = false;
        state.stationaryTicks = 0;
        state.confirmationSamples = 0;
        state.alertedDuringCurrentFlag = false;
        state.nextAlertTick = 0L;
    }

    /** Read-only packet-thread-safe flag lookup. */
    public boolean isFlagged(UUID playerId) {
        PlayerState state = states.get(playerId);
        return state != null && state.flagged;
    }

    public boolean isFlagged(Player player) {
        return player != null && isFlagged(player.getUniqueId());
    }

    public void reset(Player player) {
        // Keep an active pearl grace through the pearl's teleport event. The
        // grace expires on its own timer, even if the impact did not teleport.
        packetPositions.remove(player.getUniqueId());
        PlayerState state = states.remove(player.getUniqueId());
        if (state != null) {
            clearSuspicion(player, state);
        }
    }

    public void forget(UUID playerId) {
        pearlGraceUntilTick.remove(playerId);
        packetPositions.remove(playerId);
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
        packetPositions.clear();
        pearlGraceUntilTick.clear();
    }

    private record PacketPosition(double x, double y, double z) { }

    private static final class PlayerState {
        private UUID worldId;
        private Location anchor;
        private Location packetPosition;
        private int stationaryTicks;
        private int confirmationSamples;
        private int violationLevel;
        private volatile boolean flagged;
        private boolean hasAlerted;
        private boolean alertedDuringCurrentFlag;
        private long lastAlertTick;
        private long nextAlertTick;

        private PlayerState(Location initialLocation) {
            this.worldId = initialLocation.getWorld().getUID();
            this.anchor = initialLocation.clone();
        }
    }
}
