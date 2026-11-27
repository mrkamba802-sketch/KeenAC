package dev.keenac.engine.core.check.impl.packet;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSteerVehicle;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.player.TrackedPlayer;
import dev.keenac.engine.core.util.PacketClock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@CheckData(name = "Vehicle A", stableKey = "keenac.packet.vehicle", decay = 0.01, setbackVl = 15)
public class VehicleCheck extends Check implements PacketCheck {
    private static final double MAX_VEHICLE_SPEED = 2.0;
    private static final int MAX_STEER_PER_TICK = 3;
    private static final class PlayerState {
        int steerCountThisTick;
        long lastSteerTick;
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
        PacketTypeCommon type = event.getPacketType();
        long now = PacketClock.currentTimeMillis();
        PlayerState state = getState(player);
        if (now - state.lastSteerTick > 50) {
            state.steerCountThisTick = 0;
            state.lastSteerTick = now;
        }
        if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            handleInteract(player, event);
        } else if (type == PacketType.Play.Client.STEER_VEHICLE) {
            handleSteer(player, event, state);
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
    private void handleInteract(TrackedPlayer player, PacketReceiveEvent event) {
        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
        if (wrapper.getAction() == WrapperPlayClientInteractEntity.InteractAction.INTERACT_AT) {
            if (!player.getPlayer().isInsideVehicle()) {
                increaseBuffer(player, 0.5);
                if (getBuffer(player) > 2.0) {
                    flag(player);
                    resetBuffer(player);
                }
            }
        }
    }
    private void handleSteer(TrackedPlayer player, PacketReceiveEvent event, PlayerState state) {
        WrapperPlayClientSteerVehicle wrapper = new WrapperPlayClientSteerVehicle(event);
        state.steerCountThisTick++;
        if (state.steerCountThisTick > MAX_STEER_PER_TICK) {
            increaseBuffer(player, 1.0);
            if (getBuffer(player) > 3.0) {
                flag(player);
                resetBuffer(player);
            }
        }
        float forward = wrapper.getForward();
        float sideways = wrapper.getSideways();
        double speed = Math.sqrt(forward * forward + sideways * sideways);
        if (speed > MAX_VEHICLE_SPEED) {
            flag(player);
        }
    }
}
