package dev.keenac.engine.core.check;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import dev.keenac.KeenAC;
import dev.keenac.engine.core.adaptive.AdaptiveThreshold;
import dev.keenac.engine.core.check.impl.combat.*;
import dev.keenac.engine.core.check.impl.inventory.InventoryCheck;
import dev.keenac.engine.core.check.impl.movement.*;
import dev.keenac.engine.core.check.impl.packet.*;
import dev.keenac.engine.core.compensation.LatencyCompensator;
import dev.keenac.engine.core.compensation.PingPongManager;
import dev.keenac.engine.core.compensation.SimulationEngine;
import dev.keenac.engine.core.fingerprint.PacketFingerprint;
import dev.keenac.engine.core.player.PlayerManager;
import dev.keenac.engine.core.player.TrackedPlayer;
import dev.keenac.engine.core.plugin.PluginDetector;
import dev.keenac.engine.core.platform.FoliaCompat;
import dev.keenac.engine.core.platform.PurpurCompat;
import dev.keenac.engine.core.severity.ViolationPattern;
import dev.keenac.engine.core.version.ServerFork;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
public final class CheckManager {
    private final KeenAC plugin;
    private final List<Check> checks = new ArrayList<>();
    private final Map<String, Check> checkByKey = new ConcurrentHashMap<>();
    private final int serverProtocol;
    private final ServerFork serverFork;
    private final PluginDetector pluginDetector;
    private final FoliaCompat foliaCompat;
    private final PurpurCompat purpurCompat;
    private final PingPongManager pingPongManager;
    private final LatencyCompensator latencyCompensator;
    private final SimulationEngine simulationEngine;
    private final AdaptiveThreshold adaptiveThreshold;
    private final ViolationPattern violationPattern;
    private final PacketFingerprint packetFingerprint = new PacketFingerprint();
    private long tickCounter;
    private long lastTickNanos;
    public CheckManager(KeenAC plugin) {
        this.plugin = plugin;
        serverProtocol = plugin.getVersionManager().getProtocolVersion();
        serverFork = plugin.getServerFork();
        pluginDetector = plugin.getPluginDetector();
        foliaCompat = plugin.getFoliaCompat();
        purpurCompat = plugin.getPurpurCompat();
        pingPongManager = plugin.getPingPongManager();
        latencyCompensator = plugin.getLatencyCompensator();
        simulationEngine = plugin.getSimulationEngine();
        adaptiveThreshold = AdaptiveThreshold.getInstance();
        adaptiveThreshold.loadConfig(plugin.getKeenACConfig());
        violationPattern = new ViolationPattern(plugin.getDataFolder().toPath(), plugin.getLogger());
        violationPattern.loadConfig(
                plugin.getKeenACConfig().isPatternEnabled(),
                plugin.getKeenACConfig().getPatternHistoryDays(),
                plugin.getKeenACConfig().getPatternRepeatThreshold(),
                plugin.getKeenACConfig().getPatternToggleDetectionWindow());
        packetFingerprint.loadConfig(
                plugin.getKeenACConfig().isFingerprintEnabled(),
                plugin.getKeenACConfig().getFingerprintMinSeverityToFlag(),
                plugin.getKeenACConfig().getFingerprintMaxAgeTicks());
        registerChecks();
    }
    private void registerChecks() {
        List<Check> allChecks = new ArrayList<>();
        allChecks.add(new AimCheck());
        allChecks.add(new AutoclickerCheck());
        allChecks.add(new BacktrackCheck());
        allChecks.add(new HitboxesCheck());
        allChecks.add(new MultiInteractCheck());
        allChecks.add(new SelfInteractCheck());
        allChecks.add(new ReachCheck());
        allChecks.add(new InteractCursorCheck());
        allChecks.add(new CriticalsCheck());
        allChecks.add(new KillAuraCheck());
        allChecks.add(new FastHealCheck());
        allChecks.add(new SwordBlockCheck());
        allChecks.add(new MacroCheck());
        allChecks.add(new CrystalCheck());
        allChecks.add(new SpeedCheck());
        allChecks.add(new FlightCheck());
        allChecks.add(new VelocityCheck());
        allChecks.add(new TimerCheck());
        allChecks.add(new NoFallCheck());
        allChecks.add(new StepCheck());
        allChecks.add(new ScaffoldCheck());
        allChecks.add(new ScaffoldSupportCheck());
        allChecks.add(new GravityCheck());
        allChecks.add(new ElytraCheck());
        allChecks.add(new BaritoneCheck());
        allChecks.add(new GroundSpoofCheck());
        allChecks.add(new PhaseCheck());
        allChecks.add(new SimulationCheck());
        allChecks.add(new OmniSprintCheck());
        allChecks.add(new NoSlowCheck());
        allChecks.add(new MotionCheck());
        allChecks.add(new IllegalMoveCheck());
        allChecks.add(new FastBreakCheck());
        allChecks.add(new FarBreakCheck());
        allChecks.add(new FarPlaceCheck());
        allChecks.add(new InvalidBreakCheck());
        allChecks.add(new InvalidPlaceCheck());
        allChecks.add(new InvalidPlaceCursorCheck());
        allChecks.add(new NoSwingCheck());
        allChecks.add(new RotationBreakCheck());
        allChecks.add(new AirLiquidBreakCheck());
        allChecks.add(new WrongBreakCheck());
        allChecks.add(new PositionBreakCheck());
        allChecks.add(new MultiBreakCheck());
        allChecks.add(new AirLiquidPlaceCheck());
        allChecks.add(new RotationPlaceCheck());
        allChecks.add(new PositionPlaceCheck());
        allChecks.add(new MultiPlaceCheck());
        allChecks.add(new BadPacketsCheck());
        allChecks.add(new ChestStealerCheck());
        allChecks.add(new CreativeCheck());
        allChecks.add(new PacketOrderCheck());
        allChecks.add(new ChatCheck());
        allChecks.add(new CrashCheck());
        allChecks.add(new SprintCheck());
        allChecks.add(new ExploitCheck());
        allChecks.add(new ClientBrandCheck());
        allChecks.add(new VehicleCheck());
        allChecks.add(new InventoryCheck());
        allChecks.add(new TransactionCheck());
        int skippedVersion = 0;
        int skippedFork = 0;
        int skippedPlugin = 0;
        for (Check check : allChecks) {
            String skipReason = getSkipReason(check);
            if (skipReason != null) {
                plugin.getLogger().info("Skipping " + check.getName() + " (" + skipReason + ")");
                if (skipReason.startsWith("version")) skippedVersion++;
                else if (skipReason.startsWith("fork")) skippedFork++;
                else skippedPlugin++;
                continue;
            }
            checks.add(check);
            checkByKey.put(check.getStableKey(), check);
        }
        plugin.getLogger().info("Registered " + checks.size() + "/" + allChecks.size()
                + " checks for protocol " + serverProtocol + " (" + serverFork.getDisplayName() + ")");
        if (skippedVersion + skippedFork + skippedPlugin > 0) {
            plugin.getLogger().info("Skipped " + skippedVersion + " version, "
                    + skippedFork + " fork, " + skippedPlugin + " plugin-incompatible checks");
        }
    }
    private String getSkipReason(Check check) {
        if (serverProtocol < check.getMinVersion() || serverProtocol > check.getMaxVersion()) {
            return "version: requires " + check.getMinVersion() + "-" + check.getMaxVersion()
                    + ", server=" + serverProtocol;
        }
        if (check.isDisableOnFolia() && serverFork.isFolia()) return "fork: disabled on Folia";
        if (check.isDisableOnPurpur() && serverFork.isPurpur()) return "fork: disabled on Purpur";
        if (check.hasCompatFlag(CompatFlag.VERSION_LEGACY)
                && serverProtocol < 107 && pluginDetector.isOldCombatMechanicsInstalled()) return null;
        return null;
    }
    public void onPacketReceive(TrackedPlayer player, PacketReceiveEvent event) {
        if (player == null || !player.isValid()) return;
        for (Check check : checks) {
            if (!check.isEnabled()) continue;
            try {
                check.onPacketReceive(player, event);
            } catch (Exception exception) {
                plugin.getLogger().fine("Check " + check.getName()
                        + " packet error: " + exception.getMessage());
            }
        }
    }
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
        if (player == null || !player.isValid()) return;
        for (Check check : checks) {
            if (!check.isEnabled()) continue;
            try {
                check.onPacketSend(player, event);
            } catch (Exception exception) {
                plugin.getLogger().fine("Check " + check.getName()
                        + " packet error: " + exception.getMessage());
            }
        }
    }
    public void onTransactionResponse(TrackedPlayer player, boolean matched) {
        for (Check check : checks) {
            if (check instanceof TransactionCheck transactionCheck) {
                transactionCheck.onTransactionResponse(player, matched);
                return;
            }
        }
    }
    public void onTick() {
        long nowNanos = System.nanoTime();
        long tickDurationMs = lastTickNanos == 0L ? 50L
                : Math.max(1L, Math.min(1_000L, (nowNanos - lastTickNanos) / 1_000_000L));
        lastTickNanos = nowNanos;
        adaptiveThreshold.onTick(tickDurationMs);
        PlayerManager playerManager = plugin.getPlayerManager();
        for (TrackedPlayer player : playerManager.getAllPlayers()) {
            if (!player.isValid()) continue;
            pingPongManager.onTickStart(player);
            player.resetTickState();
            player.getActionData().tick();
            player.updateCachedState();
            latencyCompensator.processDeferredChanges(player.getUuid(), player);
            if (player.getTrustFactor() != null) player.getTrustFactor().increaseTrust();
            for (Check check : checks) {
                if (!check.isEnabled()) continue;
                check.reward(player);
                if (check instanceof ScaffoldCheck scaffoldCheck) scaffoldCheck.onTick(player, tickCounter);
                if (check instanceof TransactionCheck transactionCheck) transactionCheck.onTick(player);
            }
            pingPongManager.onTickEnd(player);
        }
        if (tickCounter++ % 200 == 0) ReachCheck.cleanup(10_000L);
        if (tickCounter % 6000 == 0) {
            packetFingerprint.onTick(tickCounter);
            violationPattern.pruneOldHistories();
            latencyCompensator.pruneTickHistory((int) tickCounter);
        }
    }
    public void reloadChecks() {
        plugin.getKeenACConfig().reload();
        adaptiveThreshold.loadConfig(plugin.getKeenACConfig());
        for (Check check : checks) {
            String key = check.getStableKey();
            check.setEnabled(plugin.getKeenACConfig().isCheckEnabled(key));
            check.setPunishable(plugin.getKeenACConfig().isCheckPunishable(key));
            check.setMaxVl(plugin.getKeenACConfig().getCheckMaxVl(key));
            Integer setback = plugin.getKeenACConfig().getExplicitCheckSetbackVl(key);
            if (setback != null) check.setSetbackVl(setback);
            Double decay = plugin.getKeenACConfig().getExplicitCheckDecay(key);
            if (decay != null) check.setDecay(decay);
        }
    }
    public void removePlayer(UUID uuid) {
        for (Check check : checks) {
   ACConfig().getEew Backgin.getKeenACCeck());
        allChecwr                            allChecks.add(new MultiInteractCheck());
        allChecks.add(new SelfInteractCheck());
        allChecks.add(new Reachck);
                            check.getMaxVersionTimeMillis();
      (neeck.getMaxVersionTimeMillis();        ‡£M5J_Olt_OltY: checpgetMa            checkChecks.add(new Reachck);
     cks.add(new Reachck);
.player.PlayerManager.
import dev.keenac.engine.core.player.Tracke Reach        } else {
                state.clippingT                                                        ingT     
      >e("Foli String clientVersion              