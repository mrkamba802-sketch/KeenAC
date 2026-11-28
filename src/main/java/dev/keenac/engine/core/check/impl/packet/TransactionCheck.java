package dev.keenac.engine.core.check.impl.packet;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import dev.keenac.KeenAC;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.CompatFlag;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.compensation.TransactionManager;
import dev.keenac.engine.core.player.TrackedPlayer;

import java.util.UUID;

/**
 * Transaction probes provide latency/confirmation data to the compensation engine.
 * A missing response alone is not proof of cheating: packet loss, client stalls,
 * overloaded servers and high latency all delay legitimate responses.
 */
@CheckData(
        name = "Transaction A",
        stableKey = "keenac.packet.transaction",
        decay = 0.005,
        setbackVl = 15,
        compat = {CompatFlag.RELAX_ON_MISMATCH},
        relaxMultiplier = 1.3
)
public class TransactionCheck extends Check implements PacketCheck {
    private static final long MIN_PROBE_TIMEOUT_MS = 15_000L;

    @Override
    public void removePlayer(UUID uuid) {
    }

    @Override
    public void onPacketReceive(TrackedPlayer player, PacketReceiveEvent event) {
    }

    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }

    public void onTransactionResponse(TrackedPlayer player, boolean matched) {
        // Response accounting is performed by TransactionManager and PingPongManager.
        // Unknown/late transaction IDs are not independently attributable to a cheat.
    }

    public void onTick(TrackedPlayer player) {
        KeenAC plugin = KeenAC.getInstance();
        if (plugin == null) return;
        TransactionManager manager = plugin.getTransactionManager();
        if (manager == null) return;

        int ping = Math.max(0, player.getTransactionPing());
        long timeout = Math.max(MIN_PROBE_TIMEOUT_MS, (long) ping * 4L + 5_000L);
        if (player.isBedrock()) timeout *= 2L;

        // Keep the probe queue bounded without generating violation levels from timeouts.
        manager.sweepTimedOutTransactions(player.getUuid(), timeout);
    }
}
