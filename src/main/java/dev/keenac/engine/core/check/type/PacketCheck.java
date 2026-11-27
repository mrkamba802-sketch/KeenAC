package dev.keenac.engine.core.check.type;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import dev.keenac.engine.core.player.TrackedPlayer;
public interface PacketCheck {
    default void onPacketReceive(TrackedPlayer player, PacketReceiveEvent event) {}
    default void onPacketSend(TrackedPlayer player, PacketSendEvent event) {}
}
