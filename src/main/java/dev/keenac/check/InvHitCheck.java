package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.inventory.InventoryType;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects attacks while a server-side container is open, plus attacks shortly
 * after a server-observed click in the client-side player inventory (window 0).
 *
 * The vanilla E screen has no dependable "opened" notification to the server;
 * player-inventory detection therefore remains a bounded best-effort signal.
 */
public final class InvHitCheck implements Listener {
    private static final long DEFAULT_PERSONAL_CLICK_GRACE_MILLIS = 650L;

    private final KeenAC plugin;
    private final Set<UUID> openContainerViews = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> recentPersonalInventoryActivity = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> violationLevels = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAlertNanos = new ConcurrentHashMap<>();

    public InvHitCheck(KeenAC plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || event.getView().getType() == InventoryType.CRAFTING) {
            return;
        }

        UUID id = player.getUniqueId();
        openContainerViews.add(id);
        recentPersonalInventoryActivity.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        openContainerViews.remove(id);
        recentPersonalInventoryActivity.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getView().getType() != InventoryType.CRAFTING
                || event.getClickedInventory() == null) {
            return;
        }

        if (event.getClickedInventory().getType() == InventoryType.PLAYER
                || event.getClickedInventory().getType() == InventoryType.CRAFTING) {
            rememberPersonalInventoryActivity(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && event.getView().getType() == InventoryType.CRAFTING
                && !event.getRawSlots().isEmpty()) {
            rememberPersonalInventoryActivity(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)
                || !plugin.getConfig().getBoolean("checks.inv-hit.enabled", true)
                || !isInventoryOpen(attacker.getUniqueId())) {
            return;
        }

        if (plugin.getConfig().getBoolean("checks.inv-hit.cancel-hits", true)) {
            event.setCancelled(true);
        }
        reportViolation(attacker);
    }

    /**
     * Called directly from PacketEvents for a client click on window 0.
     * Only concurrent data is touched here; no Bukkit entity or inventory calls.
     */
    public void recordPersonalInventoryPacket(UUID playerId) {
        if (playerId != null
                && plugin.getConfig().getBoolean("checks.inv-hit.enabled", true)) {
            rememberPersonalInventoryActivity(playerId);
        }
    }

    private void rememberPersonalInventoryActivity(UUID playerId) {
        long graceMillis = Math.max(100L,
                plugin.getConfig().getLong("checks.inv-hit.personal-inventory-click-grace-ms",
                        DEFAULT_PERSONAL_CLICK_GRACE_MILLIS));
        recentPersonalInventoryActivity.put(playerId,
                System.nanoTime() + graceMillis * 1_000_000L);
    }

    public boolean isInventoryOpen(UUID playerId) {
        if (openContainerViews.contains(playerId)) {
            return true;
        }

        Long until = recentPersonalInventoryActivity.get(playerId);
        if (until == null) {
            return false;
        }
        if (System.nanoTime() <= until) {
            return true;
        }
        recentPersonalInventoryActivity.remove(playerId, until);
        return false;
    }

    public boolean shouldCancelHits() {
        return plugin.getConfig().getBoolean("checks.inv-hit.cancel-hits", true);
    }

    public void reportViolation(Player player) {
        if (player == null || !player.isOnline()
                || !plugin.getConfig().getBoolean("checks.inv-hit.enabled", true)) {
            return;
        }

        UUID id = player.getUniqueId();
        long now = System.nanoTime();
        long cooldownNanos = Math.max(0L,
                plugin.getConfig().getLong("alerts.delay-seconds", 3L)) * 1_000_000_000L;
        long previous = lastAlertNanos.getOrDefault(id, 0L);
        if (now - previous < cooldownNanos) {
            return;
        }

        int violationLevel = violationLevels.merge(id, 1, Integer::sum);
        lastAlertNanos.put(id, now);
        plugin.getAlertManager().send("InvHit", player, violationLevel);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    private void clear(UUID id) {
        openContainerViews.remove(id);
        recentPersonalInventoryActivity.remove(id);
        violationLevels.remove(id);
        lastAlertNanos.remove(id);
    }

    public void clearAll() {
        openContainerViews.clear();
        recentPersonalInventoryActivity.clear();
        violationLevels.clear();
        lastAlertNanos.clear();
    }
}
