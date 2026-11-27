package dev.keenac.engine.core.check.impl.packet;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCreativeInventoryAction;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.player.TrackedPlayer;
import dev.keenac.engine.core.util.PacketClock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@CheckData(name = "Creative A", stableKey = "keenac.packet.creative", decay = 0.0, setbackVl = 5)
public class CreativeCheck extends Check implements PacketCheck {
    private static final int MAX_CREATIVE_ACTIONS_PER_TICK = 5;
    private static final int KICK_THRESHOLD = 10;
    private static final class PlayerState {
        int actionsThisTick;
        long lastTickStart;
        int totalActions;
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
        if (type != PacketType.Play.Client.CREATIVE_INVENTORY_ACTION) return;
        if (player.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE) {
            flag(player);
            return;
        }
        PlayerState state = getState(player);
        long now = PacketClock.currentTimeMillis();
        if (now - state.lastTickStart > 50) {
            state.actionsThisTick = 0;
            state.lastTickStart = now;
        }
        state.actionsThisTick++;
        state.totalActions++;
        if (state.actionsThisTick > MAX_CREATIVE_ACTIONS_PER_TICK) {
            increaseBuffer(player, 1.0);
            if (getBuffer(player) > KICK_THRESHOLD) {
                flagWithSetback(player);
                resetBuffer(player);
            }
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
}
