package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Reviews packet movement while a server-side GUI is open.
 * Packet callbacks only append immutable samples; all Bukkit access and
 * evaluation happen from tick() on the primary server thread.
 */
public final class GhostMenuCheck implements Listener {
    private static final long MAX_MOVEMENT_GAP_NANOS = 300_000_000L;
    private static final int MAX_QUEUED_SAMPLES = 256;
    private static final int TRIMMED_QUEUE_SIZE = 128;

    private final KeenAC plugin;
    private final Map<UUID, MenuState> states = new ConcurrentHashMap<>();
    private final Map<UUID, ConcurrentLinkedQueue<PacketPosition>> packetPositions =
            new ConcurrentHashMap<>();
    private final Map<UUID, Long> personalInventoryClicks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> violationLevels = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAlerts = new ConcurrentHashMap<>();

    public GhostMenuCheck(KeenAC plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || event.getView().getType() == InventoryType.CRAFTING
                || !enabled()) {
            return;
        }

        UUID id = player.getUniqueId();
        ConcurrentLinkedQueue<PacketPosition> queue = packetPositions.get(id);
        if (queue != null) {
            queue.clear();
        }
        MenuState state = states.computeIfAbsent(id, ignored -> new MenuState());
        state.begin(MenuSource.CONTAINER, System.nanoTime(), event.getView().getType().name(),
                player.getLocation().getX(), player.getLocation().getZ(), openGraceNanos());
        personalInventoryClicks.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getView().getType() != InventoryType.CRAFTING) {
            UUID id = event.getPlayer().getUniqueId();
            states.remove(id);
            personalInventoryClicks.remove(id);
            ConcurrentLinkedQueue<PacketPosition> queue = packetPositions.get(id);
            if (queue != null) {
                queue.clear();
            }
        }
    }

    /**
     * PacketEvents entry point: primitive values only, no Bukkit calls.
     * A bounded queue preserves every recent positional sample instead of
     * overwriting intermediate packets with only the newest location.
     */
    public void recordPacketPosition(UUID playerId, double x, double z) {
        if (playerId == null || !Double.isFinite(x) || !Double.isFinite(z)) {
            return;
        }

        ConcurrentLinkedQueue<PacketPosition> queue =
                packetPositions.computeIfAbsent(playerId, ignored -> new ConcurrentLinkedQueue<>());
        queue.add(new PacketPosition(x, z, System.nanoTime()));

        if (queue.size() > MAX_QUEUED_SAMPLES) {
            for (int i = 0; i < MAX_QUEUED_SAMPLES - TRIMMED_QUEUE_SIZE; i++) {
                if (queue.poll() == null) {
                    break;
                }
            }
        }
    }

    /** Window 0 click is a short, best-effort signal for the client-side E inventory. */
    public void recordPersonalInventoryClick(UUID playerId) {
        if (playerId != null && enabled()) {
            personalInventoryClicks.put(playerId, System.nanoTime());
        }
    }

    /** Called once per tick on the main thread. */
    public void tick() {
        if (!enabled()) {
            clearTrackingOnly();
            return;
        }

        long now = System.nanoTime();
        long personalWindow = personalInventoryWindowNanos();

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            long clickAt = personalInventoryClicks.getOrDefault(id, 0L);
            boolean personalInventoryOpen = clickAt > 0L && now - clickAt <= personalWindow;
            if (clickAt > 0L && !personalInventoryOpen) {
                personalInventoryClicks.remove(id, clickAt);
            }

            boolean containerOpen =
                    player.getOpenInventory().getType() != InventoryType.CRAFTING;
            if (!containerOpen && !personalInventoryOpen) {
                states.remove(id);
                ConcurrentLinkedQueue<PacketPosition> stale = packetPositions.get(id);
                if (stale != null) {
                    stale.clear();
                }
                continue;
            }

            ConcurrentLinkedQueue<PacketPosition> queue = packetPositions.get(id);
            if (queue == null) {
                continue;
            }

            MenuSource source = containerOpen
                    ? MenuSource.CONTAINER : MenuSource.PERSONAL_INVENTORY;
            String menuType = containerOpen
                    ? player.getOpenInventory().getType().name() : "PLAYER_INVENTORY";
            MenuState state = states.computeIfAbsent(id, ignored -> new MenuState());

            PacketPosition sample;
            int drained = 0;
            while (drained++ < MAX_QUEUED_SAMPLES && (sample = queue.poll()) != null) {
                if (state.source != source) {
                    state.begin(source, sample.atNanos, menuType,
                            sample.x, sample.z, openGraceNanos());
                }

                if (sample.atNanos < state.graceUntilNanos
                        || sample.atNanos < state.externalMovementGraceUntilNanos
                        || player.getPing() > maxPing()
                        || isExempt(player)) {
                    state.resetMovementWindow();
                    state.setPacketBaseline(sample);
                    continue;
                }

                if (state.lastPacketNanos == 0L) {
                    state.setPacketBaseline(sample);
                    continue;
                }

                long elapsed = sample.atNanos - state.lastPacketNanos;
                double distance = Math.hypot(
                        sample.x - state.lastPacketX,
                        sample.z - state.lastPacketZ);
                state.setPacketBaseline(sample);

                if (elapsed <= 0L || elapsed > MAX_MOVEMENT_GAP_NANOS) {
                    state.resetMovementWindow();
                    continue;
                }

                if (distance < 0.025) {
                    if (state.samples > 0
                            && sample.atNanos - state.sampleStartedNanos > sampleWindowNanos()) {
                        state.resetMovementWindow();
                    }
                    continue;
                }

                double speed = distance / (elapsed / 1_000_000_000.0);
                if ((requireSprint() && !player.isSprinting())
                        || speed < minimumHorizontalSpeed()) {
                    state.resetMovementWindow();
                    continue;
                }

                if (state.samples == 0
                        || sample.atNanos - state.sampleStartedNanos > sampleWindowNanos()) {
                    state.resetMovementWindow();
                    state.sampleStartedNanos = sample.atNanos;
                }

                state.samples++;
                state.distance += distance;

                if (state.samples >= requiredSamples()
                        && sample.atNanos - state.sampleStartedNanos >= requiredDurationNanos()
                        && state.distance >= requiredDistance()) {
                    reportViolation(player, sample.atNanos, state, speed);
                    state.resetMovementWindow();
                }
            }
        }

        packetPositions.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
        states.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
        personalInventoryClicks.keySet().removeIf(
                id -> plugin.getServer().getPlayer(id) == null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        grantExternalMovementGrace(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            grantExternalMovementGrace(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        clearTracking(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        clearTracking(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        clearTracking(id);
        violationLevels.remove(id);
        lastAlerts.remove(id);
    }

    private void grantExternalMovementGrace(Player player) {
        MenuState state = states.get(player.getUniqueId());
        if (state != null) {
            state.externalMovementGraceUntilNanos = System.nanoTime() + velocityGraceNanos();
            state.resetMovementWindow();
        }
    }

    private boolean isExempt(Player player) {
        if (!player.isValid() || player.isDead()
                || player.getGameMode() == GameMode.SPECTATOR
                || player.isFlying() || player.isInsideVehicle()
                || player.isGliding() || player.isSwimming()
                || player.isClimbing() || player.isRiptiding()
                || player.isInWater() || player.isInLava()
                || player.isSleeping()) {
            return true;
        }

        Material below = player.getLocation().getBlock().getRelative(BlockFace.DOWN).getType();
        return below == Material.ICE || below == Material.PACKED_ICE
                || below == Material.BLUE_ICE || below == Material.FROSTED_ICE
                || below == Material.SLIME_BLOCK || below == Material.HONEY_BLOCK
                || below == Material.SOUL_SAND || below == Material.SOUL_SOIL;
    }

    private void reportViolation(Player player, long now, MenuState state, double speed) {
        long previous = lastAlerts.getOrDefault(player.getUniqueId(), 0L);
        if (now - previous < alertCooldownNanos()) {
            return;
        }

        int level = violationLevels.merge(player.getUniqueId(), 1, Integer::sum);
        lastAlerts.put(player.getUniqueId(), now);
        plugin.getAlertManager().send("GhostMenu", player, level);
        if (plugin.getConfig().getBoolean("checks.ghost-menu.log-context", true)) {
            plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                    "GhostMenu review for %s (VL=%d, menu=%s, speed=%.2f, distance=%.2f, samples=%d)",
                    player.getName(), level, state.menuType, speed, state.distance, state.samples));
        }
    }

    private void clearTracking(UUID id) {
        states.remove(id);
        personalInventoryClicks.remove(id);
        ConcurrentLinkedQueue<PacketPosition> queue = packetPositions.remove(id);
        if (queue != null) {
            queue.clear();
        }
    }

    private void clearTrackingOnly() {
        states.clear();
        personalInventoryClicks.clear();
        packetPositions.values().forEach(ConcurrentLinkedQueue::clear);
    }

    public void clearAll() {
        clearTrackingOnly();
        violationLevels.clear();
        lastAlerts.clear();
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("checks.ghost-menu.enabled", true);
    }

    private boolean requireSprint() {
        return plugin.getConfig().getBoolean("checks.ghost-menu.require-sprint", false);
    }

    private int requiredSamples() {
        return Math.max(3,
                plugin.getConfig().getInt("checks.ghost-menu.required-samples", 6));
    }

    private int maxPing() {
        return Math.max(100,
                plugin.getConfig().getInt("checks.ghost-menu.max-ping-ms", 250));
    }

    private double minimumHorizontalSpeed() {
        return Math.max(1.0, plugin.getConfig().getDouble(
                "checks.ghost-menu.minimum-horizontal-speed", 3.4));
    }

    private double requiredDistance() {
        return Math.max(0.5, plugin.getConfig().getDouble(
                "checks.ghost-menu.required-distance", 1.2));
    }

    private long openGraceNanos() {
        return Math.max(300L, plugin.getConfig().getLong(
                "checks.ghost-menu.open-grace-ms", 1200L)) * 1_000_000L;
    }

    private long velocityGraceNanos() {
        return Math.max(200L, plugin.getConfig().getLong(
                "checks.ghost-menu.velocity-grace-ms", 1000L)) * 1_000_000L;
    }

    private long personalInventoryWindowNanos() {
        return Math.max(500L, plugin.getConfig().getLong(
                "checks.ghost-menu.personal-inventory-window-ms", 2200L)) * 1_000_000L;
    }

    private long sampleWindowNanos() {
        return Math.max(350L, plugin.getConfig().getLong(
                "checks.ghost-menu.sample-window-ms", 1200L)) * 1_000_000L;
    }

    private long requiredDurationNanos() {
        return Math.max(150L, plugin.getConfig().getLong(
                "checks.ghost-menu.required-duration-ms", 350L)) * 1_000_000L;
    }

    private long alertCooldownNanos() {
        return Math.max(1000L, plugin.getConfig().getLong(
                "checks.ghost-menu.alert-cooldown-ms", 5000L)) * 1_000_000L;
    }

    private enum MenuSource { CONTAINER, PERSONAL_INVENTORY }

    private record PacketPosition(double x, double z, long atNanos) { }

    private static final class MenuState {
        private MenuSource source;
        private String menuType;
        private long graceUntilNanos;
        private long externalMovementGraceUntilNanos;
        private long lastPacketNanos;
        private long sampleStartedNanos;
        private double lastPacketX;
        private double lastPacketZ;
        private int samples;
        private double distance;

        private void begin(MenuSource source, long now, String menuType,
                           double x, double z, long graceNanos) {
            this.source = source;
            this.menuType = menuType;
            this.graceUntilNanos = now + graceNanos;
            this.lastPacketX = x;
            this.lastPacketZ = z;
            this.lastPacketNanos = now;
            resetMovementWindow();
        }

        private void setPacketBaseline(PacketPosition packet) {
            lastPacketX = packet.x;
            lastPacketZ = packet.z;
            lastPacketNanos = packet.atNanos;
        }

        private void resetMovementWindow() {
            samples = 0;
            distance = 0.0;
            sampleStartedNanos = 0L;
        }
    }
}
