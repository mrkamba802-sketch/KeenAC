package dev.keenac.engine.core.check.impl.movement;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import dev.keenac.KeenAC;
import dev.keenac.engine.core.bedrock.BedrockInfo;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.CompatFlag;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.player.TrackedPlayer;
import dev.keenac.engine.core.util.PacketClock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@CheckData(name = "Scaffold A", stableKey = "keenac.movement.scaffold", decay = 0.005, setbackVl = 30, compat = {CompatFlag.RELAX_ON_MISMATCH}, relaxMultiplier = 1.3, disableOnFolia = false)
public class ScaffoldCheck extends Check implements PacketCheck {
    private static final double JAVA_MAX_BLOCK_PLACE_PER_SECOND = 12.0;
    private static final double BEDROCK_TOUCH_MAX_BLOCKS_PER_SEC = 8.0;
    private static final double BEDROCK_KB_MAX_BLOCKS_PER_SEC = 10.0;
    private static final double BEDROCK_CONTROLLER_MAX_BLOCKS_PER_SEC = 9.0;
    private static final double SPRINTING_BLOCKS_PER_SEC_THRESHOLD = 4.0;
    private static final long PLACE_WINDOW_MS = 1000;
    private static final int TOWER_TICK_THRESHOLD = 6;
    private static final int TOWER_FAST_PLACEMENT_THRESHOLD = 3;
    private static final float ROTATION_SNAP_THRESHOLD = 45.0f;
    private static final int ROTATION_VIOLATION_THRESHOLD = 3;
    private static final class PlayerState {
        int blocksPlacedThisWindow;
        long windowStartTime;
        int lastSlot;
        double blocksPerSecondAccum;
        int samplesCollected;
        long lastPlacementTick;
        int consecutiveFastPlacements;
        float lastYaw;
        float lastPitch;
        long lastPlacementTimeMs;
        int rotationSnapViolations;
    }
    private final ConcurrentHashMap<UUID, PlayerState> stateMap = new ConcurrentHashMap<>();
    private PlayerState getState(TrackedPlayer player) {
        return stateMap.computeIfAbsent(player.getUuid(), k -> new PlayerState());
    }
    @Override
    public void removePlayer(UUID uuid) {
        stateMap.remove(uuid);
    }
    @Override
    public void onPacketReceive(TrackedPlayer player, PacketReceiveEvent event) {
        if (isBlockPlacePacket(event)) {
            handleBlockPlace(player);
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
    public void onTick(TrackedPlayer player, long currentTick) {
        PlayerState state = getState(player);
        if (state.lastPlacementTick > 0 && currentTick - state.lastPlacementTick > TOWER_TICK_THRESHOLD) {
            state.consecutiveFastPlacements = 0;
        }
    }
    private boolean isBlockPlacePacket(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        return type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT;
    }
    private void handleBlockPlace(TrackedPlayer player) {
        PlayerState state = getState(player);
        long now = PacketClock.currentTimeMillis();
        checkPlacementSpeed(player, state, now);
        checkTowerDetection(player, state);
        checkRotationConsistency(player, state, now);
    }
    private void checkPlacementSpeed(TrackedPlayer player, PlayerState state, long now) {
        if (state.windowStartTime == 0 || now - state.windowStartTime > PLACE_WINDOW_MS) {
            if (state.blocksPlacedThisWindow > 0) {
                double bps = state.blocksPlacedThisWindow;
                state.blocksPerSecondAccum += bps;
                state.samplesCollected++;
            }
            state.blocksPlacedThisWindow = 0;
            state.windowStartTime = now;
        }
        state.blocksPlacedThisWindow++;
        double bps = state.blocksPlacedThisWindow / Math.max(1.0, (now - state.windowStartTime) / 1000.0);
        if (player.isBedrock()) {
            checkBedrockScaffold(player, bps);
        } else {
            checkJavaScaffold(player, bps);
        }
    }
    private void checkTowerDetection(TrackedPlayer player, PlayerState state) {
        KeenAC plugin = KeenAC.getInstance();
        if (plugin == null) return;
        long currentTick = plugin.getCheckManager().getTickCounter();
        long tickDelta = currentTick - state.lastPlacementTick;
        if (state.lastPlacementTick > 0 && tickDelta <= TOWER_TICK_THRESHOLD) {
            state.consecutiveFastPlacements++;
            if (state.consecutiveFastPlacements >= TOWER_FAST_PLACEMENT_THRESHOLD) {
                increaseBuffer(player, 1.5);
                if (getBuffer(player) > 4.0) {
                    flag(player);
                    resetBuffer(player);
                    state.consecutiveFastPlacements = 0;
                }
            }
        } else {
            state.consecutiveFastPlacements = Math.max(0, state.consecutiveFastPlacements - 1);
        }
        state.lastPlacementTick = currentTick;
    }
    private void checkRotationConsistency(TrackedPlayer player, PlayerState state, long now) {
        float yaw = player.getYaw();
        float pitch = player.getPitch();
        if (state.lastPlacementTimeMs > 0) {
            float deltaYaw = Math.abs(yaw - state.lastYaw);
            if (deltaYaw > 180) deltaYaw = 360 - deltaYaw;
            float deltaPitch = Math.abs(pitch - state.lastPitch);
            if (deltaYaw > ROTATION_SNAP_THRESHOLD || deltaPitch > ROTATION_SNAP_THRESHOLD) {
                state.rotationSnapViolations++;
                if (state.rotationSnapViolations >= ROTATION_VIOLATION_THRESHOLD) {
                    increaseBuffer(player, 1.0);
                    if (getBuffer(player) > 5.0) {
                        flag(player);
                        resetBuffer(player);
                        state.rotationSnapViolations = 0;
                    }
                }
            } else {
                state.rotationSnapViolations = Math.max(0, state.rotationSnapViolations - 1);
            }
        }
        state.lastYaw = yaw;
        state.lastPitch = pitch;
        state.lastPlacementTimeMs = now;
    }
    private void checkJavaScaffold(TrackedPlayer player, double bps) {
        if (bps > JAVA_MAX_BLOCK_PLACE_PER_SECOND) {
            increaseBuffer(player, 1.0);
            if (getBuffer(player) > 5.0) {
                flag(player);
                resetBuffer(player);
            }
        } else if (player.isSprinting() && bps > SPRINTING_BLOCKS_PER_SEC_THRESHOLD) {
            increaseBuffer(player, 0.5);
            if (getBuffer(player) > 3.0) {
                flag(player);
                resetBuffer(player);
            }
        } else {
            decreaseBuffer(player, 0.1);
        }
    }
    private void checkBedrockScaffold(TrackedPlayer player, double bps) {
        BedrockInfo info = player.getBedrockInfo();
        if (info == null) return;
        double maxBps;
        if (info.isTouchDevice()) {
            maxBps = BEDROCK_TOUCH_MAX_BLOCKS_PER_SEC;
        } else if (info.isController()) {
            maxBps = BEDROCK_CONTROLLER_MAX_BLOCKS_PER_SEC;
        } else {
            maxBps = BEDROCK_KB_MAX_BLOCKS_PER_SEC;
        }
        if (bps > maxBps) {
            increaseBuffer(player, 0.5);
            if (getBuffer(player) > 8.0) {
                flag(player);
                resetBuffer(player);
            }
        } else {
            decreaseBuffer(player, 0.1);
        }
    }
}
