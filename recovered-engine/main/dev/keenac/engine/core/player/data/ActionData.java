package dev.keenac.engine.core.player.data;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import dev.keenac.engine.core.player.TrackedPlayer;
import org.bukkit.Material;
public class ActionData {
    private static final int MAX_TICKS = 1000;
    private int sincePistonUpdateTicks = MAX_TICKS;
    private int sinceBlockUpdateUnderTicks = MAX_TICKS;
    private int sinceConfirmedUnderPlaceTicks = MAX_TICKS;
    private int sinceConfirmedUnderBreakTicks = MAX_TICKS;
    private int sinceBlockPlaceAttemptTicks = MAX_TICKS;
    private final TrackedPlayer player;
    public ActionData(TrackedPlayer player) {
        this.player = player;
    }
    public boolean hasRecentPistonUpdate(int ticks) {
        return sincePistonUpdateTicks <= ticks;
    }
    public boolean hasRecentBlockUpdateUnder(int ticks) {
        return sinceBlockUpdateUnderTicks <= ticks;
    }
    public boolean hasRecentConfirmedUnderPlace(int ticks) {
        return sinceConfirmedUnderPlaceTicks <= ticks;
    }
    public boolean hasRecentConfirmedUnderBreak(int ticks) {
        return sinceConfirmedUnderBreakTicks <= ticks;
    }
    public boolean hasRecentBlockPlaceAttempt(int ticks) {
        return sinceBlockPlaceAttemptTicks <= ticks;
    }
    public void processReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            handleBlockPlace(event);
        } else if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client.PLAYER_DIGGING) {
            handleBlockDig(event);
        }
    }
    public void processSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.BLOCK_CHANGE) {
            handleBlockChange(event);
        }
    }
    public void tick() {
        sincePistonUpdateTicks = Math.min(sincePistonUpdateTicks + 1, MAX_TICKS);
        sinceBlockUpdateUnderTicks = Math.min(sinceBlockUpdateUnderTicks + 1, MAX_TICKS);
        sinceConfirmedUnderPlaceTicks = Math.min(sinceConfirmedUnderPlaceTicks + 1, MAX_TICKS);
        sinceConfirmedUnderBreakTicks = Math.min(sinceConfirmedUnderBreakTicks + 1, MAX_TICKS);
        sinceBlockPlaceAttemptTicks = Math.min(sinceBlockPlaceAttemptTicks + 1, MAX_TICKS);
    }
    private void handleBlockPlace(PacketReceiveEvent event) {
        try {
            WrapperPlayClientPlayerBlockPlacement wrapper = new WrapperPlayClientPlayerBlockPlacement(event);
            Vector3i blockPos = wrapper.getBlockPosition();
            if (blockPos == null) return;
            sinceBlockPlaceAttemptTicks = 0;
            if (isBlockUnderPlayer(blockPos.getY())) {
                sinceConfirmedUnderPlaceTicks = 0;
            }
        } catch (Exception ignored) {
        }
    }
    private void handleBlockDig(PacketReceiveEvent event) {
        try {
            WrapperPlayClientPlayerDigging wrapper = new WrapperPlayClientPlayerDigging(event);
            DiggingAction action = wrapper.getAction();
            if (action != DiggingAction.FINISHED_DIGGING) return;
            Vector3i blockPos = wrapper.getBlockPosition();
            if (blockPos == null) return;
            if (isBlockUnderPlayer(blockPos.getY())) {
                sinceConfirmedUnderBreakTicks = 0;
            }
        } catch (Exception ignored) {
        }
    }
    private void handleBlockChange(PacketSendEvent event) {
        try {
            WrapperPlayServerBlockChange wrapper = new WrapperPlayServerBlockChange(event);
            Vector3i blockPos = wrapper.getBlockPosition();
            if (blockPos == null) return;
            int blockId = wrapper.getBlockId();
            if (isPistonBlock(blockId)) {
                sincePistonUpdateTicks = 0;
            }
            if (isBlockUnderPlayer(blockPos.getY())) {
                sinceBlockUpdateUnderTicks = 0;
            }
        } catch (Exception ignored) {
        }
    }
    private boolean isBlockUnderPlayer(int blockY) {
        return blockY <= (int) Math.floor(player.getY());
    }
    private static boolean isPistonBlock(int blockId) {
        return blockId == 29 || blockId == 33 || blockId == 34 || blockId == 36;
    }
    private static boolean isPistonMaterial(Material material) {
        if (material == null) return false;
        String name = material.name();
        return name.contains("PISTON");
    }
}
