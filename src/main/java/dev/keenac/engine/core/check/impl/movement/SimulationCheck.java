package dev.keenac.engine.core.check.impl.movement;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import dev.keenac.KeenAC;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.CompatFlag;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.compensation.SimulationEngine;
import dev.keenac.engine.core.physics.PhysicsConstants;
import dev.keenac.engine.core.player.TrackedPlayer;
import dev.keenac.engine.core.version.VersionBracket;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@CheckData(name = "Simulation A", stableKey = "keenac.movement.simulation", decay = 0.01, setbackVl = 20,
    compat = {CompatFlag.RELAX_ON_MISMATCH},
    relaxMultiplier = 1.4)
public class SimulationCheck extends Check implements PacketCheck {
    private static final double MAX_SIMULATION_DEVIATION = 0.15;
    private static final int MIN_SAMPLES = 10;
    private static final class PlayerState {
        double expectedDeltaY;
        int samples;
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
        if (!isMovementPacket(event)) return;
        PlayerState state = getState(player);
        boolean onGround = player.isOnGround();
        double deltaY = player.getDeltaY();
        double deltaX = player.getDeltaX();
        double deltaZ = player.getDeltaZ();
        if (onGround) {
            state.expectedDeltaY = 0;
            state.samples = 0;
            return;
        }
        int protocol = player.getProtocolVersion();
        double gravity = PhysicsConstants.GRAVITY;
        double airDrag = PhysicsConstants.AIR_DRAG;
        if (player.isSwimming()) {
            double waterDrag = protocol >= 393 ? PhysicsConstants.WATER_DRAG : 0.8;
            double predictedDeltaY = (state.expectedDeltaY - gravity) * waterDrag;
            double verticalDeviation = Math.abs(deltaY - predictedDeltaY);
            checkDeviation(player, verticalDeviation, deltaX, deltaZ, state);
            state.expectedDeltaY = deltaY;
            return;
        }
        double predictedDeltaY = (state.expectedDeltaY - gravity) * airDrag;
        double verticalDeviation = Math.abs(deltaY - predictedDeltaY);
        SimulationEngine simEngine = KeenAC.getInstance().getSimulationEngine();
        if (simEngine != null && simEngine.needsSimulation(player)) {
            SimulationEngine.SimulationResult result = simEngine.simulate(player, player.getX(), player.getY(), player.getZ());
            if (result.bestScenario > 0 && result.matches) {
                state.samples = Math.max(0, state.samples - 2);
                decreaseBuffer(player, 0.2);
                state.expectedDeltaY = deltaY;
                return;
            }
        }
        checkDeviation(player, verticalDeviation, deltaX, deltaZ, state);
        state.expectedDeltaY = deltaY;
    }
    private void checkDeviation(TrackedPlayer player, double verticalDeviation, double deltaX, double deltaZ, PlayerState state) {
        double horizontalSpeed = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        double maxDeviation = MAX_SIMULATION_DEVIATION;
        if (player.isBedrock()) {
            maxDeviation *= 1.15;
        }
        int protocol = player.getProtocolVersion();
        VersionBracket bracket = VersionBracket.fromProtocol(protocol);
        if (bracket == VersionBracket.LEGACY || bracket == VersionBracket.COMBAT) {
            maxDeviation *= 1.2;
        }
        if (verticalDeviation > maxDeviation && horizontalSpeed > 0.1) {
            state.samples++;
            if (state.samples >= MIN_SAMPLES) {
                increaseBuffer(player, 0.5 * (verticalDeviation / maxDeviation));
                if (getBuffer(player) > 4.0) {
                    flag(player);
                    resetBuffer(player);
                    state.samples = 0;
                }
            }
        } else {
            state.samples = Math.max(0, state.samples - 1);
            decreaseBuffer(player, 0.1);
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
    private boolean isMovementPacket(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        return type == PacketType.Play.Client.PLAYER_FLYING
                || type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
    }
}
