package dev.keenac.check;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
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
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Reviews movement packets while a GUI is open. After the third confirmed
 * violation, any further positional movement while the GUI is reopened is
 * treated strictly; the fourth violation also starts a short hard freeze.
 */
public final class GhostMenuCheck implements Listener {
    private static final long MAX_MOVEMENT_GAP_NANOS = 300_000_000L;
    private static final int MAX_QUEUED_SAMPLES = 256;
    private static final int TRIMMED_QUEUE_SIZE = 128;
    private static final double POSITION_EPSILON = 0.000001;
    private static final double HIGH_JUMP_DISTANCE = 1.05;
    private static final long FREEZE_ATTEMPT_ALERT_INTERVAL_MS = 150L;

    private final KeenAC plugin;
    private final Map<UUID, MenuState> states = new ConcurrentHashMap<>();
    private final Map<UUID, ConcurrentLinkedQueue<PacketPosition>> packetPositions =
            new ConcurrentHashMap<>();
    private final Map<UUID, Long> personalInventoryClicks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> violationLevels = new ConcurrentHashMap<>();
    private final Map<UUID, Long> externalMovementGraceUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastGroundedPositions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastGroundedAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> frozenUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Location> frozenPositions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFreezeAttemptAlert = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> applyingFreezeTeleports = ConcurrentHashMap.newKeySet();

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

        Location location = player.getLocation();
        MenuState state = states.computeIfAbsent(id, ignored -> new MenuState());
        state.begin(MenuSource.CONTAINER, System.nanoTime(), event.getView().getType().name(),
                location.getX(), location.getY(), location.getZ(), openGraceNanos());
        state.externalMovementGraceUntilNanos = externalMovementGraceUntil.getOrDefault(id, 0L);
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

    /** PacketEvents entry point: primitive values only; no Bukkit API calls. */
    public void recordPacketPosition(UUID playerId, double x, double y, double z) {
        if (playerId == null || !Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(z)) {
            return;
        }
        ConcurrentLinkedQueue<PacketPosition> queue =
                packetPositions.computeIfAbsent(playerId, ignored -> new ConcurrentLinkedQueue<>());
        queue.add(new PacketPosition(x, y, z, System.nanoTime()));

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

    /** Called once per tick on the primary server thread. */
    public void tick() {
        if (!enabled()) {
            clearTrackingOnly();
            return;
        }

        long now = System.nanoTime();
        long nowMillis = System.currentTimeMillis();
        long personalWindow = personalInventoryWindowNanos();

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();

            if (isFrozen(id, nowMillis)) {
                player.setVelocity(new Vector(0.0, 0.0, 0.0));
                ConcurrentLinkedQueue<PacketPosition> frozenQueue = packetPositions.get(id);
                if (frozenQueue != null) {
                    frozenQueue.clear();
                }
                continue;
            }

            if (player.isOnGround() && player.isValid() && !player.isDead()) {
                lastGroundedPositions.put(id, player.getLocation().clone());
                lastGroundedAt.put(id, nowMillis);
            }

            long clickAt = personalInventoryClicks.getOrDefault(id, 0L);
            boolean personalInventoryOpen = clickAt > 0L && now - clickAt <= personalWindow;
            if (clickAt > 0L && !personalInventoryOpen) {
                personalInventoryClicks.remove(id, clickAt);
            }

            boolean containerOpen = player.getOpenInventory().getType() != InventoryType.CRAFTING;
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
                            sample.x, sample.y, sample.z, openGraceNanos());
                    state.externalMovementGraceUntilNanos =
                            externalMovementGraceUntil.getOrDefault(id, 0L);
                }

                long movementGraceUntil = Math.max(
                        state.externalMovementGraceUntilNanos,
                        externalMovementGraceUntil.getOrDefault(id, 0L));
                if (sample.atNanos < state.graceUntilNanos
                        || sample.atNanos < movementGraceUntil
                        || player.getPing() > maxPing()
                        || isExempt(player)) {
                    state.resetMovementWindow();
                    state.resetVerticalWindow(sample.y, sample.atNanos);
                    state.setPacketBaseline(sample);
                    continue;
                }

                if (state.lastPacketNanos == 0L) {
                    state.setPacketBaseline(sample);
                    state.resetVerticalWindow(sample.y, sample.atNanos);
                    continue;
                }

