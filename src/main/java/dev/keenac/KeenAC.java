package dev.keenac;

import com.github.retrooper.packetevents.PacketEvents;
import dev.keenac.alert.AlertManager;
import dev.keenac.command.KeenACCommand;
import dev.keenac.context.PlayerContextManager;
import dev.keenac.engine.core.bedrock.GeyserManager;
import dev.keenac.engine.core.check.CheckManager;
import dev.keenac.engine.core.compensation.LatencyCompensator;
import dev.keenac.engine.core.compensation.PingPongManager;
import dev.keenac.engine.core.compensation.SimulationEngine;
import dev.keenac.engine.core.compensation.TransactionManager;
import dev.keenac.engine.core.config.KeenACConfig;
import dev.keenac.engine.core.network.PacketListener;
import dev.keenac.engine.core.network.WorldChangeListener;
import dev.keenac.engine.core.player.PlayerManager;
import dev.keenac.engine.core.player.TrackedPlayer;
import dev.keenac.engine.core.platform.FoliaCompat;
import dev.keenac.engine.core.platform.PurpurCompat;
import dev.keenac.engine.core.plugin.PluginDetector;
import dev.keenac.engine.core.severity.SeverityManager;
import dev.keenac.engine.core.version.ServerFork;
import dev.keenac.engine.core.version.VersionManager;
import dev.keenac.evidence.EvidenceManager;
import dev.keenac.listener.AlertLifecycleListener;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class KeenAC extends JavaPlugin {
    private static KeenAC instance;
    private static boolean packetEventsLoaded;

    private AlertManager alertManager;
    private PlayerContextManager playerContextManager;
    private EvidenceManager evidenceManager;

    private KeenACConfig keenACConfig;
    private VersionManager versionManager;
    private ServerFork serverFork;
    private PluginDetector pluginDetector;
    private FoliaCompat foliaCompat;
    private PurpurCompat purpurCompat;
    private PlayerManager playerManager;
    private TransactionManager transactionManager;
    private PingPongManager pingPongManager;
    private LatencyCompensator latencyCompensator;
    private SimulationEngine simulationEngine;
    private GeyserManager geyserManager;
    private SeverityManager severityManager;
    private CheckManager checkManager;
    private BukkitTask tickTask;

    public static KeenAC getInstance() {
        return instance;
    }

    @Override
    public void onLoad() {
        instance = this;
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
        packetEventsLoaded = true;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        // Existing alert, evidence and review systems remain the source of staff notifications.
        playerContextManager = new PlayerContextManager();
        Bukkit.getPluginManager().registerEvents(playerContextManager, this);
        evidenceManager = new EvidenceManager(this, playerContextManager);
        alertManager = new AlertManager(this);
        for (Player online : Bukkit.getOnlinePlayers()) alertManager.enableForStaff(online);
        Bukkit.getPluginManager().registerEvents(new AlertLifecycleListener(alertManager), this);

        keenACConfig = new KeenACConfig(this);
        versionManager = new VersionManager();
        serverFork = ServerFork.detect(getLogger());
        pluginDetector = new PluginDetector();
        pluginDetector.init(this);
        foliaCompat = new FoliaCompat(serverFork.isFolia());
        foliaCompat.init(getLogger());
        purpurCompat = new PurpurCompat();
        purpurCompat.init(serverFork, getLogger());

        playerManager = new PlayerManager();
        transactionManager = new TransactionManager(this);
        pingPongManager = new PingPongManager(this);
        latencyCompensator = new LatencyCompensator();
        simulationEngine = new SimulationEngine(pingPongManager, latencyCompensator);
        geyserManager = GeyserManager.init(this);
        severityManager = SeverityManager.fromConfig(keenACConfig);
        checkManager = new CheckManager(this);

        PacketEvents.getAPI().getEventManager().registerListener(new PacketListener(this));
        PacketEvents.getAPI().init();
        Bukkit.getPluginManager().registerEvents(new WorldChangeListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerLifecycleListener(), this);

        // PacketEvents creates tracked sessions at login. This also covers players already online
        // during /reload or plugin enable, when a login-success packet has already passed.
        for (Player online : Bukkit.getOnlinePlayers()) ensureTracked(online);

        tickTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (checkManager != null) checkManager.onTick();
        }, 1L, 1L);

        KeenACCommand executor = new KeenACCommand(this);
        if (getCommand("keenac") != null) {
            getCommand("keenac").setExecutor(executor);
            getCommand("keenac").setTabCompleter(executor);
        }

        getLogger().info("KeenAC enabled: " + checkManager.getChecks().size()
                + " checks registered; player/world tracking and transaction compensation active.");
    }

    private void ensureTracked(Player player) {
        if (playerManager == null || playerManager.get(player.getUniqueId()) != null) return;
        var user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        playerManager.add(new TrackedPlayer(player, user));
    }

    public AlertManager getAlertManager() { return alertManager; }
    public PlayerContextManager getPlayerContextManager() { return playerContextManager; }
    public EvidenceManager getEvidenceManager() { return evidenceManager; }

    public KeenACConfig getKeenACConfig() { return keenACConfig; }
    public VersionManager getVersionManager() { return versionManager; }
    public ServerFork getServerFork() { return serverFork; }
    public PluginDetector getPluginDetector() { return pluginDetector; }
    public FoliaCompat getFoliaCompat() { return foliaCompat; }
    public PurpurCompat getPurpurCompat() { return purpurCompat; }
    public PlayerManager getPlayerManager() { return playerManager; }
    public TransactionManager getTransactionManager() { return transactionManager; }
    public PingPongManager getPingPongManager() { return pingPongManager; }
    public LatencyCompensator getLatencyCompensator() { return latencyCompensator; }
    public SimulationEngine getSimulationEngine() { return simulationEngine; }
    public GeyserManager getGeyserManager() { return geyserManager; }
    public SeverityManager getSeverityManager() { return severityManager; }
    public CheckManager getCheckManager() { return checkManager; }

    @Override
    public void onDisable() {
        if (tickTask != null) tickTask.cancel();
        if (alertManager != null) alertManager.clearAll();
        if (evidenceManager != null) evidenceManager.clearAll();
        if (playerContextManager != null) playerContextManager.clearAll();
        if (playerManager != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (checkManager != null) checkManager.removePlayer(player.getUniqueId());
                playerManager.remove(player.getUniqueId());
            }
        }
        if (packetEventsLoaded) {
            try {
                PacketEvents.getAPI().terminate();
            } catch (RuntimeException ignored) {
                // PacketEvents may already be terminating as part of server shutdown.
            }
            packetEventsLoaded = false;
        }
        instance = null;
    }

    private final class PlayerLifecycleListener implements Listener {
        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            ensureTracked(event.getPlayer());
            if (alertManager != null) alertManager.enableForStaff(event.getPlayer());
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            var uuid = event.getPlayer().getUniqueId();
            if (checkManager != null) checkManager.removePlayer(uuid);
            if (playerManager != null) playerManager.remove(uuid);
            if (geyserManager != null) geyserManager.onPlayerQuit(uuid);
        }
    }
}
