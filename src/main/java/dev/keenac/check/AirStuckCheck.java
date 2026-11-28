package dev.keenac.check;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
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
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Packet-driven AirStuck detection. Packet data is collected off-thread and
 * all Bukkit world/player inspection is performed by the primary-thread task.
 */
public final class AirStuckCheck extends PacketListenerAbstract {

    private static final int MAX_QUEUED_PACKETS = 12_000;
    private static final int MAX_PACKETS_PER_TICK = 3_000;
    private static final double PLAYER_HALF_WIDTH = 0.30;
    private static final double SUPPORT_TOLERANCE = 0.08;

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

    /** Called from PlayerQuitEvent. */
    public void cleanup(UUID uuid) {
        if (uuid == null) return;
        dataMap.remove(uuid);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        Player player = (Player) event.getPlayer();
        if (player == null || !plugin.isEnabled()) return;

        UUID uuid = player.getUniqueId();
        var type = event.getPacketType();

        if (type == PacketType.Play.Client.TELEPORT_CONFIRM) {
            enqueue(new PacketSnapshot(uuid, SnapshotType.TELEPORT_CONFIRM, false, 0.0, 0.0, 0.0));
            return;
        }

        if (type == PacketType.Play.Client.VEHICLE_MOVE) {
            enqueue(new PacketSnapshot(uuid, SnapshotType.VEHICLE_MOVE, false, 0.0, 0.0, 0.0));
            return;
        }

        if (WrapperPlayClientPlayerFlying.isFlying(type)) {
            WrapperPlayClientPlayerFlying packet = new WrapperPlayClientPlayerFlying(event);
            boolean hasPos = packet.hasPositionChanged();
            var position = packet.getLocation();

            enqueue(new PacketSnapshot(
                    uuid,
                    SnapshotType.FLYING,
                    hasPos,
                    hasPos ? position.getX() : 0.0,
                    hasPos ? position.getY() : 0.0,
                    hasPos ? position.getZ() : 0.0
            ));
        }
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
                dataMap.clear();
                synchronized (queueLock) {
                    packetQueue.clear();
                    queueOverflow = false;
                }
                return;
            }

            long now = System.currentTimeMillis();
            if (consumeQueueOverflow()) {
                // A dropped packet batch cannot be used as evidence of a freeze.
                for (PlayerMovementData data : dataMap.values()) {
                    data.stuckTicks = 0;
                    data.setbackCount = 0;
                    data.hasLastPos = false;
                    data.exemptTicks = 3;
                    data.lastFlyingTime = now;
                }
                return;
            }

            int processed = 0;
            PacketSnapshot snapshot;
            while (processed++ < MAX_PACKETS_PER_TICK && (snapshot = pollPacket()) != null) {
                Player player = Bukkit.getPlayer(snapshot.uuid);
                if (player != null && player.isOnline()) {
                    processPacket(player, snapshot);
                }
            }

            long blinkTimeoutMs = Math.max(250L,
                    plugin.getConfig().getLong("checks.air-stuck.blink-timeout-ms", 1_000L));
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
                        || data.exemptTicks > 0
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

