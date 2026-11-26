package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shared short rubberband correction used by movement checks.
 *
 * All Bukkit state is read and changed on the primary server thread.
 */
public final class MovementSetbackManager implements Listener {
    private static final Vector ZERO_VELOCITY = new Vector(0.0, 0.0, 0.0);

    private final KeenAC plugin;
    private final Map<UUID, Location> lastValidLocations = new HashMap<>();
    private final Map<UUID, Location> correctionLocks = new HashMap<>();
    private final Map<UUID, Long> correctionLockUntil = new HashMap<>();
    private final Map<UUID, Long> lastSetbackAt = new HashMap<>();
    private final Set<UUID> applyingSetbacks = new HashSet<>();

    public MovementSetbackManager(KeenAC plugin) {
        this.plugin = plugin;
    }

    /**
     * Save a position only after the caller's movement sample has been accepted.
     */
    public void recordValidPosition(Player player, Location location) {
        if (player == null || location == null || location.getWorld() == null
                || !player.isOnline() || location.getWorld() != player.getWorld()) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            Location snapshot = location.clone();
            UUID id = player.getUniqueId();
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(id);
                if (online != null && online.isOnline()) {
                    recordValidPosition(online, snapshot);
                }
            });
            return;
        }
        lastValidLocations.put(player.getUniqueId(), location.clone());
    }

    public Location getLastValidLocation(UUID playerId) {
        Location location = lastValidLocations.get(playerId);
        return location == null ? null : location.clone();
    }

    /**
     * Apply a synchronous server-authoritative position correction.
     * The client receives the normal teleport correction packet from Bukkit/Paper.
     */
    public boolean setback(Player player, Location lastValidLocation, String checkName) {
        if (player == null || !player.isOnline()
                || !plugin.getConfig().getBoolean("checks.movement-setback.enabled", true)) {
            return false;
        }
        if (!Bukkit.isPrimaryThread()) {
            UUID id = player.getUniqueId();
            Location snapshot = lastValidLocation == null ? null : lastValidLocation.clone();
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(id);
                if (online != null && online.isOnline()) {
                    setback(online, snapshot, checkName);
                }
            });
            return false;
        }

        UUID id = player.getUniqueId();
        Location anchor = lastValidLocation;
        if (anchor == null) {
            anchor = lastValidLocations.get(id);
        }
        if (anchor == null || anchor.getWorld() == null || anchor.getWorld() != player.getWorld()) {
            return false;
        }

        long now = System.currentTimeMillis();
        long dedupeMillis = Math.max(0L,
                plugin.getConfig().getLong("checks.movement-setback.dedupe-ms", 80L));
        long previous = lastSetbackAt.getOrDefault(id, 0L);
        if (now - previous < dedupeMillis) {
            return false;
        }

        Location current = player.getLocation();
        Location target = anchor.clone();

        // Move the correction point a few millimetres against the attempted
        // horizontal displacement. Keep the anchor fractional; never floor/round it.
        double dx = anchor.getX() - current.getX();
        double dz = anchor.getZ() - current.getZ();
        double horizontalLength = Math.sqrt(dx * dx + dz * dz);
        double offset = Math.max(0.0005, Math.min(0.01,
                plugin.getConfig().getDouble("checks.movement-setback.micro-offset", 0.002)));
        if (horizontalLength > 1.0E-7) {
            target.add(dx / horizontalLength * offset, 0.0, dz / horizontalLength * offset);
        } else {
            target.add(offset, 0.0, 0.0);
        }
        target.setYaw(current.getYaw());
        target.setPitch(current.getPitch());

        applyingSetbacks.add(id);
        boolean applied;
        try {
            applied = player.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            applyingSetbacks.remove(id);
        }

        if (!applied) {
            return false;
        }

        player.setVelocity(ZERO_VELOCITY.clone());
        long lockMillis = Math.max(0L,
                plugin.getConfig().getLong("checks.movement-setback.correction-lock-ms", 120L));
        correctionLocks.put(id, player.getLocation().clone());
        correctionLockUntil.put(id, now + lockMillis);
        lastSetbackAt.put(id, now);

        plugin.getLogger().fine("Applied " + safeCheckName(checkName)
                + " movement rubberband to " + player.getName() + ".");
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void enforceCorrection(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) {
            return;
        }

        UUID id = event.getPlayer().getUniqueId();
        Long until = correctionLockUntil.get(id);
        if (until == null) {
            return;
        }
        if (System.currentTimeMillis() >= until) {
            correctionLockUntil.remove(id);
            correctionLocks.remove(id);
            return;
        }

        Location locked = correctionLocks.get(id);
        Location to = event.getTo();
        if (locked == null || to == null || to.getWorld() != locked.getWorld()) {
            return;
        }

        Location corrected = locked.clone();
        corrected.setYaw(to.getYaw());
        corrected.setPitch(to.getPitch());
        if (Double.compare(to.getX(), corrected.getX()) != 0
                || Double.compare(to.getY(), corrected.getY()) != 0
                || Double.compare(to.getZ(), corrected.getZ()) != 0) {
            event.setTo(corrected);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void suppressVelocityDuringCorrection(PlayerVelocityEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Long until = correctionLockUntil.get(id);
        if (until != null && System.currentTimeMillis() < until) {
            event.setVelocity(ZERO_VELOCITY.clone());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void clearAfterExternalTeleport(PlayerTeleportEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (applyingSetbacks.contains(id)) {
            return;
        }
        clear(id);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    public void clear(UUID id) {
        lastValidLocations.remove(id);
        correctionLocks.remove(id);
        correctionLockUntil.remove(id);
        lastSetbackAt.remove(id);
        applyingSetbacks.remove(id);
    }

    public void clearAll() {
        lastValidLocations.clear();
        correctionLocks.clear();
        correctionLockUntil.clear();
        lastSetbackAt.clear();
        applyingSetbacks.clear();
    }

    private String safeCheckName(String name) {
        if (name == null || name.isBlank()) {
            return "Movement";
        }
        return name.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
