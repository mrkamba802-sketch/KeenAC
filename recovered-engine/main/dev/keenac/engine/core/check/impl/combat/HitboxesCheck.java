package dev.keenac.engine.core.check.impl.combat;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.CompatFlag;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.player.TrackedPlayer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@CheckData(name = "Hitboxes A", stableKey = "keenac.combat.hitboxes", decay = 0.01, setbackVl = 15, compat = {CompatFlag.RELAX_ON_MISMATCH}, relaxMultiplier = 1.2)
public class HitboxesCheck extends Check implements PacketCheck {
    private static final double PLAYER_BOX_EXPANSION = 0.15;
    private static final int MIN_ATTACKS_PER_EVAL = 16;
    private static final double HIT_RATIO_FLAG_THRESHOLD = 0.8;
    private static final double BLATANT_FLAG_THRESHOLD = 5.0;
    private static final double MAX_REACH = 3.5;
    private static final double FLAG_BUFFER_THRESHOLD = 5.0;
    private static final class PlayerState {
        int attacksOnTarget;
        int totalAttacks;
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
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) return;
        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
        if (wrapper.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) return;
        PlayerState state = getState(player);
        state.totalAttacks++;
        double eyeX = player.getX();
        double eyeY = player.getY() + player.getEyeHeight();
        double eyeZ = player.getZ();
        float yaw = player.getYaw();
        float pitch = player.getPitch();
        double lookX = -Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)) * MAX_REACH;
        double lookY = -Math.sin(Math.toRadians(pitch)) * MAX_REACH;
        double lookZ = Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)) * MAX_REACH;
        double maxReach = MAX_REACH + PLAYER_BOX_EXPANSION;
        double hitDistance = Math.sqrt(lookX * lookX + lookY * lookY + lookZ * lookZ);
        if (hitDistance > BLATANT_FLAG_THRESHOLD) {
            flag(player);
            resetBuffer(player);
            state.attacksOnTarget = 0;
            state.totalAttacks = 0;
            return;
        }
        if (hitDistance < maxReach) {
            state.attacksOnTarget++;
        }
        if (state.totalAttacks >= MIN_ATTACKS_PER_EVAL) {
            double hitRatio = (double) state.attacksOnTarget / state.totalAttacks;
            if (hitRatio > HIT_RATIO_FLAG_THRESHOLD && state.totalAttacks > 20) {
                increaseBuffer(player, 0.3);
                if (getBuffer(player) > FLAG_BUFFER_THRESHOLD) {
                    flag(player);
                    resetBuffer(player);
                }
            } else {
                decreaseBuffer(player, 0.1);
            }
            state.attacksOnTarget = 0;
            state.totalAttacks = 0;
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
}