                if (!hasPhysicalSupport(player, world, data.lastX, data.lastY, data.lastZ)) {
                    data.setbackCount++;
                    data.lastFlyingTime = now;
                    data.exemptTicks = exemptTicksAfterSetback;

                    if (data.setbackCount >= setbackConfirmations) {
                        flag(player, data.setbackCount * 20,
                                "No movement packets received while airborne without physical support.");
                    } else if (setbackEnabled) {
                        Location setbackLocation = new Location(
                                world,
                                data.lastX,
                                data.lastY,
                                data.lastZ,
                                player.getLocation().getYaw(),
                                player.getLocation().getPitch()
                        );
                        player.teleport(setbackLocation);
                    }
                }
            }
        }, 1L, 1L);
    }

    private void processPacket(Player player, PacketSnapshot snapshot) {
        long now = System.currentTimeMillis();
        PlayerMovementData data = dataMap.computeIfAbsent(
                snapshot.uuid, ignored -> new PlayerMovementData(player));

        if (snapshot.type == SnapshotType.TELEPORT_CONFIRM) {
            data.stuckTicks = 0;
            data.hasLastPos = false;
            data.exemptTicks = 3;
            data.lastFlyingTime = now;
            return;
        }

        if (snapshot.type == SnapshotType.VEHICLE_MOVE) {
            data.stuckTicks = 0;
            data.setbackCount = 0;
            data.hasLastPos = false;
            data.exemptTicks = 5;
            data.lastFlyingTime = now;
            return;
        }

        data.lastFlyingTime = now;

        World currentWorld = player.getWorld();
        if (data.lastWorld != currentWorld) {
            resetForWorldChange(data, currentWorld, now);
            return;
        }

        if (player.isInsideVehicle()) {
            data.stuckTicks = 0;
            data.setbackCount = 0;
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
            data.setbackCount = 0;
            data.hasLastPos = false;
            return;
        }

        Location fallbackLocation = player.getLocation();
        double currentX = snapshot.hasPos
                ? snapshot.x : (data.hasLastPos ? data.lastX : fallbackLocation.getX());
        double currentY = snapshot.hasPos
                ? snapshot.y : (data.hasLastPos ? data.lastY : fallbackLocation.getY());
        double currentZ = snapshot.hasPos
                ? snapshot.z : (data.hasLastPos ? data.lastZ : fallbackLocation.getZ());

        if (!data.hasLastPos) {
            data.lastX = currentX;
            data.lastY = currentY;
            data.lastZ = currentZ;
            data.hasLastPos = true;
            return;
        }

        double deltaY = Math.abs(currentY - data.lastY);

        data.lastX = currentX;
        data.lastY = currentY;
        data.lastZ = currentZ;

        // Any actual vertical movement weakens the current freeze suspicion.
        if (deltaY >= 1.0E-7) {
            if (data.stuckTicks > 0) {
                data.stuckTicks = Math.max(0, data.stuckTicks - 2);
            }
            return;
        }

        if (hasPhysicalSupport(player, currentWorld, currentX, currentY, currentZ)) {
            data.stuckTicks = 0;
            data.setbackCount = 0;
            return;
        }

        data.stuckTicks++;
        int requiredStationaryTicks = Math.max(1,
                plugin.getConfig().getInt("checks.air-stuck.stationary-ticks", 8));
        if (data.stuckTicks >= requiredStationaryTicks) {
            flag(player, data.stuckTicks,
                    "Vertical position remained unchanged in mid-air without physical support.");
        }
    }

    private void resetForWorldChange(PlayerMovementData data, World world, long now) {
        data.lastWorld = world;
        data.stuckTicks = 0;
        data.setbackCount = 0;
        data.hasLastPos = false;
        data.exemptTicks = 3;
        data.lastFlyingTime = now;
    }

    private boolean isPlayerExempt(Player player) {
        GameMode gameMode = player.getGameMode();
        return gameMode == GameMode.CREATIVE
                || gameMode == GameMode.SPECTATOR
                || player.getAllowFlight()
                || player.isFlying()
                || player.isGliding()
                || player.isRiptiding()
                || player.isSleeping()
                || player.isDead()
                || player.hasPotionEffect(PotionEffectType.LEVITATION)
                || player.hasPotionEffect(PotionEffectType.SLOW_FALLING);
    }

    /**
     * Tests actual collision geometry close to the player's feet. Unloaded
     * chunks are treated as unknown support so chunk borders cannot cause flags.
     */
    private boolean hasPhysicalSupport(Player player, World world, double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return true;

        int minChunkX = ((int) Math.floor(x - PLAYER_HALF_WIDTH)) >> 4;
        int maxChunkX = ((int) Math.floor(x + PLAYER_HALF_WIDTH)) >> 4;
        int minChunkZ = ((int) Math.floor(z - PLAYER_HALF_WIDTH)) >> 4;
        int maxChunkZ = ((int) Math.floor(z + PLAYER_HALF_WIDTH)) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) return true;
            }
        }

        double playerMinX = x - PLAYER_HALF_WIDTH;
        double playerMaxX = x + PLAYER_HALF_WIDTH;
        double playerMinZ = z - PLAYER_HALF_WIDTH;
        double playerMaxZ = z + PLAYER_HALF_WIDTH;

        int minX = (int) Math.floor(playerMinX);
        int maxX = (int) Math.floor(playerMaxX);
        int minY = (int) Math.floor(y - 2.0);
        int maxY = (int) Math.floor(y + SUPPORT_TOLERANCE);
        int minZ = (int) Math.floor(playerMinZ);
        int maxZ = (int) Math.floor(playerMaxZ);

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

        // These entities can provide a top collision surface even without a block below.
        BoundingBox nearbyBox = new BoundingBox(
                playerMinX, y - SUPPORT_TOLERANCE, playerMinZ,
                playerMaxX, y + SUPPORT_TOLERANCE, playerMaxZ
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
        keenAC.getAlertManager().send("AirStuck", player, Math.max(1, violationLevel), reason);
    }

    /** Cancels the repeating task and clears all queued/player state on shutdown. */
    public void clearAll() {
        if (batchTask != null) {
            batchTask.cancel();
            batchTask = null;
        }
        dataMap.clear();
        synchronized (queueLock) {
            packetQueue.clear();
            queueOverflow = false;
        }
    }

    private enum SnapshotType {
        FLYING,
        TELEPORT_CONFIRM,
        VEHICLE_MOVE
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
        private final UUID uuid;
        private World lastWorld;
        private double lastX;
        private double lastY;
        private double lastZ;
        private int stuckTicks;
        private int setbackCount;
        private int exemptTicks;
        private long lastFlyingTime;
        private boolean hasLastPos;

        private PlayerMovementData(Player player) {
            this.uuid = player.getUniqueId();
            this.lastWorld = player.getWorld();
            this.lastFlyingTime = System.currentTimeMillis();
        }
    }
}
