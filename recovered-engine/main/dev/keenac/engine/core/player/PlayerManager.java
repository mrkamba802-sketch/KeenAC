package dev.keenac.engine.core.player;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
public final class PlayerManager {
    private final ConcurrentHashMap<UUID, TrackedPlayer> players = new ConcurrentHashMap<>();
    public TrackedPlayer get(UUID uuid) { return players.get(uuid); }
    public void add(TrackedPlayer player) { players.put(player.getUuid(), player); }
    public TrackedPlayer remove(UUID uuid) {
        TrackedPlayer player = players.remove(uuid);
        if (player != null) player.setValid(false);
        return player;
    }
    public ConcurrentHashMap<UUID, TrackedPlayer> getAll() { return players; }
    public Collection<TrackedPlayer> getAllPlayers() { return players.values(); }
    public int size() { return players.size(); }
}
