package dev.keenac.engine.core.check.impl.packet;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import dev.keenac.engine.core.check.Check;
import dev.keenac.engine.core.check.CheckData;
import dev.keenac.engine.core.check.type.PacketCheck;
import dev.keenac.engine.core.player.TrackedPlayer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
@CheckData(name = "Client Brand A", stableKey = "keenac.packet.brand", decay = 0.0, setbackVl = 10)
public class ClientBrandCheck extends Check implements PacketCheck {
    private static final List<String> SUSPICIOUS_BRANDS = Arrays.asList(
        "wurst", "impact", "moon", "liquidbounce", "koks", "aristois",
        "vape", "rusherhack", "ghostly", "exhibiton", "phobos",
        "fdp", "salhack", "rise", "astolfo", "blexware", "hanabi",
        "cabbage", "dotgod", "sodium", "meteor", "inertia",
        "xplus", "tenacity", "nextgen", "crescent"
    );
    @Override
    public void onPacketReceive(TrackedPlayer player, PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.PLUGIN_MESSAGE) return;
        WrapperPlayClientPluginMessage wrapper = new WrapperPlayClientPluginMessage(event);
        String channel = wrapper.getChannelName();
        if (!channel.equals("minecraft:brand") && !channel.equals("MC|Brand")) return;
        byte[] data = wrapper.getData();
        if (data == null || data.length == 0) return;
        String brand = new String(data, StandardCharsets.UTF_8).toLowerCase()
            .replaceAll("[^a-z0-9]", "");
        for (String suspicious : SUSPICIOUS_BRANDS) {
            if (brand.contains(suspicious)) {
                flag(player);
                var logger = dev.keenac.KeenAC.getInstance().getLogger();
                logger.warning("[Client Brand A] " + player.getName()
                    + " detected suspicious brand: " + brand);
                return;
            }
        }
    }
    @Override
    public void onPacketSend(TrackedPlayer player, PacketSendEvent event) {
    }
}
