package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.inventory.InventoryType;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Packet-driven SilentSwap review.
 *
 * The only evidence accepted by this check is a rapid HELD_ITEM_CHANGE
 * switch-away / attack / switch-back sequence without a matching main-hand
 * swing packet. Window click and inventory-mutation packets are deliberately
 * excluded and only clear an in-progress candidate.
 *
 * The server-thread tick publishes immutable inventory/eligibility snapshots;
 * packet callbacks never access Bukkit inventories or entities.
 */
public final class SilentSwapCheck {
    private final KeenAC plugin;
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();

    private volatile boolean enabled = true;
    private volatile long maxCycleNanos = 100_000_000L;
    private volatile long observationWindowNanos = 1_600_000_000L;
    private volatile long swingCorrelationNanos = 140_000_000L;
    private volatile long alertCooldownNanos = 5_000_000_000L;
    private volatile int requiredCycles = 2;
    private volatile int maxPingMs = 250;
    private volatile long reviewDelayTicks = 3L;

    public SilentSwapCheck(KeenAC plugin) {
        this.plugin = plugin;
        reloadSettings();
    }

    public void reloadSettings() {
        enabled = plugin.getConfig().getBoolean("checks.silent-swap.enabled", true);
        maxCycleNanos = Math.max(20L,
                plugin.getConfig().getLong("checks.silent-swap.max-cycle-ms", 100L))
                * 1_000_000L;
        observationWindowNanos = Math.max(500L,
                plugin.getConfig().getLong("checks.silent-swap.observation-window-ms", 1600L))
                * 1_000_000L;
        swingCorrelationNanos = Math.max(20L,
                plugin.getConfig().getLong("checks.silent-swap.swing-correlation-ms", 140L))
                * 1_000_000L;
        alertCooldownNanos = Math.max(1000L,
                plugin.getConfig().getLong("checks.silent-swap.alert-cooldown-ms", 5000L))
                * 1_000_000L;
        requiredCycles = Math.max(2,
                plugin.getConfig().getInt("checks.silent-swap.required-cycles", 2));
        maxPingMs = Math.max(0,
                plugin.getConfig().getInt("checks.silent-swap.max-ping-ms", 250));
        reviewDelayTicks = Math.max(1L,
                plugin.getConfig().getLong("checks.silent-swap.review-delay-ticks", 3L));

        if (!enabled) {
            states.clear();
        }
    }

    /**
     * Called by PacketEvents for a serverbound HELD_ITEM_CHANGE packet.
     * A zero-millisecond cycle is valid: packets can be received in one burst.
     */
    public void recordSelectedSlot(UUID playerId, int slot) {
        if (!enabled || playerId == null || slot < 0 || slot > 8) {
            return;
        }

        PlayerState state = states.get(playerId);
        if (state == null) {
            return; // Wait until tick() has captured a server-side baseline.
        }

        long now = System.nanoTime();
        CycleCandidate candidate = null;
        synchronized (state) {
            ServerSnapshot snapshot = state.snapshot;
            if (snapshot == null || !snapshot.eligible) {
                state.clearEvidence();
                return;
            }

            if (state.selectedSlot < 0) {
                state.selectedSlot = snapshot.selectedSlot;
                if (state.selectedSlot < 0) {
                    state.selectedSlot = slot;
                    return;
                }
            }
            if (slot == state.selectedSlot) {
                return;
            }

            int previousSlot = state.selectedSlot;
            state.selectedSlot = slot;
            PendingSwitch pending = state.pendingSwitch;

            if (pending == null || now - pending.startedAtNanos > maxCycleNanos) {
                state.pendingSwitch = new PendingSwitch(
                        previousSlot, slot, now, snapshot.isEmpty(slot));
                return;
            }

            if (slot == pending.originalSlot) {
                long duration = now - pending.startedAtNanos;
                if (pending.actionAtNanos > 0L
                        && duration <= maxCycleNanos
                        && pending.actionAtNanos >= pending.startedAtNanos
                        && pending.actionAtNanos <= now) {
                    candidate = new CycleCandidate(
                            pending.originalSlot,
                            pending.temporarySlot,
                            pending.actionAtNanos,
                            pending.temporarySlotEmpty,
                            state.generation);
                }
                state.pendingSwitch = null;
            } else {
                // A new target slot invalidates the unfinished pair.
                state.pendingSwitch = new PendingSwitch(
                        previousSlot, slot, now, snapshot.isEmpty(slot));
            }
        }

        if (candidate != null) {
            scheduleCandidateReview(playerId, state, candidate);
        }
    }

    /** Called for the legacy INTERACT_ENTITY attack packet or the newer ATTACK packet. */
    public void recordAttack(UUID playerId) {
        if (!enabled || playerId == null) {
            return;
        }

        PlayerState state = states.get(playerId);
        if (state == null) {
            return;
        }

        long now = System.nanoTime();
        synchronized (state) {
            ServerSnapshot snapshot = state.snapshot;
            PendingSwitch pending = state.pendingSwitch;
            if (snapshot == null || !snapshot.eligible || pending == null) {
                return;
            }
            if (now - pending.startedAtNanos > maxCycleNanos
                    || state.selectedSlot != pending.temporarySlot) {
                state.pendingSwitch = null;
                return;
            }

            // Record only the first attack inside this short switch interval.
            if (pending.actionAtNanos == 0L) {
                pending.actionAtNanos = now;
            }
        }
    }

    /** Main-hand animation/swing packet; used to reject ordinary visible attacks. */
    public void recordMainHandSwing(UUID playerId) {
        if (!enabled || playerId == null) {
            return;
        }
        PlayerState state = states.get(playerId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.lastMainHandSwingNanos = System.nanoTime();
        }
    }

