package dev.keenac;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import dev.keenac.alert.AlertManager;
import dev.keenac.context.PlayerContextManager;
import dev.keenac.evidence.EvidenceManager;
import dev.keenac.check.AirStuckCheck;
import dev.keenac.check.AuraCheck;
import dev.keenac.check.AimCheck;
import dev.keenac.check.BaritoneCheck;
import dev.keenac.check.BaritoneACheck;
import dev.keenac.check.InventoryAutoSwapCheck;
import dev.keenac.check.GhostMenuCheck;
import dev.keenac.check.InvHitCheck;
import dev.keenac.check.MotionCheck;
import dev.keenac.check.MovementSetbackManager;
import dev.keenac.check.WallHitCheck;
import dev.keenac.check.WallInteractCheck;
import dev.keenac.check.NoSlowCheck;
import dev.keenac.check.NoWebCheck;
import dev.keenac.check.ReachCheck;
import dev.keenac.check.ScaffoldCheck;
import dev.keenac.check.SilentSwapCheck;
import dev.keenac.check.WindfallCombatCheckSuite;
import dev.keenac.command.KeenACCommand;
import dev.keenac.listener.ActionBlockListener;
import dev.keenac.listener.PacketAirStuckListener;
import dev.keenac.listener.PacketAuraListener;
import dev.keenac.listener.PacketGameplayListener;
import dev.keenac.listener.PacketAutoSwapListener;
import dev.keenac.listener.PacketAimListener;
import dev.keenac.listener.PacketBaritoneListener;
import dev.keenac.listener.PacketInvHitListener;
import dev.keenac.listener.PacketGhostMenuListener;
import dev.keenac.listener.PacketReachListener;
import dev.keenac.listener.PacketSilentSwapListener;
import dev.keenac.listener.PlayerLifecycleListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class KeenAC extends JavaPlugin {

    private AirStuckCheck airStuckCheck;
    private AuraCheck auraCheck;
    private NoSlowCheck noSlowCheck;
    private NoWebCheck noWebCheck;
    private WallHitCheck wallHitCheck;
    private WallInteractCheck wallInteractCheck;
    private InventoryAutoSwapCheck inventoryAutoSwapCheck;
    private GhostMenuCheck ghostMenuCheck;
    private InvHitCheck invHitCheck;
    private AimCheck aimCheck;
    private ReachCheck reachCheck;
    private MotionCheck motionCheck;
    private MovementSetbackManager movementSetbackManager;
    private SilentSwapCheck silentSwapCheck;
    private BaritoneCheck baritoneCheck;
    private BaritoneACheck baritoneACheck;
    private ScaffoldCheck scaffoldCheck;
    private WindfallCombatCheckSuite windfallCombatCheckSuite;
    private AlertManager alertManager;
    private PlayerContextManager playerContextManager;
    private EvidenceManager evidenceManager;
    private PacketListenerAbstract packetListener;
    private PacketListenerAbstract auraPacketListener;
    private PacketListenerAbstract gameplayPacketListener;
    private PacketListenerAbstract invHitPacketListener;
    private PacketListenerAbstract autoSwapPacketListener;
    private PacketListenerAbstract aimPacketListener;
    private PacketListenerAbstract reachPacketListener;
    private PacketListenerAbstract baritonePacketListener;
    private PacketListenerAbstract ghostMenuPacketListener;
    private PacketListenerAbstract silentSwapPacketListener;
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
        playerContextManager = new PlayerContextManager();
        Bukkit.getPluginManager().registerEvents(playerContextManager, this);
        evidenceManager = new EvidenceManager(this, playerContextManager);
        alertManager = new AlertManager(this);
        movementSetbackManager = new MovementSetbackManager(this);
        Bukkit.getPluginManager().registerEvents(movementSetbackManager, this);
        for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
            alertManager.enableForStaff(online);
        }
        airStuckCheck = new AirStuckCheck(this);
        auraCheck = new AuraCheck(this);
        noSlowCheck = new NoSlowCheck(this);
        noWebCheck = new NoWebCheck(this);
        wallHitCheck = new WallHitCheck(this);
        wallInteractCheck = new WallInteractCheck(this);
        inventoryAutoSwapCheck = new InventoryAutoSwapCheck(this);
        ghostMenuCheck = new GhostMenuCheck(this);
        invHitCheck = new InvHitCheck(this);
        aimCheck = new AimCheck(this);
        reachCheck = new ReachCheck(this);
        motionCheck = new MotionCheck(this);
        silentSwapCheck = new SilentSwapCheck(this);
        baritoneCheck = new BaritoneCheck(this);
        baritoneACheck = new BaritoneACheck(this);
        scaffoldCheck = new ScaffoldCheck(this);
        windfallCombatCheckSuite = new WindfallCombatCheckSuite(this);
        auraPacketListener = new PacketAuraListener(auraCheck);
        gameplayPacketListener = new PacketGameplayListener(
                this, noSlowCheck, noWebCheck, scaffoldCheck, wallHitCheck);
        packetListener = new PacketAirStuckListener(airStuckCheck, motionCheck);
        invHitPacketListener = new PacketInvHitListener(this, invHitCheck);
        autoSwapPacketListener = new PacketAutoSwapListener(inventoryAutoSwapCheck);
        aimPacketListener = new PacketAimListener(aimCheck);
        reachPacketListener = new PacketReachListener(reachCheck);
        baritonePacketListener = new PacketBaritoneListener(baritoneCheck, baritoneACheck);
        ghostMenuPacketListener = new PacketGhostMenuListener(ghostMenuCheck);
        silentSwapPacketListener = new PacketSilentSwapListener(silentSwapCheck);
        PacketEvents.getAPI().getEventManager().registerListener(packetListener);
        PacketEvents.getAPI().getEventManager().registerListener(auraPacketListener);
        PacketEvents.getAPI().getEventManager().registerListener(gameplayPacketListener);
        PacketEvents.getAPI().getEventManager().registerListener(invHitPacketListener);
        PacketEvents.getAPI().getEventManager().registerListener(autoSwapPacketListener);
        PacketEvents.getAPI().getEventManager().registerListener(aimPacketListener);
        PacketEvents.getAPI().getEventManager().registerListener(reachPacketListener);
        PacketEvents.getAPI().getEventManager().registerListener(baritonePacketListener);
        PacketEvents.getAPI().getEventManager().registerListener(ghostMenuPacketListener);
        PacketEvents.getAPI().getEventManager().registerListener(silentSwapPacketListener);
        PacketEvents.getAPI().getEventManager().registerListener(windfallCombatCheckSuite);

        Bukkit.getPluginManager().registerEvents(inventoryAutoSwapCheck, this);
        Bukkit.getPluginManager().registerEvents(ghostMenuCheck, this);
        Bukkit.getPluginManager().registerEvents(new ActionBlockListener(airStuckCheck), this);
        Bukkit.getPluginManager().registerEvents(invHitCheck, this);
        Bukkit.getPluginManager().registerEvents(new PlayerLifecycleListener(airStuckCheck, alertManager), this);
        Bukkit.getPluginManager().registerEvents(aimCheck, this);
        Bukkit.getPluginManager().registerEvents(auraCheck, this);
        Bukkit.getPluginManager().registerEvents(reachCheck, this);
        Bukkit.getPluginManager().registerEvents(motionCheck, this);
        Bukkit.getPluginManager().registerEvents(baritoneCheck, this);
        Bukkit.getPluginManager().registerEvents(baritoneACheck, this);
        Bukkit.getPluginManager().registerEvents(scaffoldCheck, this);
        Bukkit.getPluginManager().registerEvents(wallHitCheck, this);
        Bukkit.getPluginManager().registerEvents(wallInteractCheck, this);
        Bukkit.getPluginManager().registerEvents(noSlowCheck, this);
        Bukkit.getPluginManager().registerEvents(noWebCheck, this);
        Bukkit.getPluginManager().registerEvents(windfallCombatCheckSuite, this);

        KeenACCommand executor = new KeenACCommand(this);
        getCommand("keenac").setExecutor(executor);
        getCommand("keenac").setTabCompleter(executor);

        tickTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            airStuckCheck.tick();
            aimCheck.tick();
            auraCheck.tick();
            reachCheck.tick();
            motionCheck.tick();
            silentSwapCheck.tick();
            ghostMenuCheck.tick();
            baritoneCheck.tick();
            baritoneACheck.tick();
        }, 1L, 1L);
        getLogger().info("KeenAC enabled with PacketEvents " + PacketEvents.getAPI().getVersion() + ".");
    }

    public MotionCheck getMotionCheck() {
        return motionCheck;
    }

    public MovementSetbackManager getMovementSetbackManager() {
        return movementSetbackManager;
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

    public void reloadChecks() {
        if (inventoryAutoSwapCheck != null) inventoryAutoSwapCheck.reloadSettings();
        if (silentSwapCheck != null) silentSwapCheck.reloadSettings();
        if (aimCheck != null) aimCheck.reloadSettings();
        if (auraCheck != null) auraCheck.reloadSettings();
        if (scaffoldCheck != null) scaffoldCheck.reloadSettings();
        if (reachCheck != null) reachCheck.reloadSettings();
    }

    @Override
    public void onDisable() {
        if (tickTask != null) tickTask.cancel();
        if (PacketEvents.getAPI() != null) {
            if (packetListener != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
            }
            if (auraPacketListener != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(auraPacketListener);
            }
            if (gameplayPacketListener != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(gameplayPacketListener);
            }
            if (invHitPacketListener != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(invHitPacketListener);
            }
            if (autoSwapPacketListener != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(autoSwapPacketListener);
            }
            if (aimPacketListener != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(aimPacketListener);
            }
            if (reachPacketListener != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(reachPacketListener);
            }
            if (baritonePacketListener != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(baritonePacketListener);
            }
            if (ghostMenuPacketListener != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(ghostMenuPacketListener);
            }
            if (silentSwapPacketListener != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(silentSwapPacketListener);
            }
        }
        if (airStuckCheck != null) airStuckCheck.clearAll();
        if (auraCheck != null) auraCheck.clearAll();
        if (noSlowCheck != null) noSlowCheck.clearAll();
        if (noWebCheck != null) noWebCheck.clearAll();
        if (wallHitCheck != null) wallHitCheck.clearAll();
        if (wallInteractCheck != null) wallInteractCheck.clearAll();
        if (inventoryAutoSwapCheck != null) inventoryAutoSwapCheck.clearAll();
        if (ghostMenuCheck != null) ghostMenuCheck.clearAll();
        if (invHitCheck != null) invHitCheck.clearAll();
        if (aimCheck != null) aimCheck.clearAll();
        if (reachCheck != null) reachCheck.clearAll();
        if (motionCheck != null) motionCheck.clearAll();
        if (movementSetbackManager != null) movementSetbackManager.clearAll();
        if (silentSwapCheck != null) silentSwapCheck.clearAll();
        if (baritoneCheck != null) baritoneCheck.clearAll();
        if (baritoneACheck != null) baritoneACheck.clearAll();
        if (scaffoldCheck != null) scaffoldCheck.clearAll();
        if (windfallCombatCheckSuite != null) windfallCombatCheckSuite.clearAll();
        if (alertManager != null) alertManager.clearAll();
        if (evidenceManager != null) evidenceManager.clearAll();
        if (playerContextManager != null) playerContextManager.clearAll();
    }
}
