package dev.keenac.check;

import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conservative SilentSwap review.
 *
 * It only counts a rapid hotbar switch-away / action / switch-back sequence.
 * A single slot change, a rapid scroll, or an action without a matching
 * return-switch is never a violation. This check is advisory and does not
 * cancel attacks, item use, or block interactions.
 */
public final class SilentSwapCheck implements Listener {
    private final KeenAC plugin;
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();

    private volatile boolean enabled = true;
    private volatile long maxCycleNanos = 120_000_000L;
    private volatile long minCycleNanos = 4_000_000L;
    private volatile long observationWindowNanos = 2_500_000_000L;
    private volatile long alertCooldownNanos = 5_000_000_000L;
    private volatile int requiredCycles = 4;
    private volatile int maxPingMs = 180;

    public SilentSwapCheck(KeenAC plugin) {
        this.plugin = plugin;
        reloadSettings();
    }

    public void reloadSettings() {
        enabled = plugin.getConfig().getBoolean("checks.silent-swap.enabled", true);
        maxCycleNanos = Math.max(40L,
                plugin.getConfig().getLong("checks.silent-swap.max-cycle-ms", 120L))
                * 1_000_000L;
        minCycleNanos = Math.max(1L,
                plugin.getConfig().getLong("checks.silent-swap.min-cycle-ms", 4L))
                * 1_000_000L;
        observationWindowNanos = Math.max(500L,
                plugin.getConfig().getLong("checks.silent-swap.observation-window-ms", 2500L))
                * 1_000_000L;
        alertCooldownNanos = Math.max(1000L,
                plugin.getConfig().getLong("checks.silent-swap.alert-cooldown-ms", 5000L))
                * 1_000_000L;
        requiredCycles = Math.max(3,
                plugin.getConfig().getInt("checks.silent-swap.required-cycles", 4));
        maxPingMs = Math.max(0,
                plugin.getConfig().getInt("checks.silent-swap.max-ping-ms", 180));
        if (!enabled) {
            states.clear();
        }
    }

    /** Initialize the packet-side selected-slot baseline on the Bukkit thread. */
    public void initialize(Player player) {
        if (player == null) {
            return;
        }
        PlayerState state = states.computeIfAbsent(
                player.getUniqueId(), ignored -> new PlayerState());
        synchronized (state) {
            state.selectedSlot = player.getInventory().getHeldItemSlot();
            state.clearEvidence();
        }
    }

    /**
     * PacketEvents callback. Only the slot number and monotonic timestamp are
     * read here; Bukkit player or inventory APIs are never accessed on Netty.
     */
    public void recordSelectedSlot(UUID playerId, int slot) {
        if (!enabled || playerId == null || slot < 0 || slot > 8) {
            return;
        }

        PlayerState state = states.computeIfAbsent(playerId, ignored -> new PlayerState());
        long now = System.nanoTime();
        boolean report = false;

        synchronized (state) {
            if (state.selectedSlot < 0) {
                state.selectedSlot = slot;
                return;
            }
            if (slot == state.selectedSlot) {
                return;
            }

            int previousSlot = state.selectedSlot;
            state.selectedSlot = slot;
            PendingSwitch pending = state.pendingSwitch;

            if (pending == null || now - pending.startedAtNanos > maxCycleNanos) {
                state.pendingSwitch = new PendingSwitch(previousSlot, slot, now);
                return;
            }

            if (slot == pending.originalSlot) {
                long duration = now - pending.startedAtNanos;
                if (pending.actionObserved
                        && duration >= minCycleNanos
                        && duration <= maxCycleNanos) {
                    pruneCycles(state, now);
                    state.completedCycles.addLast(now);
                    while (state.completedCycles.size() > 12) {
                        state.completedCycles.removeFirst();
                    }
                    report = state.completedCycles.size() >= requiredCycles
                            && !state.alertPending
                            && now - state.lastAlertNanos >= alertCooldownNanos;
                }
                state.pendingSwitch = null;
            } else {
                // A different slot was selected instead of returning to the
                // original slot. Start a fresh candidate; do not count the
                // incomplete sequence.
                state.pendingSwitch = new PendingSwitch(previousSlot, slot, now);
            }

            if (report) {
                state.alertPending = true;
                state.completedCycles.clear();
            }
        }

        if (report) {
            dispatchReview(playerId, state);
        }
    }

