package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Alert-only monitor for repeated, server-accepted hotbar swap operations.
 * It ignores PICKUP, QUICK_MOVE, drag and cursor operations. Swaps into the
 * currently selected hand are ignored; alerts remain advisory, not proof.
 */
public final class InventoryAutoSwapCheck implements Listener {
    private static final long DEFAULT_WINDOW_MILLIS = 1_000L;
    private static final int DEFAULT_REQUIRED_SWAPS = 12;
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
        if (!(event.getWhoClicked() instanceof Player player)
                || player.getGameMode() == GameMode.CREATIVE
                || player.isDead()
                || !plugin.getConfig().getBoolean("checks.auto-swap.enabled", true)) {
            return;
        }

        // A number-key click with HOTBAR_SWAP is the explicit slot exchange.
        // Other inventory actions are deliberately not part of this check.
        if (event.getClick() != ClickType.NUMBER_KEY
                || event.getAction() != InventoryAction.HOTBAR_SWAP
                || event.getClickedInventory() == null) {
            return;
        }

        int hotbarSlot = event.getHotbarButton();
        if (hotbarSlot < 0 || hotbarSlot > 8
                || hotbarSlot == player.getInventory().getHeldItemSlot()) {
            return;
        }

        recordSwap(player);
    }

    private void recordSwap(Player player) {
        UUID id = player.getUniqueId();
        long now = System.nanoTime();
        long windowNanos = Math.max(250L,
                plugin.getConfig().getLong("checks.auto-swap.window-ms",
                        DEFAULT_WINDOW_MILLIS)) * 1_000_000L;

        Deque<Long> samples = recentSwaps.computeIfAbsent(id, ignored -> new ArrayDeque<>());
        samples.addLast(now);
        while (!samples.isEmpty() && now - samples.peekFirst() > windowNanos) {
            samples.removeFirst();
        }

        int requiredSwaps = Math.max(8,
                plugin.getConfig().getInt("checks.auto-swap.required-swaps",
                        DEFAULT_REQUIRED_SWAPS));
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