                long elapsed = sample.atNanos - state.lastPacketNanos;
                double dx = sample.x - state.lastPacketX;
                double dy = sample.y - state.lastPacketY;
                double dz = sample.z - state.lastPacketZ;
                double horizontalDistance = Math.hypot(dx, dz);
                double totalDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                state.setPacketBaseline(sample);

                if (elapsed <= 0L || elapsed > MAX_MOVEMENT_GAP_NANOS) {
                    state.resetMovementWindow();
                    state.resetVerticalWindow(sample.y, sample.atNanos);
                    continue;
                }

                // Once the player has reached x3, any further movement packet
                // (including a tiny positional nudge or vertical jump) is a flag.
                if (violationLevels.getOrDefault(id, 0) >= 3
                        && totalDistance > POSITION_EPSILON) {
                    boolean closed = reportViolation(player, sample.atNanos, state,
                            totalDistance / (elapsed / 1_000_000_000.0));
                    state.resetMovementWindow();
                    state.resetVerticalWindow(sample.y, sample.atNanos);
                    if (closed) {
                        queue.clear();
                        break;
                    }
                    continue;
                }

                // High vertical displacement is a separate GhostHand signal;
                // regular small step-height changes remain ignored.
                if (state.updateVerticalWindow(sample.y, sample.atNanos)
                        >= HIGH_JUMP_DISTANCE) {
                    boolean closed = reportViolation(player, sample.atNanos, state,
                            Math.abs(dy) / (elapsed / 1_000_000_000.0));
                    state.resetMovementWindow();
                    state.resetVerticalWindow(sample.y, sample.atNanos);
                    if (closed) {
                        queue.clear();
                        break;
                    }
                    continue;
                }

                if (horizontalDistance < 0.025) {
                    if (state.samples > 0
                            && sample.atNanos - state.sampleStartedNanos > sampleWindowNanos()) {
                        state.resetMovementWindow();
                    }
                    continue;
                }

                double speed = horizontalDistance / (elapsed / 1_000_000_000.0);
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
                state.distance += horizontalDistance;