    /** Called for main-hand attack, use-item, or use-item-on-block packets. */
    public void recordMainHandAction(UUID playerId) {
        if (!enabled || playerId == null) {
            return;
        }
        PlayerState state = states.get(playerId);
        if (state == null) {
            return;
        }

        long now = System.nanoTime();
        synchronized (state) {
            PendingSwitch pending = state.pendingSwitch;
            if (pending == null) {
                return;
            }
            if (now - pending.startedAtNanos > maxCycleNanos) {
                state.pendingSwitch = null;
                return;
            }
            if (state.selectedSlot == pending.temporarySlot) {
                pending.actionObserved = true;
            }
        }
    }

    /** Called once per server tick; expires incomplete packet sequences. */
    public void tick() {
        if (!enabled) {
            states.clear();
            return;
        }

        Set<UUID> online = ConcurrentHashMap.newKeySet();
        long now = System.nanoTime();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            online.add(id);
            PlayerState state = states.computeIfAbsent(id, ignored -> new PlayerState());
            synchronized (state) {
                if (state.selectedSlot < 0) {
                    state.selectedSlot = player.getInventory().getHeldItemSlot();
                }
                if (state.pendingSwitch != null
                        && now - state.pendingSwitch.startedAtNanos > maxCycleNanos) {
                    state.pendingSwitch = null;
                }
                pruneCycles(state, now);
            }
        }
        states.keySet().removeIf(id -> !online.contains(id));
    }

    private void dispatchReview(UUID playerId, PlayerState state) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline() || !enabled || !isEligible(player)) {
                synchronized (state) {
                    state.alertPending = false;
                }
                return;
            }

            int violationLevel;
            synchronized (state) {
                if (!enabled) {
                    state.alertPending = false;
                    return;
                }
                state.violationLevel++;
                violationLevel = state.violationLevel;
                state.lastAlertNanos = System.nanoTime();
                state.alertPending = false;
            }

            plugin.getAlertManager().send("SilentSwap", player, violationLevel);
            if (plugin.getConfig().getBoolean("checks.silent-swap.log-context", true)) {
                plugin.getLogger().info("SilentSwap review for " + player.getName()
                        + " (VL=" + violationLevel
                        + ", rapid action-bound slot swap cycles=" + requiredCycles + ")");
            }
        });
    }

    private boolean isEligible(Player player) {
        return player.isValid()
                && !player.isDead()
                && !player.isInsideVehicle()
                && !player.isFlying()
                && !player.isGliding()
                && player.getGameMode() != GameMode.CREATIVE
                && player.getGameMode() != GameMode.SPECTATOR
                && player.getPing() <= maxPingMs
                && player.getOpenInventory().getType() == InventoryType.CRAFTING;
    }

    private void pruneCycles(PlayerState state, long now) {
        while (!state.completedCycles.isEmpty()
                && now - state.completedCycles.peekFirst() > observationWindowNanos) {
            state.completedCycles.removeFirst();
        }
    }

    private void clearEvidence(UUID playerId) {
        PlayerState state = states.get(playerId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.clearEvidence();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        initialize(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        clearEvidence(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        initialize(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        clearEvidence(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getView().getType() != InventoryType.CRAFTING) {
            clearEvidence(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        clearEvidence(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    public void clearAll() {
        states.clear();
    }

    private static final class PlayerState {
        private int selectedSlot = -1;
        private int violationLevel;
        private long lastAlertNanos;
        private boolean alertPending;
        private PendingSwitch pendingSwitch;
        private final ArrayDeque<Long> completedCycles = new ArrayDeque<>();

        private void clearEvidence() {
            pendingSwitch = null;
            completedCycles.clear();
            alertPending = false;
        }
    }

    private static final class PendingSwitch {
        private final int originalSlot;
        private final int temporarySlot;
        private final long startedAtNanos;
        private boolean actionObserved;

        private PendingSwitch(int originalSlot, int temporarySlot, long startedAtNanos) {
            this.originalSlot = originalSlot;
            this.temporarySlot = temporarySlot;
            this.startedAtNanos = startedAtNanos;
        }
    }
}
