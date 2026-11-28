package dev.keenac.listener;

import dev.keenac.alert.AlertManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerLifecycleListener implements Listener {
    private final AlertManager alerts;

    public PlayerLifecycleListener(AlertManager alerts) {
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
