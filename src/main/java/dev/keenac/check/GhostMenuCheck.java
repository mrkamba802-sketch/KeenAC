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
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.inventory.InventoryType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects sustained sprinting while a server-side GUI is open.
 *
 * The personal E-inventory is client-side and has no open/close event on the
 * server. For that screen, CLICK_WINDOW packets on window 0 provide only a
 * short best-effort observation window after an inventory click.
 */
public final class GhostMenuCheck implements Listener {
    private static final long MAX_MOVEMENT_GAP_NANOS = 300_000_000L;

    private final KeenAC plugin;
    private final Map<UUID, MenuState> states = new ConcurrentHashMap<>();
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

        long now = System.nanoTime();
        MenuState state = states.computeIfAbsent(player.getUniqueId(), ignored -> new MenuState());
        state.begin(MenuSource.CONTAINER, now, event.getView().getType().name(),
                player.getLocation().getX(), player.getLocation().getZ(),
                openGraceNanos());
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
     * Called from PacketEvents for window-0 inventory clicks. This method touches
     * only a concurrent map and primitive values; it does not access Bukkit state.
     */
    public void recordPersonalInventoryClick(UUID playerId) {
        if (playerId != null) {
            personalInventoryClicks.put(playerId, System.nanoTime());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!enabled() || event.getTo() == null) {
            return;
        }

        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        long now = System.nanoTime();
        boolean containerOpen = player.getOpenInventory().getType() != InventoryType.CRAFTING;

        long personalClickAt = personalInventoryClicks.getOrDefault(id, 0L);
        long personalWindowNanos = personalInventoryWindowNanos();
        boolean personalInventoryOpen = personalClickAt > 0L
                && now - personalClickAt <= personalWindowNanos;

        if (!containerOpen && !personalInventoryOpen) {
            if (personalClickAt > 0L && now - personalClickAt > personalWindowNanos) {
                personalInventoryClicks.remove(id, personalClickAt);
            }
            states.remove(id);
            return;
        }

        MenuState state = states.computeIfAbsent(id, ignored -> new MenuState());
        if (containerOpen) {
            if (state.source != MenuSource.CONTAINER) {
                state.begin(MenuSource.CONTAINER, now, player.getOpenInventory().getType().name(),
                        event.getFrom().getX(), event.getFrom().getZ(), openGraceNanos());
            }
        } else if (state.source != MenuSource.PERSONAL_INVENTORY) {
            state.begin(MenuSource.PERSONAL_INVENTORY, now, "PLAYER_INVENTORY",
                    event.getFrom().getX(), event.getFrom().getZ(), openGraceNanos());
        }

        // A menu-opening transition is not evidence: a player may have been
        // sprinting toward a chest immediately before opening it.
        if (now < state.graceUntilNanos || now < state.externalMovementGraceUntilNanos) {
            state.resetMovementWindow();
            state.lastMovementNanos = now;
            return;
        }

        if (isExempt(player) || player.getPing() > maxPing()) {
            state.resetMovementWindow();
            state.lastMovementNanos = now;
            return;
        }

        double dx = event.getTo().getX() - event.getFrom().getX();
        double dz = event.getTo().getZ() - event.getFrom().getZ();
        double horizontalDistance = Math.hypot(dx, dz);
        if (horizontalDistance < 0.035) {
            return; // Ignore look-only packets without breaking a continuous run sample.
        }

        long elapsed = state.lastMovementNanos == 0L ? 0L : now - state.lastMovementNanos;
        state.lastMovementNanos = now;
        if (elapsed <= 0L || elapsed > MAX_MOVEMENT_GAP_NANOS) {
            state.resetMovementWindow();
            return;
        }

        double speed = horizontalDistance / (elapsed / 1_000_000_000.0);
        if ((requireSprint() && !player.isSprinting()) || speed < minimumHorizontalSpeed()) {
            state.resetMovementWindow();
            return;
        }

        if (state.samples == 0) {
            state.sampleStartedNanos = now;
        } else if (now - state.sampleStartedNanos > sampleWindowNanos()) {
            state.resetMovementWindow();
            state.sampleStartedNanos = now;
        }

        state.samples++;
        state.distance += horizontalDistance;

        if (state.samples >= requiredSamples()
                && now - state.sampleStartedNanos >= requiredDurationNanos()
                && state.distance >= requiredDistance()) {
            reportViolation(player, state, now);
            state.resetMovementWindow();
        }
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
                || player.isSleeping() || !player.isOnGround()) {
            return true;
        }

        Material below = player.getLocation().getBlock().getRelative(BlockFace.DOWN).getType();
        return below == Material.ICE || below == Material.PACKED_ICE
                || below == Material.BLUE_ICE || below == Material.FROSTED_ICE
                || below == Material.SLIME_BLOCK || below == Material.HONEY_BLOCK
                || below == Material.SOUL_SAND || below == Material.SOUL_SOIL;
    }

    private void reportViolation(Player player, MenuState state, long now) {
        long cooldown = alertCooldownNanos();
        long previous = lastAlerts.getOrDefault(player.getUniqueId(), 0L);
        if (now - previous < cooldown) {
            return;
        }

        int level = violationLevels.merge(player.getUniqueId(), 1, Integer::sum);
        lastAlerts.put(player.getUniqueId(), now);
        plugin.getAlertManager().send("GhostMenu", player, level);
    }

    private void clearTracking(UUID id) {
        states.remove(id);
        personalInventoryClicks.remove(id);
    }

    public void clearAll() {
        states.clear();
        personalInventoryClicks.clear();
        violationLevels.clear();
        lastAlerts.clear();
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("checks.ghost-menu.enabled", true);
    }

    private boolean requireSprint() {
        return plugin.getConfig().getBoolean("checks.ghost-menu.require-sprint", true);
    }

    private int requiredSamples() {
        return Math.max(3, plugin.getConfig().getInt("checks.ghost-menu.required-samples", 6));
    }

    private int maxPing() {
        return Math.max(100, plugin.getConfig().getInt("checks.ghost-menu.max-ping-ms", 250));
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

    private static final class MenuState {
        private MenuSource source;
        private String menuType;
        private long graceUntilNanos;
        private long externalMovementGraceUntilNanos;
        private long lastMovementNanos;
        private long sampleStartedNanos;
        private int samples;
        private double distance;

        private void begin(MenuSource source, long now, String menuType,
                           double x, double z, long graceNanos) {
            this.source = source;
            this.menuType = menuType;
            this.graceUntilNanos = now + graceNanos;
            this.lastMovementNanos = now;
            resetMovementWindow();
        }

        private void resetMovementWindow() {
            samples = 0;
            distance = 0.0;
            sampleStartedNanos = 0L;
        }
    }
}
