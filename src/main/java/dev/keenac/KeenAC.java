package dev.keenac;

import dev.keenac.alert.AlertManager;
import dev.keenac.command.KeenACCommand;
import dev.keenac.context.PlayerContextManager;
import dev.keenac.evidence.EvidenceManager;
import dev.keenac.listener.AlertLifecycleListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class KeenAC extends JavaPlugin {
    private AlertManager alertManager;
    private PlayerContextManager playerContextManager;
    private EvidenceManager evidenceManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        playerContextManager = new PlayerContextManager();
        Bukkit.getPluginManager().registerEvents(playerContextManager, this);

        evidenceManager = new EvidenceManager(this, playerContextManager);
        alertManager = new AlertManager(this);

        for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
            alertManager.enableForStaff(online);
        }
        Bukkit.getPluginManager().registerEvents(
                new AlertLifecycleListener(alertManager), this);

        KeenACCommand executor = new KeenACCommand(this);
        getCommand("keenac").setExecutor(executor);
        getCommand("keenac").setTabCompleter(executor);

        getLogger().info("KeenAC core enabled. No detection checks are registered.");
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
        if (alertManager != null) alertManager.clearAll();
        if (evidenceManager != null) evidenceManager.clearAll();
        if (playerContextManager != null) playerContextManager.clearAll();
    }
}