    /**
     * Inventory clicks, item moves, and window swaps never count as evidence.
     * They invalidate only the unfinished packet sequence so clicks cannot be
     * associated with an unrelated attack that happens afterwards.
     */
    public void recordInventoryMutation(UUID playerId) {
        if (playerId == null) {
            return;
        }
        PlayerState state = states.get(playerId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.clearEvidence();
        }
    }

    /** Called on Bukkit's main thread once per tick to publish safe snapshots. */
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
            boolean[] emptySlots = new boolean[9];
            for (int slot = 0; slot < 9; slot++) {
                ItemStack item = player.getInventory().getItem(slot);
                emptySlots[slot] = item == null || item.getType().isAir();
            }

            boolean eligible = isEligible(player);
            UUID worldId = player.getWorld().getUID();
            int heldSlot = player.getInventory().getHeldItemSlot();

            synchronized (state) {
                ServerSnapshot previous = state.snapshot;
                boolean worldChanged = previous != null
                        && !previous.worldId.equals(worldId);
                if (worldChanged || !eligible) {
                    state.clearEvidence();
                    state.selectedSlot = heldSlot;
                } else if (state.selectedSlot < 0) {
                    state.selectedSlot = heldSlot;
                }

                state.snapshot = new ServerSnapshot(
                        eligible, heldSlot, emptySlots, worldId, now);

                if (state.pendingSwitch != null
                        && now - state.pendingSwitch.startedAtNanos > maxCycleNanos) {
                    state.pendingSwitch = null;
                }
                pruneCycles(state, now);
            }
        }

        states.keySet().removeIf(id -> !online.contains(id));
    }

    private void scheduleCandidateReview(UUID playerId, PlayerState state,
                                         CycleCandidate candidate) {
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> reviewCandidate(playerId, state, candidate), reviewDelayTicks);
    }

    /** Runs on the Bukkit main thread after enough time for a delayed swing packet to arrive. */
    private void reviewCandidate(UUID playerId, PlayerState state,
                                 CycleCandidate candidate) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline() || !enabled || !isEligible(player)
                || states.get(playerId) != state) {
            return;
        }

        int violationLevel = 0;
        long now = System.nanoTime();
        synchronized (state) {
            if (!enabled || state.generation != candidate.generation) {
                return;
            }

            long swingAt = state.lastMainHandSwingNanos;
            boolean hasAnimation = swingAt > 0L
                    && Math.abs(swingAt - candidate.actionAtNanos) <= swingCorrelationNanos;
            if (hasAnimation) {
                return;
            }

            // No swing packet within the correlation window is the key
            // discriminator. Empty temporary slots are explicitly preserved
            // as evidence too; empty-hand attacks are not ignored.
            pruneCycles(state, now);
            state.completedSilentCycles.addLast(candidate.actionAtNanos);
            while (state.completedSilentCycles.size() > 8) {
                state.completedSilentCycles.removeFirst();
            }

            if (state.completedSilentCycles.size() < requiredCycles
                    || now - state.lastAlertNanos < alertCooldownNanos) {
                return;
            }

            state.completedSilentCycles.clear();
            state.lastAlertNanos = now;
            state.violationLevel++;
            violationLevel = state.violationLevel;
        }

        plugin.getAlertManager().send("SilentSwap", player, violationLevel);
        if (plugin.getConfig().getBoolean("checks.silent-swap.log-context", true)) {
            plugin.getLogger().info("SilentSwap confirmed for " + player.getName()
                    + " (VL=" + violationLevel
                    + ", slotCycle=" + candidate.originalSlot + "->"
                    + candidate.temporarySlot + "->" + candidate.originalSlot
                    + ", temporarySlotEmpty=" + candidate.temporarySlotEmpty
                    + ", swingPacket=false)");
        }
    }

    private boolean isEligible(Player player) {
        return player.isOnline()
                && player.isValid()
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
        while (!state.completedSilentCycles.isEmpty()
                && now - state.completedSilentCycles.peekFirst() > observationWindowNanos) {
            state.completedSilentCycles.removeFirst();
        }
    }

    public void clearAll() {
        states.clear();
    }

    private record ServerSnapshot(boolean eligible, int selectedSlot, boolean[] emptySlots,
                                  UUID worldId, long capturedAtNanos) {
        private boolean isEmpty(int slot) {
            return slot >= 0 && slot < emptySlots.length && emptySlots[slot];
        }
    }

    private static final class PlayerState {
        private int selectedSlot = -1;
        private int violationLevel;
        private long lastAlertNanos;
        private long lastMainHandSwingNanos;
        private long generation;
        private PendingSwitch pendingSwitch;
        private ServerSnapshot snapshot;
        private final ArrayDeque<Long> completedSilentCycles = new ArrayDeque<>();

        private void clearEvidence() {
            generation++;
            pendingSwitch = null;
            completedSilentCycles.clear();
        }
    }

    private static final class PendingSwitch {
        private final int originalSlot;
        private final int temporarySlot;
        private final long startedAtNanos;
        private final boolean temporarySlotEmpty;
        private long actionAtNanos;

        private PendingSwitch(int originalSlot, int temporarySlot,
                              long startedAtNanos, boolean temporarySlotEmpty) {
            this.originalSlot = originalSlot;
            this.temporarySlot = temporarySlot;
            this.startedAtNanos = startedAtNanos;
            this.temporarySlotEmpty = temporarySlotEmpty;
        }
    }

    private record CycleCandidate(int originalSlot, int temporarySlot, long actionAtNanos,
                                  boolean temporarySlotEmpty, long generation) { }
}
