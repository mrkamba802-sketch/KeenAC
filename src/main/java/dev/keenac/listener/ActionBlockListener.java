package dev.keenac.listener;

import dev.keenac.check.AirStuckCheck;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Applies AirStuck mitigation only to block breaking and placement.
 * Combat, inventory, item use, and ordinary movement remain untouched.
 */
public final class ActionBlockListener implements Listener {
    private final AirStuckCheck check;

    public ActionBlockListener(AirStuckCheck check) {
        this.check = check;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (check.shouldCancelBlockActions() && check.isFlagged(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (check.shouldCancelBlockActions() && check.isFlagged(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
}
