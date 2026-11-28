package dev.keenac;

import com.github.retrooper.packetevents.PacketEvents;
import dev.keenac.alert.AlertManager;
import dev.keenac.check.AirStuckCheck;
import dev.keenac.command.KeenACCommand;
import dev.keenac.context.PlayerContextManager;
import dev.keenac.evidence.EvidenceManager;
import dev.keenac.listener.PlayerLifecycleListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class KeenAC extends JavaPlugin {

    private AlertManager alertManager;
    private PlayerContextManager playerContextManager;
    private EvidenceManager evidenceManager;
    private AirStuckCheck airStuckCheck;

    @Override
    public void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("packetevents") == null
                || !Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
            getLogger().severe("PacketEvents is required. KeenAC will be disabled.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();

        playerContextManager = new PlayerContextManager();
        Bukkit.getPluginManager().registerEvents(playerContextManager, this);
        evidenceManager = new EvidenceManager(this, playerContextManager);
        alertManager = new AlertManager(this);

        for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
            alertManager.enableForStaff(online);
        }

        airStuckCheck = new AirStuckCheck(this);
        PacketEvents.getAPI().getEventManager().registerListener(airStuckCheck);
        Bukkit.getPluginManager().registerEvents(
                new PlayerLifecycleListener(alertManager, airStuckCheck), this);

        KeenACCommand executor = new KeenACCommand(this);
        getCommand("keenac").setExecutor(executor);
        getCommand("keenac").setTabCompleter(executor);

        getLogger().info("KeenAC enabled with AirStuck detection and staff alerts.");
    }

    public AlertManager getAlertManager() {
        return alertManager;
    }

    public PlayerContextManager getPlayerContextManager() {
        return playerContextManager;
    }

    public EvidenceManager getEvidenceManager() {
        return evidenceManager;
    }

    @Override
    public void onDisable() {
        if (airStuckCheck != null) {
            if (PacketEvents.getAPI() != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(airStuckCheck);
            }
            airStuckCheck.clearAll();
        }
        if (alertManager != null) alertManager.clearAll();
        if (evidenceManager != null) evidenceManager.clearAll();
        if (playerContextManager != null) playerContextManager.clearAll();
    }
}
