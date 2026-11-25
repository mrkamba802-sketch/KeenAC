package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Conservative, alert-only inventory swap monitor.
 *
 * It deliberately ignores generic clicks, shift-click transfers and drag events.
 * A burst of swap operations is only a signal for staff review, never proof by itself.
 */
public final class InventoryAutoSwapCheck implements Listener {
    private static final long DEFAULT_WINDOW_MILLIS = 900L;
    private static final int DEFAULT_REQUIRED_SWAPS = 15;
    private static final long DEFAULT_ALERT_COOLDOWN_MILLIS = 5_000L;

    private final KeenAC plugin;
    private final Map<UUID, Deque<Long>> recentSwaps = new HashMap<>();
    private final Map<UUID, Long> lastAlertNanos = new HashMap<>();
    private final Map<UUID, Integer> violationLevels = new HashMap<>();

    public InventoryAutoSwapCheck(KeenAC plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!plugin.getConfig().getBoolean("checks.auto-swap.enabled", true)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)
                || player.getGameMode() == GameMode.CREATIVE
                || player.isDead()) {
            return;
        }

        // Only observe changes to the player's own inventory. Container sorting,
        // shift-click transfers, drag actions and ordinary clicks are not counted.
        if (event.getClickedInventory() == null
                || event.getClickedInventory().getType() != InventoryType.PLAYER) {
            return;
        }

        InventoryAction action = event.getAction();
        if (action != InventoryAction.SWAP_WITH_CURSOR
                && action != InventoryAction.HOTBAR_SWAP) {
            return;
        }

        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType() == Material.AIR) {
            return;
        }

        UUID id = player.getUniqueId();
        long now = System.nanoTime();
        long windowNanos = Math.max(250L,
                plugin.getConfig().getLong("checks.auto-swap.window-ms", DEFAULT_WINDOW_MILLIS))
                * 1_000_000L;

        Deque<Long> samples = recentSwaps.computeIfAbsent(id, ignored -> new ArrayDeque<>());
        samples.addLast(now);
        while (!samples.isEmpty() && now - samples.peekFirst() > windowNanos) {
            samples.removeFirst();
        }

        int requiredSwaps = Math.max(8,
                plugin.getConfig().getInt("checks.auto-swap.required-swaps", DEFAULT_REQUIRED_SWAPS));
        if (samples.size() < requiredSwaps) {
            return;
        }

        long cooldownNanos = Math.max(1_000L,
                plugin.getConfig().getLong("checks.auto-swap.alert-cooldown-ms",
                        DEFAULT_ALERT_COOLDOWN_MILLIS)) * 1_000_000L;
        long lastAlert = lastAlertNanos.getOrDefault(id, 0L);
        if (now - lastAlert < cooldownNanos) {
            return;
        }

        int violationLevel = violationLevels.merge(id, 1, Integer::sum);
        lastAlertNanos.put(id, now);
        samples.clear();

        // Inventory automation cannot be proven from Bukkit click events alone.
        // Keep this check informational and let staff review the alert.
        plugin.getAlertManager().send("AutoSwap", player, violationLevel);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    public void clear(UUID playerId) {
        recentSwaps.remove(playerId);
        lastAlertNanos.remove(playerId);
        violationLevels.remove(playerId);
    }

    public void clearAll() {
        recentSwaps.clear();
        lastAlertNanos.clear();
        violationLevels.clear();
    }
}
