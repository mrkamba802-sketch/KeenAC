package dev.keenac.check;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.entity.Strider;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Packet-driven AirStuck detection.
 *
 * Packet callbacks only copy primitive packet data into a bounded queue.
 * Player and world inspection is performed by the primary-thread task.
 */
public final class AirStuckCheck extends PacketListenerAbstract {

    private static final int MAX_QUEUED_PACKETS = 12_000;
    private static final int MAX_PACKETS_PER_TICK = 3_000;
    private static final int MAX_PENDING_SERVER_TELEPORTS = 8;

    private static final double PLAYER_HALF_WIDTH = 0.30;
    private static final double SUPPORT_TOLERANCE = 0.08;
    private static final double GRAVITY_DELTA_EPSILON = 0.015;
    private static final double GRAVITY_ACCELERATION_EPSILON = 0.03;

    private final Plugin plugin;
    private final KeenAC keenAC;

    private final Map<UUID, PlayerMovementData> dataMap = new ConcurrentHashMap<>(512);
    private final Queue<PacketSnapshot> packetQueue = new ArrayDeque<>();
    private final Object queueLock = new Object();

    private boolean queueOverflow;
    private BukkitTask batchTask;

    public AirStuckCheck(Plugin plugin) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
        this.keenAC = (KeenAC) plugin;
        startBatchProcessor();
    }

    /**
     * Called from PlayerQuitEvent.
     */
    public void cleanup(UUID uuid) {
        if (uuid != null) {
            dataMap.remove(uuid);
        }
    }

    /**
     * Tracks server-originated teleports and velocity packets. A client
     * TELEPORT_CONFIRM is only accepted when a matching server teleport exists.
     */
    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!plugin.isEnabled()) return;

        Player player = (Player) event.getPlayer();
        if (player == null) return;

        var type = event.getPacketType();
        if (type == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
            enqueue(new PacketSnapshot(
                    player.getUniqueId(),
                    SnapshotType.SERVER_TELEPORT,
                    false,
                    0.0,
                    0.0,
                    0.0
            ));
        } else if (type == PacketType.Play.Server.ENTITY_VELOCITY
                || type == PacketType.Play.Server.EXPLOSION) {
            enqueue(new PacketSnapshot(
                    player.getUniqueId(),
                    SnapshotType.SERVER_VELOCITY,
                    false,
                    0.0,
                    0.0,
                    0.0
            ));
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!plugin.isEnabled()) return;

        Player player = (Player) event.getPlayer();
        if (player == null) return;

        UUID uuid = player.getUniqueId();
        var type = event.getPacketType();

        if (type == PacketType.Play.Client.TELEPORT_CONFIRM) {
            enqueue(new PacketSnapshot(
                    uuid, SnapshotType.TELEPORT_CONFIRM, false, 0.0, 0.0, 0.0));
            return;
        }

        if (type == PacketType.Play.Client.VEHICLE_MOVE) {
            enqueue(new PacketSnapshot(
                    uuid, SnapshotType.VEHICLE_MOVE, false, 0.0, 0.0, 0.0));
            return;
        }

        if (!WrapperPlayClientPlayerFlying.isFlying(type)) return;

        WrapperPlayClientPlayerFlying packet = new WrapperPlayClientPlayerFlying(event);
        boolean hasPosition = packet.hasPositionChanged();

        enqueue(new PacketSnapshot(
                uuid,
                SnapshotType.FLYING,
                hasPosition,
                hasPosition ? packet.getLocation().getX() : 0.0,
                hasPosition ? packet.getLocation().getY() : 0.0,
                hasPosition ? packet.getLocation().getZ() : 0.0
        ));
    }

    private void enqueue(PacketSnapshot snapshot) {
        synchronized (queueLock) {
            if (packetQueue.size() >= MAX_QUEUED_PACKETS) {
                packetQueue.clear();
                queueOverflow = true;
                return;
            }
            packetQueue.add(snapshot);
        }
    }

    private PacketSnapshot pollPacket() {
        synchronized (queueLock) {
            return packetQueue.poll();
        }
    }

    private boolean consumeQueueOverflow() {
        synchronized (queueLock) {
            if (!queueOverflow) return false;

            queueOverflow = false;
            packetQueue.clear();
            return true;
        }
    }

    private void startBatchProcessor() {
        batchTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!plugin.getConfig().getBoolean("checks.air-stuck.enabled", true)) {
                resetAllState();
                return;
            }

            long now = System.currentTimeMillis();

            // Once packets have been dropped, the missing sequence is not valid evidence.
            if (consumeQueueOverflow()) {
                for (PlayerMovementData data : dataMap.values()) {
                    resetEvidence(data, now, 3);
                }
                return;
            }

            int processed = 0;
            PacketSnapshot snapshot;
            while (processed++ < MAX_PACKETS_PER_TICK
                    && (snapshot = pollPacket()) != null) {
                Player player = Bukkit.getPlayer(snapshot.uuid);
                if (player != null && player.isOnline()) {
                    processPacket(player, snapshot, now);
                }
            }

            checkPacketFreeze(now);
        }, 1L, 1L);
    }

    private void checkPacketFreeze(long now) {
        long blinkTimeoutMs = Math.max(250L,
                plugin.getConfig().getLong("checks.air-stuck.blink-timeout-ms", 500L));
        int setbackConfirmations = Math.max(1,
                plugin.getConfig().getInt("checks.air-stuck.setback.confirmations", 3));
        int exemptTicksAfterSetback = Math.max(1,
                plugin.getConfig().getInt("checks.air-stuck.setback.exempt-ticks", 3));
        boolean setbackEnabled = plugin.getConfig().getBoolean(
                "checks.air-stuck.setback.enabled", true);

        for (Map.Entry<UUID, PlayerMovementData> entry : dataMap.entrySet()) {
            PlayerMovementData data = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());

            if (player == null || !player.isOnline()) {
                dataMap.remove(entry.getKey(), data);
                continue;
            }

            if (!data.hasLastPos
                    || now < data.freezeCheckExemptUntil
                    || player.isInsideVehicle()
                    || isPlayerExempt(player)
                    || now - data.lastFlyingTime <= blinkTimeoutMs) {
                continue;
            }

            World world = player.getWorld();
            if (data.lastWorld != world) {
                resetForWorldChange(data, world, now);
                continue;
            }

            if (hasPhysicalSupport(player, world, data.lastX, data.lastY, data.lastZ)) {
                data.freezePenalty = Math.max(0, data.freezePenalty - 1);
                continue;
            }

            data.freezePenalty += 2;
            data.lastFlyingTime = now;
            data.exemptTicks = exemptTicksAfterSetback;
            data.freezeCheckExemptUntil = now + Math.max(150L, exemptTicksAfterSetback * 50L);

            if (data.freezePenalty >= setbackConfirmations * 2) {
                flag(player, data.freezePenalty * 10,
                        "Packet movement stopped while airborne without physical support.");
                data.freezePenalty = Math.max(1, setbackConfirmations);
                continue;
            }

            if (setbackEnabled) {
                Location current = player.getLocation();
                Location setbackLocation = new Location(
                        world,
                        data.lastX,
                        data.lastY,
                        data.lastZ,
                        current.getYaw(),
                        current.getPitch()
                );
                player.teleport(setbackLocation);
            }
        }
    }

    private void processPacket(Player player, PacketSnapshot snapshot, long now) {
        PlayerMovementData data = dataMap.computeIfAbsent(
                snapshot.uuid, ignored -> new PlayerMovementData(player));

        switch (snapshot.type) {
            case SERVER_TELEPORT -> {
                data.pendingServerTeleports = Math.min(
                        MAX_PENDING_SERVER_TELEPORTS,
                        data.pendingServerTeleports + 1
                );
                data.lastFlyingTime = now;
                data.stuckTicks = 0;
                data.hasLastPos = false;
                data.exemptTicks = Math.max(data.exemptTicks, 4);
                data.freezeCheckExemptUntil = now + 1_000L;
                return;
            }
            case TELEPORT_CONFIRM -> {
                // Ignore unsolicited confirms; they must correspond to a server packet.
                if (data.pendingServerTeleports > 0) {
                    data.pendingServerTeleports--;
                    data.stuckTicks = 0;
                    data.freezePenalty = 0;
                    data.hasLastPos = false;
                    data.exemptTicks = 4;
                    data.freezeCheckExemptUntil = now + 500L;
                    data.lastFlyingTime = now;
                }
                return;
            }
            case SERVER_VELOCITY -> {
                data.stuckTicks = 0;
                data.lastDeltaY = 0.0;
                data.exemptTicks = Math.max(data.exemptTicks, 10);
                data.freezeCheckExemptUntil = now + 500L;
                data.lastFlyingTime = now;
                return;
            }
            case VEHICLE_MOVE -> {
                data.stuckTicks = 0;
                data.freezePenalty = 0;
                data.hasLastPos = false;
                data.exemptTicks = 5;
                data.freezeCheckExemptUntil = now + 500L;
                data.lastFlyingTime = now;
                return;
            }
            case FLYING -> {
                // Movement packet handling continues below.
            }
        }

        data.lastFlyingTime = now;

        World currentWorld = player.getWorld();
        if (data.lastWorld != currentWorld) {
            resetForWorldChange(data, currentWorld, now);
            return;
        }

        if (player.isInsideVehicle()) {
            data.stuckTicks = 0;
            data.freezePenalty = 0;
            data.hasLastPos = false;
            data.exemptTicks = 5;
            return;
        }

        if (data.exemptTicks > 0) {
            data.exemptTicks--;
            data.stuckTicks = 0;
            data.hasLastPos = false;
            return;
        }

        if (isPlayerExempt(player)) {
            data.stuckTicks = 0;
            data.freezePenalty = 0;
            data.hasLastPos = false;
            return;
        }

        // Wait for a position-bearing packet after a reset.
        if (!data.hasLastPos) {
            if (!snapshot.hasPos) return;

            data.lastX = snapshot.x;
            data.lastY = snapshot.y;
            data.lastZ = snapshot.z;
            data.lastDeltaY = 0.0;
            data.hasLastPos = true;
            return;
        }

        double currentX = snapshot.hasPos ? snapshot.x : data.lastX;
        double currentY = snapshot.hasPos ? snapshot.y : data.lastY;
        double currentZ = snapshot.hasPos ? snapshot.z : data.lastZ;

        double deltaY = currentY - data.lastY;

        data.lastX = currentX;
        data.lastY = currentY;
        data.lastZ = currentZ;

        if (hasPhysicalSupport(player, currentWorld, currentX, currentY, currentZ)) {
            data.stuckTicks = 0;
            data.freezePenalty = Math.max(0, data.freezePenalty - 1);
            data.lastDeltaY = deltaY;
            return;
        }

        // A normal airborne movement packet should show gravity-driven Y change.
        // The acceleration check also catches very small repeated Y jitter.
        boolean gravityViolation = Math.abs(deltaY) < GRAVITY_DELTA_EPSILON
                && Math.abs(deltaY - data.lastDeltaY) < GRAVITY_ACCELERATION_EPSILON;

        data.lastDeltaY = deltaY;

        if (gravityViolation) {
            data.stuckTicks++;

            int requiredStationaryTicks = Math.max(1,
                    plugin.getConfig().getInt("checks.air-stuck.stationary-ticks", 8));
            if (data.stuckTicks >= requiredStationaryTicks) {
                flag(player, data.stuckTicks,
                        "Vertical position remained unchanged in mid-air without physical support.");
            }
        } else if (data.stuckTicks > 0) {
            data.stuckTicks = Math.max(0, data.stuckTicks - 1);
        }
    }

    private void resetForWorldChange(PlayerMovementData data, World world, long now) {
        data.lastWorld = world;
        resetEvidence(data, now, 4);
    }

    private void resetEvidence(PlayerMovementData data, long now, int exemptTicks) {
        data.stuckTicks = 0;
        data.freezePenalty = 0;
        data.lastDeltaY = 0.0;
        data.hasLastPos = false;
        data.exemptTicks = exemptTicks;
        data.freezeCheckExemptUntil = now + 500L;
        data.lastFlyingTime = now;
        data.pendingServerTeleports = 0;
    }

    private boolean isPlayerExempt(Player player) {
        GameMode gameMode = player.getGameMode();
        return gameMode == GameMode.CREATIVE
                || gameMode == GameMode.SPECTATOR
                || player.getAllowFlight()
                || player.isFlying()
                || player.isGliding()
                || player.isInWater()
                || player.isInLava()
                || player.isRiptiding()
                || player.isSleeping()
                || player.isDead()
                || player.hasPotionEffect(PotionEffectType.LEVITATION)
                || player.hasPotionEffect(PotionEffectType.SLOW_FALLING);
    }

    /**
     * Checks collision geometry around the player's feet. Unloaded chunks are
     * treated as unknown support, preventing chunk borders from creating flags.
     */
    private boolean hasPhysicalSupport(
            Player player, World world, double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return true;
        }

        double playerMinX = x - PLAYER_HALF_WIDTH;
        double playerMaxX = x + PLAYER_HALF_WIDTH;
        double playerMinZ = z - PLAYER_HALF_WIDTH;
        double playerMaxZ = z + PLAYER_HALF_WIDTH;

        int minChunkX = ((int) Math.floor(playerMinX)) >> 4;
        int maxChunkX = ((int) Math.floor(playerMaxX)) >> 4;
        int minChunkZ = ((int) Math.floor(playerMinZ)) >> 4;
        int maxChunkZ = ((int) Math.floor(playerMaxZ)) >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    return true;
                }
            }
        }

        int minX = (int) Math.floor(playerMinX);
        int maxX = (int) Math.floor(playerMaxX);
        int minY = Math.max(world.getMinHeight(), (int) Math.floor(y - 2.0));
        int maxY = Math.min(
                world.getMaxHeight() - 1,
                (int) Math.floor(y + SUPPORT_TOLERANCE)
        );
        int minZ = (int) Math.floor(playerMinZ);
        int maxZ = (int) Math.floor(playerMaxZ);

        if (minY > maxY) return false;

        for (int blockX = minX; blockX <= maxX; blockX++) {
            for (int blockY = minY; blockY <= maxY; blockY++) {
                for (int blockZ = minZ; blockZ <= maxZ; blockZ++) {
                    Block block = world.getBlockAt(blockX, blockY, blockZ);
                    if (block.getType().isAir() || block.isLiquid()) continue;

                    VoxelShape shape = block.getCollisionShape();
                    for (BoundingBox collision : shape.getBoundingBoxes()) {
                        double supportY = blockY + collision.getMaxY();
                        if (supportY < y - SUPPORT_TOLERANCE
                                || supportY > y + SUPPORT_TOLERANCE) {
                            continue;
                        }

                        double collisionMinX = blockX + collision.getMinX();
                        double collisionMaxX = blockX + collision.getMaxX();
                        double collisionMinZ = blockZ + collision.getMinZ();
                        double collisionMaxZ = blockZ + collision.getMaxZ();

                        if (collisionMaxX > playerMinX
                                && collisionMinX < playerMaxX
                                && collisionMaxZ > playerMinZ
                                && collisionMinZ < playerMaxZ) {
                            return true;
                        }
                    }
                }
            }
        }

        // Some entities expose a solid top surface without a supporting block.
        BoundingBox nearbyBox = new BoundingBox(
                playerMinX,
                y - SUPPORT_TOLERANCE,
                playerMinZ,
                playerMaxX,
                y + SUPPORT_TOLERANCE,
                playerMaxZ
        );

        for (Entity entity : world.getNearbyEntities(nearbyBox)) {
            if (!(entity instanceof Boat)
                    && !(entity instanceof Minecart)
                    && !(entity instanceof Shulker)
                    && !(entity instanceof Strider)) {
                continue;
            }

            BoundingBox bounds = entity.getBoundingBox();
            if (Math.abs(bounds.getMaxY() - y) <= SUPPORT_TOLERANCE
                    && bounds.getMaxX() > playerMinX
                    && bounds.getMinX() < playerMaxX
                    && bounds.getMaxZ() > playerMinZ
                    && bounds.getMinZ() < playerMaxZ) {
                return true;
            }
        }

        return false;
    }

    private void flag(Player player, int violationLevel, String reason) {
        if (player == null || !player.isOnline()) return;

        keenAC.getAlertManager().send(
                "AirStuck",
                player,
                Math.max(1, violationLevel),
                reason
        );
    }

    private void resetAllState() {
        dataMap.clear();
        synchronized (queueLock) {
            packetQueue.clear();
            queueOverflow = false;
        }
    }

    /** Cancels the repeating task and clears queued/player state on shutdown. */
    public void clearAll() {
        if (batchTask != null) {
            batchTask.cancel();
            batchTask = null;
        }
        resetAllState();
    }

    private enum SnapshotType {
        SERVER_TELEPORT,
        SERVER_VELOCITY,
        TELEPORT_CONFIRM,
        VEHICLE_MOVE,
        FLYING
    }

    private record PacketSnapshot(
            UUID uuid,
            SnapshotType type,
            boolean hasPos,
            double x,
            double y,
            double z
    ) { }

    private static final class PlayerMovementData {
        private World lastWorld;
        private double lastX;
        private double lastY;
        private double lastZ;
        private double lastDeltaY;
        private int stuckTicks;
        private int freezePenalty;
        private int exemptTicks;
        private int pendingServerTeleports;
        private long lastFlyingTime;
        private long freezeCheckExemptUntil;
        private boolean hasLastPos;

        private PlayerMovementData(Player player) {
            this.lastWorld = player.getWorld();
            this.lastFlyingTime = System.currentTimeMillis();
        }
    }
}
