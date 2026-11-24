package dev.keenac;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import dev.keenac.check.AirStuckCheck;
import dev.keenac.listener.ActionBlockListener;
import dev.keenac.listener.PacketAirStuckListener;
import dev.keenac.listener.PlayerLifecycleListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class KeenAC extends JavaPlugin {

    private AirStuckCheck airStuckCheck;
    private PacketListenerAbstract packetListener;
    private BukkitTask tickTask;

    @Override
    public void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("packetevents") == null
                || !Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
            getLogger().severe("PacketEvents is required. KeenAC will be disabled.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();

        airStuckCheck = new AirStuckCheck(this);
        packetListener = new PacketAirStuckListener(this, airStuckCheck);
        PacketEvents.getAPI().getEventManager().registerListener(packetListener);

        Bukkit.getPluginManager().registerEvents(
                new ActionBlockListener(airStuckCheck), this);
        Bukkit.getPluginManager().registerEvents(
                new PlayerLifecycleListener(airStuckCheck), this);

        // Packet positions are sampled by PacketEvents; Bukkit tick task evaluates
        // collision support and advances the stationary-in-air counter safely.
        tickTask = Bukkit.getScheduler().runTaskTimer(
                this, airStuckCheck::tick, 1L, 1L);

        getLogger().info("KeenAC enabled with PacketEvents "
                + PacketEvents.getAPI().getVersion() + ".");
    }

    @Override
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        if (packetListener != null && PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
        }
        if (airStuckCheck != null) {
            airStuckCheck.clearAll();
        }
    }
}
