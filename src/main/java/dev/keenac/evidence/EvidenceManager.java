package dev.keenac.evidence;

import dev.keenac.KeenAC;
import dev.keenac.context.PlayerContextManager;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/** Captures evidence with a shared movement/latency context snapshot. */
public final class EvidenceManager {
    private final KeenAC plugin;
    private final PlayerContextManager contexts;
    private final EvidenceStore store = new EvidenceStore();

    public EvidenceManager(KeenAC plugin, PlayerContextManager contexts) {
        this.plugin = plugin;
        this.contexts = contexts;
    }

    public EvidenceRecord record(Player player, String checkName, int violationLevel, String reason) {
        if (player == null || !plugin.getConfig().getBoolean("evidence.enabled", true)) return null;

        EvidenceRecord record = new EvidenceRecord(
                System.currentTimeMillis(),
                player.getUniqueId(),
                player.getName(),
                checkName == null ? "Unknown" : checkName,
                Math.max(0, violationLevel),
                reason == null || reason.isBlank() ? "No additional reason was recorded." : reason,
                contexts.snapshot(player)
        );
        store.add(record);
        return record;
    }

    public List<EvidenceRecord> recent(UUID playerId, int limit) {
        return store.recent(playerId, limit);
    }

    public UUID findPlayerId(String playerName) {
        return store.findPlayerId(playerName);
    }

    public void clearAll() {
        store.clearAll();
    }
}
