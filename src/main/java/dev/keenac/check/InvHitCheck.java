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
 * Detects attack attempts while a server-side inventory GUI is open.
 *
 * On modern Minecraft versions the default E-screen is client-side and is not
 * always announced to the server. Click activity in the personal inventory is
 * therefore tracked briefly as a best-effort signal; open containers are tracked
 * directly through Bukkit inventory lifecycle events.
 */
public final class InvHitCheck implements Listener {
    private static final long DEFAULT_PERSONAL_CLICK_GRACE_MILLIS = 450L;

    private final KeenAC plugin;
    private final Set<UUID> openContainerViews = ConcurrentHashMap.newKeySet();
    private final Set<UUID> openPersonalViews = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> recentPersonalInventoryActivity = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> violationLevels = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAlertNanos = new ConcurrentHashMap<>();

    public InvHitCheck(KeenAC plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        UUID id = player.getUniqueId();
        if (event.getView().getType() == InventoryType.CRAFTING) {
            openPersonalViews.add(id);
            openContainerViews.remove(id);
        } else {
            openContainerViews.add(id);
            openPersonalViews.remove(id);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        openContainerViews.remove(id);
        openPersonalViews.remove(id);
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
            rememberPersonalInventoryActivity(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && event.getView().getType() == InventoryType.CRAFTING
                && !event.getRawSlots().isEmpty()) {
            rememberPersonalInventoryActivity(player);
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

    private void rememberPersonalInventoryActivity(Player player) {
        long graceMillis = Math.max(100L,
                plugin.getConfig().getLong("checks.inv-hit.personal-inventory-click-grace-ms",
                        DEFAULT_PERSONAL_CLICK_GRACE_MILLIS));
        recentPersonalInventoryActivity.put(player.getUniqueId(),
                System.nanoTime() + graceMillis * 1_000_000L);
    }

    /**
     * Safe to call from a PacketEvents callback: this method reads only
     * concurrent collections and does not access Bukkit world/inventory state.
     */
    public boolean isInventoryOpen(UUID playerId) {
        if (openContainerViews.contains(playerId) || openPersonalViews.contains(playerId)) {
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
        openPersonalViews.remove(id);
        recentPersonalInventoryActivity.remove(id);
        violationLevels.remove(id);
        lastAlertNanos.remove(id);
    }

    public void clearAll() {
        openContainerViews.clear();
        openPersonalViews.clear();
        recentPersonalInventoryActivity.clear();
        violationLevels.clear();
        lastAlertNanos.clear();
    }
}
