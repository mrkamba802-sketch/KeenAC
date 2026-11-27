package dev.keenac.engine.core.check.impl.movement;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.player.TrackedPlayer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@CheckData(name = "Multi Place", stableKey = "keenac.movement.multiplace", decay = 0.02, setbackVl = 10)
public class MultiPlaceCheck extends Check implements PacketCheck {
    private static final int MAX_PLACES_PER_TICK = 1;
    private static final int BUFFER_THRESHOLD = 2;
    private static final class PlayerState {
        int placesThisTick;
        long lastTick;
    }
    private final ConcurrentHashMap<UUID, PlayerState> stateMap = new ConcurrentHashMap<>();
    private PlayerState getState(TrackedPlayer player) {
        return stateMap.computeIfAbsent(player.getUuid(), k -> new PlayerState());
    }
    @Override
    public void removePlayer(java.util.UUID uuid) {
        stateMap.remove(uuid);
    }
    @Override
    public void onPacketReceive(TrackedPlayer player, PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) return;
        WrapperPlayClientPlayerBlockPlacement wrapper = new WrapperPlayClientPlayerBlockPlacement(event);
        if (wrapper.getFace() == null) return;
        PlayerState state = getState(player);
        long currentTick = player.getTickCount();
        if (currentTick != state.lastTick) {
            state.placesThisTick = 0;
            state.lastTick = currentTick;
        }
        state.placesThisTick++;
        if (state.placesThisTick > MAX_PLACES_PER_TICK) {
            increaseBuffer(player, 1.0);
            if (getBuffer(player) > BUFFER_THRESHOLD) {
                flag(player);
                resetBuffer(player);
            }
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
}
