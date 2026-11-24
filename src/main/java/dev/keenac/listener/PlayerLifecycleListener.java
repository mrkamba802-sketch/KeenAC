package dev.keenac.listener;

import dev.keenac.check.AirStuckCheck;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class PlayerLifecycleListener implements Listener {

    private final AirStuckCheck check;

    public PlayerLifecycleListener(AirStuckCheck check) {
        this.check = check;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        reset(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        reset(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        reset(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        check.forget(event.getPlayer().getUniqueId());
    }

    private void reset(Player player) {
        check.reset(player);
    }
}
