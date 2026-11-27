package dev.keenac.engine.core.check.impl.inventory;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCreativeInventoryAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.CompatFlag;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.player.TrackedPlayer;
import dev.keenac.engine.core.util.PacketClock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@CheckData(name = "Inventory A", stableKey = "keenac.inventory.inventory", decay = 0.02, setbackVl = 15, compat = {CompatFlag.RELAX_ON_MISMATCH}, relaxMultiplier = 1.3)
public class InventoryCheck extends Check implements PacketCheck {
    private static final int MAX_CLICKS_PER_SECOND = 20;
    private static final long CLICK_WINDOW_MS = 50;
    private static final class PlayerState {
        long lastClickTime;
        int clickCount;
        int clicksThisSecond;
        long secondStart;
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
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Client.CLICK_WINDOW) {
            handleClickWindow(player, event);
        } else if (type == PacketType.Play.Client.CREATIVE_INVENTORY_ACTION) {
            handleCreativeSlot(player, event);
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
    private void handleClickWindow(TrackedPlayer player, PacketReceiveEvent event) {
        PlayerState state = getState(player);
        long now = PacketClock.currentTimeMillis();
        if (now - state.lastClickTime < CLICK_WINDOW_MS) {
            state.clickCount++;
            if (state.clickCount > 5) {
                increaseBuffer(player, 1.0);
                if (getBuffer(player) > 3.0) {
                    flag(player);
                    resetBuffer(player);
                }
            }
        } else {
            state.clickCount = 0;
        }
        state.lastClickTime = now;
        if (now - state.secondStart > 1000) {
            state.clicksThisSecond = 0;
            state.secondStart = now;
        }
        state.clicksThisSecond++;
        if (state.clicksThisSecond > MAX_CLICKS_PER_SECOND) {
            flag(player);
        }
    }
    private void handleCreativeSlot(TrackedPlayer player, PacketReceiveEvent event) {
        if (!player.getPlayer().getGameMode().equals(org.bukkit.GameMode.CREATIVE)) {
            flag(player);
        }
    }
}
