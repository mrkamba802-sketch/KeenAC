package dev.keenac.engine.core.check;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import dev.keenac.KeenAC;
import dev.keenac.engine.core.check.impl.combat.*;
import dev.keenac.engine.core.check.impl.inventory.InventoryCheck;
import dev.keenac.engine.core.check.impl.movement.*;
import dev.keenac.engine.core.check.impl.packet.*;
import dev.keenac.engine.core.compensation.LatencyCompensator;
import dev.keenac.engine.core.compensation.PingPongManager;
import dev.keenac.engine.core.player.PlayerManager;
import dev.keenac.engine.core.player.TrackedPlayer;
import dev.keenac.engine.core.severity.ViolationPattern;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Owns check registration, packet dispatch, decay and player-state cleanup. */
public final class CheckManager {
    private final KeenAC plugin;
    private final List<Check> checks = new ArrayList<>(60);
    private final Map<String, Check> byKey = new ConcurrentHashMap<>();
    private final ViolationPattern violationPattern;
    private long tickCounter;

    public CheckManager(KeenAC plugin) {
        this.plugin = plugin;
        this.violationPattern = new ViolationPattern(
                plugin.getDataFolder().toPath(), plugin.getLogger());
        var config = plugin.getKeenACConfig();
        violationPattern.loadConfig(
                config.isPatternEnabled(),
                config.getPatternHistoryDays(),
                config.getPatternRepeatThreshold(),
                config.getPatternToggleDetectionWindow());
        registerChecks();
    }

    private void registerChecks() {
        // Combat (14)
        register(new AimCheck());
        register(new AutoclickerCheck());
        register(new BacktrackCheck());
        register(new CriticalsCheck());
        register(new CrystalCheck());
        register(new FastHealCheck());
        register(new HitboxesCheck());
        register(new InteractCursorCheck());
        register(new KillAuraCheck());
        register(new MacroCheck());
        register(new MultiInteractCheck());
        register(new ReachCheck());
        register(new SelfInteractCheck());
        register(new SwordBlockCheck());

        // Inventory (1)
        register(new InventoryCheck());

        // Movement (34)
        register(new AirLiquidBreakCheck());
        register(new AirLiquidPlaceCheck());
        register(new BaritoneCheck());
        register(new ElytraCheck());
        register(new FarBreakCheck());
        register(new FarPlaceCheck());
        register(new FastBreakCheck());
        register(new FlightCheck());
        register(new GravityCheck());
        register(new GroundSpoofCheck());
        register(new IllegalMoveCheck());
        register(new InvalidBreakCheck());
        register(new InvalidPlaceCheck());
        register(new InvalidPlaceCursorCheck());
        register(new MotionCheck());
        register(new MultiBreakCheck());
        register(new MultiPlaceCheck());
        register(new NoFallCheck());
        register(new NoSlowCheck());
        register(new NoSwingCheck());
        register(new OmniSprintCheck());
        register(new PhaseCheck());
        register(new PositionBreakCheck());
        register(new PositionPlaceCheck());
        register(new RotationBreakCheck());
        register(new RotationPlaceCheck());
        register(new ScaffoldCheck());
        register(new ScaffoldSupportCheck());
        register(new SimulationCheck());
        register(new SpeedCheck());
        register(new StepCheck());
        register(new TimerCheck());
        register(new VelocityCheck());
        register(new WrongBreakCheck());

        // Packet (11)
        register(new BadPacketsCheck());
        register(new ChatCheck());
        register(new ChestStealerCheck());
        register(new ClientBrandCheck());
        register(new CrashCheck());
        register(new CreativeCheck());
        register(new ExploitCheck());
        register(new PacketOrderCheck());
        register(new SprintCheck());
        register(new TransactionCheck());
        register(new VehicleCheck());

        plugin.getLogger().info("Registered " + checks.size() + " KeenAC checks.");
    }

