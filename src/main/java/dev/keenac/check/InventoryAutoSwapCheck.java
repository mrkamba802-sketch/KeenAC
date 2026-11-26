package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PacketEvents-driven monitor for inventory SWAP click packets.
 * The Bukkit listener is used only to discard per-player state on quit.
 */
public final class InventoryAutoSwapCheck implements Listener {
    private final KeenAC plugin;
    private final Map<UUID, SwapState> states = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> selectedSlots = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private volatile int requiredSwaps;
    private volatile long windowNanos;
    private volatile long alertCooldownNanos;

    public InventoryAutoSwapCheck(KeenAC plugin) {
        this.plugin = plugin;
        reloadSettings();
    }

    /** Called on the server thread after startup or /keenac reload. */
    public void reloadSettings() {
        enabled = plugin.getConfig().getBoolean("checks.auto-swap.enabled", true);
        requiredSwaps = Math.max(4,
                plugin.getConfig().getInt("checks.auto-swap.required-swaps", 8));
        windowNanos = Math.max(250L,
                plugin.getConfig().getLong("checks.auto-swap.window-ms", 1500L))
                * 1_000_000L;
        alertCooldownNanos = Math.max(1000L,
                plugin.getConfig().getLong("checks.auto-swap.alert-cooldown-ms", 5000L))
                * 1_000_000L;
    }

    /** PacketEvents HELD_ITEM_CHANGE updates the last client-selected hotbar slot. */
    public void recordSelectedSlot(UUID playerId, int slot) {
        if (playerId != null && slot >= 0 && slot <= 8) {
            selectedSlots.put(playerId, slot);
        }
    }

    public int getSelectedSlot(UUID playerId) {
        return selectedSlots.getOrDefault(playerId, -1);
    }

    /**
     * Called only for PacketEvents CLICK_WINDOW packets whose click type is SWAP.
     * Button 0-8 means a hotbar key; button 40 is the offhand swap key.
     */
    public void recordSwap(UUID playerId, int windowId, int clickedSlot, int button) {
        if (!enabled || playerId == null || windowId < 0 || clickedSlot < 0
                || !((button >= 0 && button <= 8) || button == 40)) {
            return;
        }

        int selected = getSelectedSlot(playerId);
        if (button <= 8 && selected == button) {
            return;
        }

        long now = System.nanoTime();
        SwapState state = states.computeIfAbsent(playerId, ignored -> new SwapState());
        int violationLevel = 0;
        synchronized (state) {
            while (!state.samples.isEmpty()
                    && now - state.samples.peekFirst() > windowNanos) {
                state.samples.removeFirst();
            }
            state.samples.addLast(now);

            if (state.samples.size() < requiredSwaps
                    || now - state.lastAlertNanos < alertCooldownNanos) {
                return;
            }

            state.samples.clear();
            state.lastAlertNanos = now;
            violationLevel = ++state.violationLevel;
        }

        final int vl = violationLevel;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline() && enabled) {
                plugin.getAlertManager().send("AutoSwap", player, vl,
                        "The player performed rapid hotbar or offhand slot swaps above the configured threshold.");
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    public void clear(UUID playerId) {
        states.remove(playerId);
        selectedSlots.remove(playerId);
    }

    public void clearAll() {
        states.clear();
        selectedSlots.clear();
    }

    private static final class SwapState {
        private final Deque<Long> samples = new ArrayDeque<>();
        private long lastAlertNanos;
        private int violationLevel;
    }
}
