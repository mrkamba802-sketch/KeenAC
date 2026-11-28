package dev.keenac.listener;

import dev.keenac.alert.AlertManager;
import dev.keenac.check.AirStuckCheck;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class PlayerLifecycleListener implements Listener {
    private final AirStuckCheck check;
    private final AlertManager alerts;

    public PlayerLifecycleListener(AirStuckCheck check, AlertManager alerts) {
        this.check = check;
        this.alerts = alerts;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        alerts.enableForStaff(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) { reset(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) { reset(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) { reset(event.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        check.forget(event.getPlayer().getUniqueId());
        alerts.clear(event.getPlayer());
    }

    private void reset(Player player) {
        check.reset(player);
    }
}
