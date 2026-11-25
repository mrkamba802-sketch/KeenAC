package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class NoWebCheck implements Listener {
    private final KeenAC plugin;
    private final Map<UUID, PlayerState> states = new HashMap<>();
    private long serverTick;

    public NoWebCheck(KeenAC plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            serverTick++;
            states.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        }, 1L, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        // Administrative/plugin teleports must remain possible and reset this check.
        if (event instanceof PlayerTeleportEvent) {
            states.remove(id);
            return;
        }

        if (!plugin.getConfig().getBoolean("checks.no-web.enabled", true)) {
            states.remove(id);
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()) {
            states.remove(id);
            return;
        }

        boolean fromInWeb = intersectsCobweb(player, from);
        boolean toInWeb = intersectsCobweb(player, to);
        PlayerState state = states.get(id);

        // Once VL reaches x2, keep the player anchored. Even tiny movement
        // attempts are redirected to the last position accepted by this check.
        if (state != null && state.locked) {
            if (state.anchor == null || state.anchor.getWorld() != from.getWorld()) {
                states.remove(id);
                return;
            }
            returnToAnchor(event, state);
            countViolation(player, state);
            return;
        }

        // Entering or leaving a cobweb is not itself a violation.
        if (!fromInWeb || !toInWeb) {
            if (!fromInWeb && !toInWeb) {
                states.remove(id);
            } else if (toInWeb) {
                if (state == null) state = new PlayerState(to);
                state.lastSafe = to.clone();
                states.put(id, state);
            } else {
                states.remove(id);
            }
            return;
        }

        if (state == null) {
            state = new PlayerState(from);
            states.put(id, state);
        }

        double maxDistance = Math.max(0.01,
                plugin.getConfig().getDouble("checks.no-web.max-move-distance", 0.08));
        double moved = from.distance(to);

        if (moved > maxDistance) {
            countViolation(player, state);
            if (state.violationLevel >= 2) {
                state.locked = true;
                state.anchor = state.lastSafe == null ? from.clone() : state.lastSafe.clone();
                returnToAnchor(event, state);
            }
            return;
        }

        state.lastSafe = to.clone();
    }

    private void returnToAnchor(PlayerMoveEvent event, PlayerState state) {
        Location correction = state.anchor.clone();
        Location requested = event.getTo();
        if (requested != null) {
            // Keep camera rotation responsive; only lock the player's coordinates.
            correction.setYaw(requested.getYaw());
            correction.setPitch(requested.getPitch());
        }
        event.setTo(correction);
    }

    private void countViolation(Player player, PlayerState state) {
        // A movement packet can trigger more than one event in a tick. Count
        // at most once per tick, while still checking and correcting every move.
        if (state.lastViolationTick == serverTick) return;
        state.violationLevel++;
        state.lastViolationTick = serverTick;
        plugin.getAlertManager().send("NoWeb", player, state.violationLevel);
    }

    private boolean intersectsCobweb(Player player, Location location) {
        World world = location.getWorld();
        if (world == null) return false;

        // Approximate the player's standing hitbox (0.6 wide, 1.8 high) and
        // inspect every block it overlaps, including webs at head/body height.
        double halfWidth = 0.3;
        double height = 1.8;
        int minX = floor(location.getX() - halfWidth);
        int maxX = floor(location.getX() + halfWidth);
        int minY = floor(location.getY());
        int maxY = floor(location.getY() + height);
        int minZ = floor(location.getZ() - halfWidth);
        int maxZ = floor(location.getZ() + halfWidth);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (world.getBlockAt(x, y, z).getType() == Material.COBWEB) {
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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    private static final class PlayerState {
        private Location lastSafe;
        private Location anchor;
        private int violationLevel;
        private long lastViolationTick = Long.MIN_VALUE;
        private boolean locked;

        private PlayerState(Location initialLocation) {
            this.lastSafe = initialLocation.clone();
        }
    }
}
