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
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.inventory.InventoryType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Packet-driven movement review while a server-side GUI is open.
 * Bukkit events are used only for GUI lifecycle and exemptions; movement
 * coordinates are supplied by PacketEvents.
 */
public final class GhostMenuCheck implements Listener {
    private static final long MAX_MOVEMENT_GAP_NANOS = 300_000_000L;

    private final KeenAC plugin;
    private final Map<UUID, MenuState> states = new ConcurrentHashMap<>();
    private final Map<UUID, PacketPosition> packetPositions = new ConcurrentHashMap<>();
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
        MenuState state = states.computeIfAbsent(id, ignored -> new MenuState());
        state.begin(MenuSource.CONTAINER, System.nanoTime(),
                player.getOpenInventory().getType().name(),
                player.getLocation().getX(), player.getLocation().getZ(),
                openGraceNanos());
        personalInventoryClicks.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getView().getType() != InventoryType.CRAFTING) {
            UUID id = event.getPlayer().getUniqueId();
            states.remove(id);
            personalInventoryClicks.remove(id);
        }
    }

    /**
     * PacketEvents entry point. It only stores primitive values and is safe to
     * call from a network thread; Bukkit APIs are never accessed here.
     */
    public void recordPacketPosition(UUID playerId, double x, double z) {
        if (playerId == null || !Double.isFinite(x) || !Double.isFinite(z)) {
            return;
        }
        packetPositions.put(playerId, new PacketPosition(x, z, System.nanoTime()));
    }

    /** Window 0 is the client-side player inventory; clicks are a bounded signal. */
    public void recordPersonalInventoryClick(UUID playerId) {
        if (playerId != null && enabled()) {
            personalInventoryClicks.put(playerId, System.nanoTime());
        }
    }

    /**
     * Called once per server tick. Packet coordinates are consumed on the main
     * thread so inventory, movement-mode and world checks remain Bukkit-safe.
     */
    public void tick() {
        if (!enabled()) {
            clearTrackingOnly();
            return;
        }

        long now = System.nanoTime();
        long personalWindow = personalInventoryWindowNanos();

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            PacketPosition packet = packetPositions.get(id);
            if (packet == null) {
                continue;
            }

            long personalClickAt = personalInventoryClicks.getOrDefault(id, 0L);
            boolean personalInventoryOpen = personalClickAt > 0L
                    && now - personalClickAt <= personalWindow;
            if (personalClickAt > 0L && !personalInventoryOpen) {
                personalInventoryClicks.remove(id, personalClickAt);
            }

            boolean containerOpen =
                    player.getOpenInventory().getType() != InventoryType.CRAFTING;
            if (!containerOpen && !personalInventoryOpen) {
                states.remove(id);
                continue;
            }

            MenuState state = states.computeIfAbsent(id, ignored -> new MenuState());
            MenuSource source = containerOpen
                    ? MenuSource.CONTAINER : MenuSource.PERSONAL_INVENTORY;
            String menuType = containerOpen
                    ? player.getOpenInventory().getType().name() : "PLAYER_INVENTORY";

            if (state.source != source) {
                state.begin(source, packet.atNanos, menuType,
                        packet.x, packet.z, openGraceNanos());
            }

            if (packet.atNanos <= state.lastProcessedPacketNanos) {
                continue;
            }
            state.lastProcessedPacketNanos = packet.atNanos;

            if (packet.atNanos < state.graceUntilNanos
                    || packet.atNanos < state.externalMovementGraceUntilNanos
                    || isExempt(player)
                    || player.getPing() > maxPing()) {
                state.resetMovementWindow();
                state.setPacketBaseline(packet);
                continue;
            }

            if (state.lastPacketNanos == 0L) {
                state.setPacketBaseline(packet);
                continue;
            }

            long elapsed = packet.atNanos - state.lastPacketNanos;
            double distance = Math.hypot(
                    packet.x - state.lastPacketX,
                    packet.z - state.lastPacketZ);
            state.setPacketBaseline(packet);

            if (elapsed <= 0L || elapsed > MAX_MOVEMENT_GAP_NANOS) {
                state.resetMovementWindow();
                continue;
            }

            if (distance < 0.035) {
                if (state.sampleStartedNanos > 0L
                        && packet.atNanos - state.sampleStartedNanos > sampleWindowNanos()) {
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
                    || packet.atNanos - state.sampleStartedNanos > sampleWindowNanos()) {
                state.resetMovementWindow();
                state.sampleStartedNanos = packet.atNanos;
            }

            state.samples++;
            state.distance += distance;

            if (state.samples >= requiredSamples()
                    && packet.atNanos - state.sampleStartedNanos >= requiredDurationNanos()
                    && state.distance >= requiredDistance()) {
                reportViolation(player, packet.atNanos);
                state.resetMovementWindow();
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
            state.externalMovementGraceUntilNanos =
                    System.nanoTime() + velocityGraceNanos();
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
                || player.isSleeping() || !player.isOnGround()) {
            return true;
        }

        Material below = player.getLocation().getBlock().getRelative(BlockFace.DOWN).getType();
        return below == Material.ICE || below == Material.PACKED_ICE
                || below == Material.BLUE_ICE || below == Material.FROSTED_ICE
                || below == Material.SLIME_BLOCK || below == Material.HONEY_BLOCK
                || below == Material.SOUL_SAND || below == Material.SOUL_SOIL;
    }

    private void reportViolation(Player player, long now) {
        long previous = lastAlerts.getOrDefault(player.getUniqueId(), 0L);
        if (now - previous < alertCooldownNanos()) {
            return;
        }

        int level = violationLevels.merge(player.getUniqueId(), 1, Integer::sum);
        lastAlerts.put(player.getUniqueId(), now);
        plugin.getAlertManager().send("GhostMenu", player, level);
    }

    private void clearTracking(UUID id) {
        states.remove(id);
        packetPositions.remove(id);
        personalInventoryClicks.remove(id);
    }

    private void clearTrackingOnly() {
        states.clear();
        packetPositions.clear();
        personalInventoryClicks.clear();
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
        private long lastMovementNanos;
        private long lastProcessedPacketNanos;
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
            this.lastMovementNanos = now;
            this.lastPacketX = x;
            this.lastPacketZ = z;
            this.lastPacketNanos = now;
            this.lastProcessedPacketNanos = 0L;
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
