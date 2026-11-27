package dev.keenac.engine.core.check.impl.packet;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.player.TrackedPlayer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@CheckData(name = "Crash A", stableKey = "keenac.packet.crash", decay = 0.0, setbackVl = 5)
public class CrashCheck extends Check implements PacketCheck {
    private static final int MAX_STRING_LENGTH = 32767;
    private static final int MAX_PACKET_SIZE = 32767;
    private static final int KICK_THRESHOLD = 3;
    private static final class PlayerState {
        int violations;
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
        PlayerState state = getState(player);
        if (type == PacketType.Play.Client.CHAT_MESSAGE) {
            String message = getChatMessage(event);
            if (message != null && message.length() > MAX_STRING_LENGTH) {
                state.violations++;
                if (state.violations >= KICK_THRESHOLD) {
                    flag(player);
                    state.violations = 0;
                }
            }
        }
        if (type == PacketType.Play.Client.CREATIVE_INVENTORY_ACTION) {
            increaseBuffer(player, 0.5);
            if (getBuffer(player) > 10.0) {
                flag(player);
                resetBuffer(player);
            }
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
    private String getChatMessage(PacketReceiveEvent event) {
        try {
            com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatMessage wrapper =
                    new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatMessage(event);
            return wrapper.getMessage();
        } catch (Exception e) {
            return null;
        }
    }
}
