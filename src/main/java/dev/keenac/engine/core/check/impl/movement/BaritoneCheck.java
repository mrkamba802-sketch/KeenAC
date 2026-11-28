package dev.keenac.engine.core.check.impl.movement;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.CompatFlag;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.player.TrackedPlayer;

import java.util.UUID;

/**
 * Path straightness and constant speed are common for legitimate players.
 * Until this check has server-side input/steering evidence, it must not create violations.
 */
@CheckData(name = "Baritone A", stableKey = "keenac.movement.baritone",
        decay = 0.01, setbackVl = 20,
        compat = {CompatFlag.RELAX_ON_MISMATCH}, relaxMultiplier = 1.2)
public class BaritoneCheck extends Check implements PacketCheck {
    @Override
    public void removePlayer(UUID uuid) {
    }

    @Override
    public void onPacketReceive(TrackedPlayer player, PacketReceiveEvent event) {
        // Deliberately observation-only: straight walking is not evidence of automation.
    }

    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
}
