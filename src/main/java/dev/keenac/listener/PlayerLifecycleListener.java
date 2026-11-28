package dev.keenac.listener;

import dev.keenac.alert.AlertManager;
import dev.keenac.check.AirStuckCheck;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerLifecycleListener implements Listener {
    private final AlertManager alerts;
    private final AirStuckCheck airStuckCheck;

    public PlayerLifecycleListener(AlertManager alerts, AirStuckCheck airStuckCheck) {
        this.alerts = alerts;
        this.airStuckCheck = airStuckCheck;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        alerts.enableForStaff(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        airStuckCheck.cleanup(event.getPlayer().getUniqueId());
        alerts.clear(event.getPlayer());
    }
}
