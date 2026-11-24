package dev.keenac;

import dev.keenac.check.AirStuckCheck;
import dev.keenac.listener.ActionBlockListener;
import dev.keenac.listener.PlayerLifecycleListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class KeenAC extends JavaPlugin {

    private AirStuckCheck airStuckCheck;
    private BukkitTask tickTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        airStuckCheck = new AirStuckCheck(this);
        Bukkit.getPluginManager().registerEvents(
                new ActionBlockListener(airStuckCheck), this);
        Bukkit.getPluginManager().registerEvents(
                new PlayerLifecycleListener(airStuckCheck), this);

        // Sample online players once per server tick on the main thread.
        tickTask = Bukkit.getScheduler().runTaskTimer(
                this, airStuckCheck::tick, 1L, 1L);

        getLogger().info("KeenAC enabled. AirStuck check is "
                + (getConfig().getBoolean("checks.air-stuck.enabled", true) ? "enabled." : "disabled."));
    }

    @Override
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        if (airStuckCheck != null) {
            airStuckCheck.clearAll();
        }
    }
}