    private void register(Check check) {
        int protocol = plugin.getVersionManager().getProtocolVersion();
        boolean compatible = protocol >= check.getMinVersion() && protocol <= check.getMaxVersion();
        if (check.isDisableOnFolia() && plugin.getFoliaCompat().isFolia()) compatible = false;
        if (check.isDisableOnPurpur() && plugin.getPurpurCompat().isPurpur()) compatible = false;
        if (!compatible) check.setEnabled(false);
        check.setPunishable(plugin.getKeenACConfig().isCheckPunishable(check.getStableKey()));
        checks.add(check);
        byKey.put(check.getStableKey(), check);
    }

    public void onPacketReceive(TrackedPlayer player, PacketReceiveEvent event) {
        if (player == null || !player.isValid()) return;
        for (Check check : checks) {
            if (!check.isEnabled()) continue;
            try {
                check.onPacketReceive(player, event);
            } catch (Throwable error) {
                plugin.getLogger().warning("Check " + check.getName()
                        + " failed while reading an incoming packet: " + error.getMessage());
            }
        }
    }

    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
        if (player == null || !player.isValid()) return;
        for (Check check : checks) {
            if (!check.isEnabled()) continue;
            try {
                check.onPacketSend(player, event);
            } catch (Throwable error) {
                plugin.getLogger().warning("Check " + check.getName()
                        + " failed while reading an outgoing packet: " + error.getMessage());
            }
        }
    }

    public void onTransactionResponse(TrackedPlayer player, boolean matched) {
        Check check = byKey.get("keenac.packet.transaction");
        if (check instanceof TransactionCheck transactionCheck) {
            transactionCheck.onTransactionResponse(player, matched);
        }
    }

    /** Called once per server tick on the primary thread. */
    public void onTick() {
        tickCounter++;
        PlayerManager players = plugin.getPlayerManager();
        PingPongManager pingPong = plugin.getPingPongManager();
        LatencyCompensator latency = plugin.getLatencyCompensator();

        for (TrackedPlayer player : players.getAllPlayers()) {
            if (!player.isValid()) continue;
            player.updateCachedState();
            pingPong.onTickStart(player);
            latency.processDeferredChanges(player.getUuid(), player);

            for (Check check : checks) {
                if (!check.isEnabled()) continue;
                check.reward(player);
                if (check instanceof TransactionCheck transactionCheck) {
                    transactionCheck.onTick(player);
                }
            }

            pingPong.onTickEnd(player);
            player.resetTickState();
        }
    }

    public void removePlayer(java.util.UUID uuid) {
        for (Check check : checks) check.removePlayer(uuid);
        violationPattern.clearPlayerHistory(uuid);
        plugin.getPingPongManager().onPlayerQuit(uuid);
        plugin.getLatencyCompensator().onPlayerQuit(uuid);
        plugin.getTransactionManager().onPlayerQuit(uuid);
    }

    public void reloadChecks() {
        plugin.getKeenACConfig().reload();
        int protocol = plugin.getVersionManager().getProtocolVersion();
        for (Check check : checks) {
            String key = check.getStableKey();
            boolean compatible = protocol >= check.getMinVersion()
                    && protocol <= check.getMaxVersion();
            if (check.isDisableOnFolia() && plugin.getFoliaCompat().isFolia()) compatible = false;
            if (check.isDisableOnPurpur() && plugin.getPurpurCompat().isPurpur()) compatible = false;
            check.setEnabled(compatible && plugin.getKeenACConfig().isCheckEnabled(key));
            check.setMaxVl(plugin.getKeenACConfig().getCheckMaxVl(key));
            check.setPunishable(plugin.getKeenACConfig().isCheckPunishable(key));
            check.setSetbackVl(plugin.getKeenACConfig().getCheckSetbackVl(
                    key, check.getDefaultSetbackVl()));
            check.setDecay(plugin.getKeenACConfig().getCheckDecay(
                    key, check.getDefaultDecay()));
        }
    }

    public List<Check> getChecks() {
        return Collections.unmodifiableList(checks);
    }

    public Check getCheck(String stableKey) {
        return byKey.get(stableKey);
    }

    public ViolationPattern getViolationPattern() {
        return violationPattern;
    }

    public long getTickCounter() {
        return tickCounter;
    }
}