                if (state.samples >= requiredSamples()
                        && sample.atNanos - state.sampleStartedNanos >= requiredDurationNanos()
                        && state.distance >= requiredDistance()) {
                    boolean closed = reportViolation(player, sample.atNanos, state, speed);
                    state.resetMovementWindow();
                    if (closed) {
                        queue.clear();
                        break;
                    }
                }
            }
        }

        packetPositions.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
        states.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
        personalInventoryClicks.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
        externalMovementGraceUntil.keySet().removeIf(id -> {
            Long graceUntil = externalMovementGraceUntil.get(id);
            return plugin.getServer().getPlayer(id) == null
                    || graceUntil == null || now > graceUntil;
        });
        lastGroundedPositions.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
        lastGroundedAt.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVelocity(PlayerVelocityEvent event) {
        Player player = event.getPlayer();
        if (isFrozen(player.getUniqueId(), System.currentTimeMillis())) {
            event.setVelocity(new Vector(0.0, 0.0, 0.0));
            return;
        }
        grantExternalMovementGrace(player);
    }

    // Any hit, including a cancelled one, grants time for legitimate knockback.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            grantExternalMovementGrace(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onJump(PlayerJumpEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId(), System.currentTimeMillis())) {
            event.setCancelled(true);
            recordFrozenMovementAttempt(event.getPlayer());
        }
    }

    /** Reject all positional changes during the 2.5-second freeze, even tiny ones. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void enforceFreeze(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) {
            return;
        }

        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (!isFrozen(id, System.currentTimeMillis())) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()) {
            return;
        }

        Location locked = frozenPositions.get(id);
        if (locked == null || locked.getWorld() != to.getWorld()) {
            locked = from.clone();
            frozenPositions.put(id, locked.clone());
        }
        if (Math.abs(to.getX() - locked.getX()) > POSITION_EPSILON
                || Math.abs(to.getY() - locked.getY()) > POSITION_EPSILON
                || Math.abs(to.getZ() - locked.getZ()) > POSITION_EPSILON) {
            Location correction = locked.clone();
            correction.setYaw(to.getYaw());
            correction.setPitch(to.getPitch());
            event.setTo(correction);
            recordFrozenMovementAttempt(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void preventTeleportWhileFrozen(PlayerTeleportEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (applyingFreezeTeleports.contains(id)) {
            return;
        }
        if (isFrozen(id, System.currentTimeMillis())) {
            event.setCancelled(true);
            recordFrozenMovementAttempt(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (applyingFreezeTeleports.contains(id)) {
            return;
        }
        clearTracking(id);
        frozenUntil.remove(id);
        frozenPositions.remove(id);
        lastGroundedPositions.remove(id);
        lastGroundedAt.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        clearTracking(id);
        frozenUntil.remove(id);
        frozenPositions.remove(id);
        lastGroundedPositions.remove(id);
        lastGroundedAt.remove(id);
        lastFreezeAttemptAlert.remove(id);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        clearTracking(id);
        violationLevels.remove(id);
        frozenUntil.remove(id);
        frozenPositions.remove(id);
        lastFreezeAttemptAlert.remove(id);
        lastGroundedPositions.remove(id);
        lastGroundedAt.remove(id);
    }

    private void grantExternalMovementGrace(Player player) {
        UUID id = player.getUniqueId();
        long graceUntil = System.nanoTime() + velocityGraceNanos();
        externalMovementGraceUntil.merge(id, graceUntil, Math::max);
        MenuState state = states.get(id);
        if (state != null) {
            state.externalMovementGraceUntilNanos = Math.max(
                    state.externalMovementGraceUntilNanos, graceUntil);
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

    private boolean reportViolation(Player player, long now, MenuState state, double speed) {
        UUID id = player.getUniqueId();
        int level = violationLevels.merge(id, 1, (current, one) ->
                current == Integer.MAX_VALUE ? Integer.MAX_VALUE : current + 1);

        // No per-check cooldown: staff see each escalation as x1, x2, x3...
        plugin.getAlertManager().send("GhostHand", player, level);
        if (plugin.getConfig().getBoolean("checks.ghost-menu.log-context", true)) {
            plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                    "GhostHand suspicion for %s (VL=%d, menu=%s, speed=%.2f, distance=%.3f, samples=%d)",
                    player.getName(), level, state.menuType, speed, state.distance, state.samples));
        }

        if (level >= 3) {
            player.closeInventory();
        }
        if (level >= 4) {
            freezePlayer(player);
        }
        return level >= 3;
    }

    private void freezePlayer(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Location current = player.getLocation();
        Location grounded = lastGroundedPositions.get(id);
        long groundedAt = lastGroundedAt.getOrDefault(id, 0L);
        Location anchor = grounded != null && grounded.getWorld() == current.getWorld()
                && now - groundedAt <= 5000L ? grounded.clone() : current.clone();
        anchor.setYaw(current.getYaw());
        anchor.setPitch(current.getPitch());

        long duration = Math.max(2500L,
                plugin.getConfig().getLong("checks.ghost-menu.freeze-duration-ms", 2500L));
        frozenPositions.put(id, anchor.clone());
        frozenUntil.put(id, now + duration);
        lastFreezeAttemptAlert.put(id, now);

        player.closeInventory();
        applyingFreezeTeleports.add(id);
        try {
            if (player.getWorld() == anchor.getWorld()) {
                player.teleport(anchor, PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
        } finally {
            applyingFreezeTeleports.remove(id);
        }
        player.setVelocity(new Vector(0.0, 0.0, 0.0));
    }

    /** A fresh attempt while frozen escalates VL and renews the 2.5-second lock. */
    private void recordFrozenMovementAttempt(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        long previous = lastFreezeAttemptAlert.getOrDefault(id, 0L);
        if (now - previous < FREEZE_ATTEMPT_ALERT_INTERVAL_MS) {
            return;
        }
        lastFreezeAttemptAlert.put(id, now);

        int level = violationLevels.merge(id, 1, (current, one) ->
                current == Integer.MAX_VALUE ? Integer.MAX_VALUE : current + 1);
        plugin.getAlertManager().send("GhostHand", player, level);

        long duration = Math.max(2500L,
                plugin.getConfig().getLong("checks.ghost-menu.freeze-duration-ms", 2500L));
        frozenUntil.put(id, now + duration);
        player.closeInventory();
        player.setVelocity(new Vector(0.0, 0.0, 0.0));

        if (plugin.getConfig().getBoolean("checks.ghost-menu.log-context", true)) {
            plugin.getLogger().info("GhostHand freeze movement attempt for "
                    + player.getName() + " (VL=" + level + ").");
        }
    }

    private boolean isFrozen(UUID id, long now) {
        Long until = frozenUntil.get(id);
        if (until == null) {
            return false;
        }
        if (now >= until) {
            frozenUntil.remove(id, until);
            frozenPositions.remove(id);
            lastFreezeAttemptAlert.remove(id);
            return false;
        }
        return true;
    }

    private void clearTracking(UUID id) {
        states.remove(id);
        personalInventoryClicks.remove(id);
        externalMovementGraceUntil.remove(id);
        ConcurrentLinkedQueue<PacketPosition> queue = packetPositions.remove(id);
        if (queue != null) {
            queue.clear();
        }
    }

    private void clearTrackingOnly() {
        states.clear();
        personalInventoryClicks.clear();
        externalMovementGraceUntil.clear();
        packetPositions.values().forEach(ConcurrentLinkedQueue::clear);
        frozenUntil.clear();
        frozenPositions.clear();
        lastFreezeAttemptAlert.clear();
        lastGroundedPositions.clear();
        lastGroundedAt.clear();
    }

    public void clearAll() {
        clearTrackingOnly();
        violationLevels.clear();
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("checks.ghost-menu.enabled", true);
    }

    private boolean requireSprint() {
        return plugin.getConfig().getBoolean("checks.ghost-menu.require-sprint", false);
    }

    private int requiredSamples() {
        return Math.max(3,
                plugin.getConfig().getInt("checks.ghost-menu.required-samples", 4));
    }

    private int maxPing() {
        return Math.max(100,
                plugin.getConfig().getInt("checks.ghost-menu.max-ping-ms", 250));
    }

    private double minimumHorizontalSpeed() {
        return Math.max(1.0, plugin.getConfig().getDouble(
                "checks.ghost-menu.minimum-horizontal-speed", 1.8));
    }

    private double requiredDistance() {
        return Math.max(0.5, plugin.getConfig().getDouble(
                "checks.ghost-menu.required-distance", 0.65));
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
                "checks.ghost-menu.required-duration-ms", 250L)) * 1_000_000L;
    }

    private enum MenuSource { CONTAINER, PERSONAL_INVENTORY }

    private record PacketPosition(double x, double y, double z, long atNanos) { }

    private static final class MenuState {
        private MenuSource source;
        private String menuType;
        private long graceUntilNanos;
        private long externalMovementGraceUntilNanos;
        private long lastPacketNanos;
        private long sampleStartedNanos;
        private long verticalWindowStartedNanos;
        private double lastPacketX;
        private double lastPacketY;
        private double lastPacketZ;
        private double verticalMinY;
        private double verticalMaxY;
        private int samples;
        private double distance;

        private void begin(MenuSource source, long now, String menuType,
                           double x, double y, double z, long graceNanos) {
            this.source = source;
            this.menuType = menuType;
            this.graceUntilNanos = now + graceNanos;
            this.externalMovementGraceUntilNanos = 0L;
            this.lastPacketX = x;
            this.lastPacketY = y;
            this.lastPacketZ = z;
            this.lastPacketNanos = now;
            resetMovementWindow();
            resetVerticalWindow(y, now);
        }

        private void setPacketBaseline(PacketPosition packet) {
            lastPacketX = packet.x;
            lastPacketY = packet.y;
            lastPacketZ = packet.z;
            lastPacketNanos = packet.atNanos;
        }

        private double updateVerticalWindow(double y, long now) {
            if (verticalWindowStartedNanos == 0L
                    || now - verticalWindowStartedNanos > 1_200_000_000L) {
                resetVerticalWindow(y, now);
                return 0.0;
            }
            verticalMinY = Math.min(verticalMinY, y);
            verticalMaxY = Math.max(verticalMaxY, y);
            return verticalMaxY - verticalMinY;
        }

        private void resetVerticalWindow(double y, long now) {
            verticalWindowStartedNanos = now;
            verticalMinY = y;
            verticalMaxY = y;
        }

        private void resetMovementWindow() {
            samples = 0;
            distance = 0.0;
            sampleStartedNanos = 0L;
        }
    }
}
