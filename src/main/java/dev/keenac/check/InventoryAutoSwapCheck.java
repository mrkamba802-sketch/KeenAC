package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Alert-only monitor for unusually dense inventory rearrangement activity.
 *
 * It observes server-accepted inventory operations across the complete open
 * inventory view, rather than only clicks on the player's lower inventory.
 * A signal is advisory and must never be treated as proof on its own.
 */
public final class InventoryAutoSwapCheck implements Listener {
    private static final long DEFAULT_WINDOW_MILLIS = 1_000L;
    private static final int DEFAULT_REQUIRED_SCORE = 12;
    private static final long DEFAULT_ALERT_COOLDOWN_MILLIS = 5_000L;

    private final KeenAC plugin;
    private final Map<UUID, Deque<OperationSample>> recentOperations = new HashMap<>();
    private final Map<UUID, Long> lastAlertNanos = new HashMap<>();
    private final Map<UUID, Integer> violationLevels = new HashMap<>();

    public InventoryAutoSwapCheck(KeenAC plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || player.getGameMode() == GameMode.CREATIVE
                || player.isDead()
                || !plugin.getConfig().getBoolean("checks.auto-swap.enabled", true)) {
            return;
        }

        // A multi-slot drag is one operation, not one score per changed slot.
        // Only larger rearrangements contribute, reducing ordinary drag noise.
        if (event.getRawSlots().size() >= 4) {
            recordOperation(player, 1);
        }
    }

    /**
     * Called on the Bukkit main thread for a decoded client inventory operation.
     */
    public void recordPacketOperation(Player player, int score) {
        if (player == null || !player.isOnline()
                || player.getGameMode() == GameMode.CREATIVE || player.isDead()
                || !plugin.getConfig().getBoolean("checks.auto-swap.enabled", true)) {
            return;
        }
        recordOperation(player, Math.max(1, Math.min(3, score)));
    }

    private void recordOperation(Player player, int score) {
        UUID id = player.getUniqueId();
        long now = System.nanoTime();

        long windowNanos = Math.max(250L,
                plugin.getConfig().getLong("checks.auto-swap.window-ms",
                        DEFAULT_WINDOW_MILLIS)) * 1_000_000L;
        Deque<OperationSample> samples = recentOperations.computeIfAbsent(
                id, ignored -> new ArrayDeque<>());
        samples.addLast(new OperationSample(now, score));

        while (!samples.isEmpty() && now - samples.peekFirst().timeNanos > windowNanos) {
            samples.removeFirst();
        }

        int totalScore = 0;
        for (OperationSample sample : samples) {
            totalScore += sample.score;
        }

        int requiredScore = Math.max(8,
                plugin.getConfig().getInt("checks.auto-swap.required-swaps",
                        DEFAULT_REQUIRED_SCORE));
        if (totalScore < requiredScore) {
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

        // Inventory automation cannot be proven from server inventory events alone.
        // Keep this informational and let staff review the alert.
        plugin.getAlertManager().send("AutoSwap", player, violationLevel);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    public void clear(UUID playerId) {
        recentOperations.remove(playerId);
        lastAlertNanos.remove(playerId);
        violationLevels.remove(playerId);
    }

    public void clearAll() {
        recentOperations.clear();
        lastAlertNanos.clear();
        violationLevels.clear();
    }

    private static final class OperationSample {
        private final long timeNanos;
        private final int score;

        private OperationSample(long timeNanos, int score) {
            this.timeNanos = timeNanos;
            this.score = score;
        }
    }
}
