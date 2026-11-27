package dev.keenac.evidence;

import dev.keenac.context.PlayerContextSnapshot;

import java.util.UUID;

/** One immutable check signal and the player context observed at that time. */
public record EvidenceRecord(
        long timestampMillis,
        UUID playerId,
        String playerName,
        String checkName,
        int violationLevel,
        String reason,
        PlayerContextSnapshot context
) { }
