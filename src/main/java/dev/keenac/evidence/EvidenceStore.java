package dev.keenac.evidence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bounded in-memory evidence history. Newest records are returned first.
 * Both player count and per-player history are capped to avoid unbounded growth.
 */
public final class EvidenceStore {
    public static final int DEFAULT_MAX_PLAYERS = 512;
    public static final int DEFAULT_MAX_PER_PLAYER = 40;

    private final int maxPlayers;
    private final int maxPerPlayer;
    private final LinkedHashMap<UUID, Deque<EvidenceRecord>> records =
            new LinkedHashMap<>(16, 0.75f, true);

    public EvidenceStore() {
        this(DEFAULT_MAX_PLAYERS, DEFAULT_MAX_PER_PLAYER);
    }

    public EvidenceStore(int maxPlayers, int maxPerPlayer) {
        if (maxPlayers < 1 || maxPerPlayer < 1) {
            throw new IllegalArgumentException("Evidence limits must be positive.");
        }
        this.maxPlayers = maxPlayers;
        this.maxPerPlayer = maxPerPlayer;
    }

    public synchronized void add(EvidenceRecord record) {
        if (record == null) throw new IllegalArgumentException("Evidence record cannot be null.");
        Deque<EvidenceRecord> playerRecords =
                records.computeIfAbsent(record.playerId(), ignored -> new ArrayDeque<>());
        playerRecords.addLast(record);
        while (playerRecords.size() > maxPerPlayer) {
            playerRecords.removeFirst();
        }

        while (records.size() > maxPlayers) {
            Iterator<Map.Entry<UUID, Deque<EvidenceRecord>>> iterator = records.entrySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
    }

    public synchronized List<EvidenceRecord> recent(UUID playerId, int limit) {
        if (playerId == null || limit < 1) return List.of();
        Deque<EvidenceRecord> playerRecords = records.get(playerId);
        if (playerRecords == null || playerRecords.isEmpty()) return List.of();

        List<EvidenceRecord> result = new ArrayList<>(Math.min(limit, playerRecords.size()));
        Iterator<EvidenceRecord> iterator = playerRecords.descendingIterator();
        while (iterator.hasNext() && result.size() < limit) {
            result.add(iterator.next());
        }
        return List.copyOf(result);
    }

    public synchronized UUID findPlayerId(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        List<Map.Entry<UUID, Deque<EvidenceRecord>>> entries =
                new ArrayList<>(records.entrySet());
        for (int i = entries.size() - 1; i >= 0; i--) {
            Deque<EvidenceRecord> playerRecords = entries.get(i).getValue();
            EvidenceRecord newest = playerRecords.peekLast();
            if (newest != null && newest.playerName().equalsIgnoreCase(playerName)) {
                return entries.get(i).getKey();
            }
        }
        return null;
    }

    public synchronized void clearAll() {
        records.clear();
    }

    public synchronized int trackedPlayers() {
        return records.size();
    }

    public synchronized int size(UUID playerId) {
        Deque<EvidenceRecord> playerRecords = records.get(playerId);
        return playerRecords == null ? 0 : playerRecords.size();
    }
}
