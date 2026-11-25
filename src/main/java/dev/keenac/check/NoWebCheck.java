package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class NoWebCheck implements Listener {
    private static final long VIOLATION_DECAY_NANOS = 5_000_000_000L;

    private final KeenAC plugin;
    private final Map<UUID, PlayerState> states = new HashMap<>();
    private long serverTick;

    public NoWebCheck(KeenAC plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            serverTick++;
            if (serverTick % 20L == 0L) {
                decayStates();
            }
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
        long now = System.nanoTime();
        double maxDistance = getAllowedMoveDistance(player, state, now);
        if (state != null) {
            state.lastMovementNanos = now;
        }

        // Once VL reaches x2, only unusually large movement remains blocked.
        // Normal-sized movement immediately releases the anchor so a player who
        // turns the speed exploit off is not trapped by stale violation state.
        if (state != null && state.locked) {
            if (!toInWeb) {
                // Leaving the cobweb releases movement correction, but the
                // accumulated VL belongs to this player session and is retained.
                state.locked = false;
                state.anchor = null;
                state.normalMovementSamples = 0;
                state.lastSafe = to.clone();
                if (state.violationLevel > 0) {
                    states.put(id, state);
                } else {
                    states.remove(id);
                }
                return;
            }
            if (state.anchor == null || state.anchor.getWorld() != from.getWorld()) {
                states.remove(id);
                return;
            }

            double moved = from.distance(to);
            if (moved > maxDistance) {
                // Keep correcting excessive movement and continue increasing VL
                // once per server tick. Reaching x2 must not freeze alerts at x2.
                state.normalMovementSamples = 0;
                returnToAnchor(event, state);
                countViolation(player, state);
                return;
            }

            // A single legal-sized movement packet is not enough to release a
            // player from the anchor. Let normal movement through, and release
            // the lock after several consecutive legal samples. Never decrement
            // VL here: alert levels must remain monotonic during one violation.
            state.lastSafe = to.clone();
            state.anchor = to.clone();
            if (moved > 0.005) {
                state.normalMovementSamples++;
                if (state.normalMovementSamples >= 3) {
                    state.locked = false;
                    state.normalMovementSamples = 0;
                }
            }
            return;
        }

        // Entering or leaving a cobweb is not itself a violation.
        if (!fromInWeb || !toInWeb) {
            if (!fromInWeb && !toInWeb) {
                if (state == null || state.violationLevel == 0) states.remove(id);
            } else if (toInWeb) {
                if (state == null) state = new PlayerState(to);
                state.lastSafe = to.clone();
                states.put(id, state);
            } else {
                // Do not reset xVL when the player leaves the web. The next
                // entry resumes the same violation counter instead of starting at x1.
                if (state != null && state.violationLevel > 0) {
                    state.locked = false;
                    state.anchor = null;
                    state.normalMovementSamples = 0;
                    state.lastSafe = to.clone();
                    states.put(id, state);
                } else {
                    states.remove(id);
                }
            }
            return;
        }

        if (state == null) {
            state = new PlayerState(from);
            states.put(id, state);
        }

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

    private void decayStates() {
        long now = System.nanoTime();
        Iterator<Map.Entry<UUID, PlayerState>> iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PlayerState> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                iterator.remove();
                continue;
            }

            PlayerState state = entry.getValue();
            // Preserve the previous VL while the player is outside cobwebs.
            // Decay only resumes once the player is back inside the check area.
            if (!intersectsCobweb(player, player.getLocation())) {
                continue;
            }
            if (state.violationLevel > 0
                    && now - state.lastViolationNanos >= VIOLATION_DECAY_NANOS) {
                state.violationLevel--;
                state.lastViolationNanos = now;
                if (state.violationLevel < 2) {
                    state.locked = false;
                }
            }

            if (state.violationLevel == 0 && !state.locked
                    && !intersectsCobweb(player, player.getLocation())) {
                iterator.remove();
            }
        }
    }

    /**
     * Cobweb movement is roughly one quarter of normal walking speed, but the
     * player's effective movement attribute still changes with sprinting,
     * Speed effects and server-side attribute modifiers. PlayerMoveEvent can
     * also represent more than one tick after packet delay, so scale the
     * allowance by the observed interval instead of using one fixed packet
     * distance for every player and every connection.
     */
    private double getAllowedMoveDistance(Player player, PlayerState state, long now) {
        double configured = Math.max(0.01,
                plugin.getConfig().getDouble("checks.no-web.max-move-distance", 0.08));

        double speedMultiplier = 1.0;
        AttributeInstance movementSpeed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (movementSpeed != null && movementSpeed.getBaseValue() > 0.0) {
            speedMultiplier = movementSpeed.getValue() / movementSpeed.getBaseValue();
            // Avoid nonsensical values while preserving legitimate attribute/effect boosts.
            speedMultiplier = Math.max(0.75, Math.min(4.0, speedMultiplier));
        }

        double elapsedTickMultiplier = 1.0;
        if (state != null && state.lastMovementNanos > 0L) {
            double elapsedTicks = (now - state.lastMovementNanos) / 50_000_000.0;
            // A delayed movement packet may cover several server ticks.
            // The cap prevents a long idle period from creating a huge allowance.
            elapsedTickMultiplier = Math.max(1.0, Math.min(4.0, elapsedTicks));
        }

        return configured * speedMultiplier * elapsedTickMultiplier + 0.01;
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
        state.lastViolationNanos = System.nanoTime();
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
        private long lastViolationNanos;
        private long lastMovementNanos;
        private int normalMovementSamples;
        private boolean locked;

        private PlayerState(Location initialLocation) {
            this.lastSafe = initialLocation.clone();
            this.lastMovementNanos = System.nanoTime();
        }
    }
}
