package dev.keenac.engine.core.check.impl.combat;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import dev.keenac.engine.compat.combat.CrystalAttackWindow;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.CompatFlag;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.player.TrackedPlayer;
import dev.keenac.engine.core.util.PacketClock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@CheckData(name = "Crystal A", stableKey = "keenac.combat.crystal", decay = 0.01, setbackVl = 20,
    compat = {CompatFlag.RELAX_ON_MISMATCH},
    relaxMultiplier = 1.3)
public class CrystalCheck extends Check implements PacketCheck {
    private static final long SPAWN_TRACK_WINDOW_MS = 5000L;
    private static final double FLAGS_REQUIRED = 2.0D;
    private static final class PlayerState {
        final CrystalAttackWindow window = new CrystalAttackWindow();
        final Map<Integer, Long> spawnTimes = new HashMap<>();
        int consecutiveFlags;
    }
    private final ConcurrentHashMap<UUID, PlayerState> stateMap = new ConcurrentHashMap<>();
    private PlayerState getState(TrackedPlayer player) {
        return stateMap.computeIfAbsent(player.getUuid(), uuid -> new PlayerState());
    }
    @Override
    public void removePlayer(UUID uuid) {
        stateMap.remove(uuid);
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.SPAWN_ENTITY) return;
        WrapperPlayServerSpawnEntity wrapper = new WrapperPlayServerSpawnEntity(event);
        if (wrapper.getEntityType() != EntityTypes.END_CRYSTAL) return;
        long now = PacketClock.currentTimeMillis();
        PlayerState state = getState(player);
        state.window.onCrystalSpawn(wrapper.getEntityId(), now);
        state.window.onCrystalPlace(now);
        state.spawnTimes.put(wrapper.getEntityId(), now);
        pruneSpawns(state, now);
    }
    @Override
    public void onPacketReceive(TrackedPlayer player, PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) return;
        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
        if (wrapper.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) return;
        long now = PacketClock.currentTimeMillis();
        PlayerState state = getState(player);
        int entityId = wrapper.getEntityId();
        Long spawnedAt = state.spawnTimes.get(entityId);
        boolean targetIsCrystal = spawnedAt != null;
        int targetTicksLived = targetIsCrystal
            ? (int) ((now - spawnedAt) / 50L)
            : Integer.MAX_VALUE;
        CrystalAttackWindow.Flag flag =
            state.window.onAttack(now, targetIsCrystal, targetIsCrystal, targetTicksLived);
        if (flag == CrystalAttackWindow.Flag.ID_PREDICT) {
            state.consecutiveFlags = 0;
            flagWithSetback(player);
            resetBuffer(player);
            return;
        }
        if (flag == CrystalAttackWindow.Flag.FAST_PLACE_BREAK) {
            state.consecutiveFlags++;
            double required = trustAdjustedThreshold(player, FLAGS_REQUIRED);
            if (state.consecutiveFlags >= required) {
                flag(player);
                resetBuffer(player);
                state.consecutiveFlags = 0;
            }
        } else {
            state.consecutiveFlags = Math.max(0, state.consecutiveFlags - 1);
        }
    }
    private void pruneSpawns(PlayerState state, long now) {
        state.spawnTimes.entrySet().removeIf(entry -> now - entry.getValue() > SPAWN_TRACK_WINDOW_MS);
    }
}
