package dev.keenac.listener;

import dev.keenac.alert.AlertManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Keeps alert preferences in sync with player sessions. */
public final class AlertLifecycleListener implements Listener {
    private final AlertManager alerts;

    public AlertLifecycleListener(AlertManager alerts) {
        this.alerts = alerts;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        alerts.enableForStaff(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        alerts.clear(event.getPlayer());
    }
}
