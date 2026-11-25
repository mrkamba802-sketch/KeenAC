package dev.keenac;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import dev.keenac.alert.AlertManager;
import dev.keenac.check.AirStuckCheck;
import dev.keenac.check.AuraCheck;
import dev.keenac.check.BaritoneCheck;
import dev.keenac.check.InventoryAutoSwapCheck;
import dev.keenac.check.InvHitCheck;
import dev.keenac.check.MotionCheck;
import dev.keenac.check.NoFallCheck;
import dev.keenac.check.WallHitCheck;
import dev.keenac.check.NoSlowCheck;
import dev.keenac.check.NoWebCheck;
import dev.keenac.check.ReachCheck;
import dev.keenac.command.KeenACCommand;
import dev.keenac.listener.ActionBlockListener;
import dev.keenac.listener.PacketAirStuckListener;
import dev.keenac.listener.PacketAutoSwapListener;
import dev.keenac.listener.PacketAuraListener;
import dev.keenac.listener.PacketBaritoneListener;
import dev.keenac.listener.PacketInvHitListener;
import dev.keenac.listener.PacketReachListener;
import dev.keenac.listener.PlayerLifecycleListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class KeenAC extends JavaPlugin {

    private AirStuckCheck airStuckCheck;
    private InventoryAutoSwapCheck inventoryAutoSwapCheck;
    private InvHitCheck invHitCheck;
    private AuraCheck auraCheck;
    private ReachCheck reachCheck;
    private MotionCheck motionCheck;
    private NoFallCheck noFallCheck;
    private BaritoneCheck baritoneCheck;
    private AlertManager alertManager;
    private PacketListenerAbstract packetListener;
    private PacketListenerAbstract invHitPacketListener;
    private PacketListenerAbstract autoSwapPacketListener;
    private PacketListenerAbstract auraPacketListener;
    private PacketListenerAbstract reachPacketListener;
    private PacketListenerAbstract baritonePacketListener;
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
        alertManager = new AlertManager(this);
        for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
            alertManager.enableForStaff(online);
        }
        airStuckCheck = new AirStuckCheck(this);
        inventoryAutoSwapCheck = new InventoryAutoSwapCheck(this);
        invHitCheck = new InvHitCheck(this);
        auraCheck = new AuraCheck(this);
        reachCheck = new ReachCheck(this);
        motionCheck = new MotionCheck(this);
        noFallCheck = new NoFallCheck(this);
        baritoneCheck = new BaritoneCheck(this);
        packetListener = new PacketAirStuckListener(airStuckCheck, motionCheck, noFallCheck);
        invHitPacketListener = new PacketInvHitListener(this, invHitCheck);
        autoSwapPacketListener = new PacketAutoSwapListener(inventoryAutoSwapCheck);
        auraPacketListener = new PacketAuraListener(auraCheck);
        reachPacketListener = new PacketReachListener(reachCheck);
        baritonePacketListener = new PacketBaritoneListener(baritoneCheck);
        PacketEvents.getAPI().getEventManager().registerListener(packetListener);
        PacketEvents.getAPI().getEventManager().registerListener(invHitPacketListener);
        PacketEvents.getAPI().getEventManager().registerListener(autoSwapPacketListener);
        PacketEvents.getAPI().getEventManager().registerListener(auraPacketListener);
        PacketEvents.getAPI().getEventManager().registerListener(reachPacketListener);
        PacketEvents.getAPI().getEventManager().registerListener(baritonePacketListener);

        Bukkit.getPluginManager().registerEvents(inventoryAutoSwapCheck, this);
        Bukkit.getPluginManager().registerEvents(invHitCheck, this);
        Bukkit.getPluginManager().registerEvents(new PlayerLifecycleListener(airStuckCheck, alertManager), this);
        Bukkit.getPluginManager().registerEvents(auraCheck, this);
        Bukkit.getPluginManager().registerEvents(reachCheck, this);
        Bukkit.getPluginManager().registerEvents(motionCheck, this);
        Bukkit.getPluginManager().registerEvents(noFallCheck, this);
        Bukkit.getPluginManager().registerEvents(baritoneCheck, this);
        Bukkit.getPluginManager().registerEvents(new WallHitCheck(this), this);
        Bukkit.getPluginManager().registerEvents(new NoSlowCheck(this), this);
        Bukkit.getPluginManager().registerEvents(new NoWebCheck(this), this);

        KeenACCommand executor = new KeenACCommand(this);
        getCommand("keenac").setExecutor(executor);
        getCommand("keenac").setTabCompleter(executor);

        tickTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            airStuckCheck.tick();
            auraCheck.tick();
            reachCheck.tick();
            motionCheck.tick();
            noFallCheck.tick();
            baritoneCheck.tick();
        }, 1L, 1L);
        getLogger().info("KeenAC enabled with PacketEvents " + PacketEvents.getAPI().getVersion() + ".");
    }

    public MotionCheck getMotionCheck() {
        return motionCheck;
    }

    public AlertManager getAlertManager() {
        return alertManager;
    }

    public void reloadChecks() {
        if (inventoryAutoSwapCheck != null) inventoryAutoSwapCheck.reloadSettings();
        if (auraCheck != null) auraCheck.reloadSettings();
        if (reachCheck != null) reachCheck.reloadSettings();
    }

    @Override
    public void onDisable() {
        if (tickTask != null) tickTask.cancel();
        if (PacketEvents.getAPI() != null) {
            if (packetListener != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
            }
            if (invHitPacketListener != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(invHitPacketListener);
            }
            if (autoSwapPacketListener != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(autoSwapPacketListener);
            }
            if (auraPacketListener != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(auraPacketListener);
            }
            if (reachPacketListener != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(reachPacketListener);
            }
            if (baritonePacketListener != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(baritonePacketListener);
            }
        }
        if (airStuckCheck != null) airStuckCheck.clearAll();
        if (inventoryAutoSwapCheck != null) inventoryAutoSwapCheck.clearAll();
        if (invHitCheck != null) invHitCheck.clearAll();
        if (auraCheck != null) auraCheck.clearAll();
        if (reachCheck != null) reachCheck.clearAll();
        if (motionCheck != null) motionCheck.clearAll();
        if (noFallCheck != null) noFallCheck.clearAll();
        if (baritoneCheck != null) baritoneCheck.clearAll();
        if (alertManager != null) alertManager.clearAll();
    }
}
