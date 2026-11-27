package dev.keenac.evidence;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceStoreTest {
    @Test
    void keepsOnlyConfiguredNumberOfRecordsPerPlayer() {
        EvidenceStore store = new EvidenceStore(10, 2);
        UUID player = UUID.randomUUID();

        store.add(record(player, "Tester", 1L));
        store.add(record(player, "Tester", 2L));
        store.add(record(player, "Tester", 3L));

        List<EvidenceRecord> latest = store.recent(player, 10);
        assertEquals(2, latest.size());
        assertEquals(List.of(3L, 2L),
                latest.stream().map(EvidenceRecord::timestampMillis).toList());
    }

    @Test
    void returnsNewestRecordsFirstAndHonorsRequestedLimit() {
        EvidenceStore store = new EvidenceStore(10, 10);
        UUID player = UUID.randomUUID();
        store.add(record(player, "Tester", 10L));
        store.add(record(player, "Tester", 20L));
        store.add(record(player, "Tester", 30L));

        assertEquals(List.of(30L, 20L), store.recent(player, 2).stream()
                .map(EvidenceRecord::timestampMillis).toList());
    }

    @Test
    void findsPlayerNamesWithoutCaseSensitivity() {
        EvidenceStore store = new EvidenceStore();
        UUID player = UUID.randomUUID();
        store.add(record(player, "KeenPlayer", 1L));

        assertEquals(player, store.findPlayerId("keenplayer"));
        assertNull(store.findPlayerId("unknown"));
    }

    @Test
    void evictsLeastRecentlyUsedPlayerWhenPlayerLimitIsReached() {
        EvidenceStore store = new EvidenceStore(2, 5);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        store.add(record(first, "First", 1L));
        store.add(record(second, "Second", 2L));
        store.recent(first, 1); // Access refreshes the LRU position.
        store.add(record(third, "Third", 3L));

        assertEquals(1, store.size(first));
        assertEquals(0, store.size(second));
        assertEquals(1, store.size(third));
        assertEquals(2, store.trackedPlayers());
    }

    @Test
    void rejectsNonPositiveRetentionLimits() {
        assertThrows(IllegalArgumentException.class, () -> new EvidenceStore(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceStore(1, 0));
    }

    private EvidenceRecord record(UUID id, String name, long timestamp) {
        return new EvidenceRecord(timestamp, id, name, "Motion", 2,
                "Test evidence", null);
    }
}
