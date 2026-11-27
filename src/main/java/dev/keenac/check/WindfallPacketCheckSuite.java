package dev.keenac.check;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCreativeInventoryAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSteerVehicle;
import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.InventoryType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Windfall packet and inventory check adaptations for modern Paper/PacketEvents.
 *
 * All signals are advisory and are sent through KeenAC's shared evidence and
 * alert pipeline. No client packet is cancelled and no player is kicked here.
 */
public final class WindfallPacketCheckSuite extends PacketListenerAbstract implements Listener {
    private static final long SECOND_NANOS = 1_000_000_000L;
    private static final long CHAT_BURST_NANOS = 2_000_000_000L;
    private static final long PACKET_BURST_NANOS = 100_000_000L;
    private static final Set<String> REVIEW_BRANDS = Set.of(
            "wurst", "liquidbounce", "rusherhack", "meteor", "inertia",
            "aristois", "rise", "vape", "phobos", "salhack", "tenacity"
    );

    private final KeenAC plugin;
    private final Map<UUID, PacketState> states = new ConcurrentHashMap<>();

    public WindfallPacketCheckSuite(KeenAC plugin) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID id = player.getUniqueId();
        var type = event.getPacketType();
        long now = System.nanoTime();
        PacketState state = states.computeIfAbsent(id, ignored -> new PacketState());

        reviewPacketBurst(player, state, now);

        if (WrapperPlayClientPlayerFlying.isFlying(type)) {
            WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);
            var location = flying.getLocation();
            if (location != null && (flying.hasPositionChanged() || flying.hasRotationChanged())) {
                double x = location.getX();
                double y = location.getY();
                double z = location.getZ();
                float yaw = location.getYaw();
                float pitch = location.getPitch();
                reviewMovementFields(player, state, x, y, z, yaw, pitch);
            }
            return;
        }

        if (type == PacketType.Play.Client.CHAT_MESSAGE) {
            String message = new WrapperPlayClientChatMessage(event).getMessage();
            reviewChat(player, state, message, now);
            return;
        }

        if (type == PacketType.Play.Client.PLUGIN_MESSAGE) {
            reviewBrand(player, new WrapperPlayClientPluginMessage(event));
            return;
        }

        if (type == PacketType.Play.Client.CLICK_WINDOW) {
            WrapperPlayClientClickWindow click = new WrapperPlayClientClickWindow(event);
            reviewExploit(player, click.getWindowId(), click.getSlot(), null, now);
            reviewInventoryClick(player, state, now);
            return;
        }

        if (type == PacketType.Play.Client.CREATIVE_INVENTORY_ACTION) {
            WrapperPlayClientCreativeInventoryAction creative =
                    new WrapperPlayClientCreativeInventoryAction(event);
            reviewCreative(player, state, creative.getSlot(), now);
            return;
        }

        if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            int slot = new WrapperPlayClientHeldItemChange(event).getSlot();
            if (slot < 0 || slot > 8) {
                flag(player, "Exploit", "Held-item packet contained an invalid hotbar slot.");
            }
            return;
        }

        if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
            reviewExploit(player, null, null, interact.getEntityId(), now);
            if (interact.getAction()
                    == WrapperPlayClientInteractEntity.InteractAction.INTERACT_AT) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player online = Bukkit.getPlayer(id);
                    if (online != null && online.isOnline() && !online.isInsideVehicle()) {
                        PacketState live = states.get(id);
                        if (live != null) {
                            live.invalidVehicleInteract++;
                            if (live.invalidVehicleInteract >= 4) {
                                flag(online, "Vehicle", "Repeated INTERACT_AT packets were sent while not mounted in a vehicle.");
                                live.invalidVehicleInteract = 0;
                            }
                        }
                    }
                });
            }
            return;
        }

        if (type == PacketType.Play.Client.STEER_VEHICLE) {
            WrapperPlayClientSteerVehicle steer = new WrapperPlayClientSteerVehicle(event);
            float forward = steer.getForward();
            float sideways = steer.getSideways();
            reviewVehicleInput(player, state, forward, sideways, now);
        }
    }

    private void reviewPacketBurst(Player player, PacketState state, long now) {
        if (!enabled("packet-order")) return;
        synchronized (state.packetTimes) {
            state.packetTimes.addLast(now);
            while (!state.packetTimes.isEmpty()
                    && now - state.packetTimes.peekFirst() > PACKET_BURST_NANOS) {
                state.packetTimes.removeFirst();
            }
            if (state.packetTimes.size() > 70) {
                state.packetBurstWindows++;
                state.packetTimes.clear();
                if (state.packetBurstWindows >= 3) {
                    flag(player, "Packet Order", "Sustained incoming packet bursts exceeded the 100 ms review threshold.");
                    state.packetBurstWindows = 0;
                }
            } else if (state.packetBurstWindows > 0) {
                state.packetBurstWindows--;
            }
        }
    }

    private void reviewMovementFields(Player player, PacketState state,
                                      double x, double y, double z, float yaw, float pitch) {
        if (enabled("bad-packets")) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                    || y < player.getWorld().getMinHeight() - 64.0
                    || y > player.getWorld().getMaxHeight() + 64.0) {
                flag(player, "Bad Packets", "Movement packet contained non-finite fields or coordinates outside the world safety bounds.");
            } else if (yaw < -180.0f || yaw > 180.0f || pitch < -90.0f || pitch > 90.0f) {
                state.invalidRotations++;
                if (state.invalidRotations >= 3) {
                    flag(player, "Bad Packets", "Repeated yaw/pitch values exceeded the protocol rotation range.");
                    state.invalidRotations = 0;
                }
            } else {
                state.invalidRotations = Math.max(0, state.invalidRotations - 1);
            }
        }

        if (enabled("packet-order")) {
            boolean duplicate = state.hasLastMovement
                    && Math.abs(state.lastX - x) < 0.00001
                    && Math.abs(state.lastY - y) < 0.00001
                    && Math.abs(state.lastZ - z) < 0.00001
                    && Math.abs(state.lastYaw - yaw) < 0.00001
                    && Math.abs(state.lastPitch - pitch) < 0.00001;
            state.duplicateMovement = duplicate ? state.duplicateMovement + 1
                    : Math.max(0, state.duplicateMovement - 1);
            if (state.duplicateMovement >= 12) {
                flag(player, "Packet Order", "Repeated identical position/rotation samples were received in sequence.");
                state.duplicateMovement = 0;
            }
        }

        state.lastX = x;
        state.lastY = y;
        state.lastZ = z;
        state.lastYaw = yaw;
        state.lastPitch = pitch;
        state.hasLastMovement = true;
    }

    private void reviewChat(Player player, PacketState state, String message, long now) {
        if (message == null) return;
        if (enabled("crash") && message.length() > 32767) {
            flag(player, "Crash", "Chat payload exceeded the protocol string length limit.");
        }
        if (!enabled("chat")) return;

        synchronized (state.chatMinute) {
            state.chatMinute.addLast(now);
            state.chatBurst.addLast(now);
            while (!state.chatMinute.isEmpty()
                    && now - state.chatMinute.peekFirst() > 60L * SECOND_NANOS) {
                state.chatMinute.removeFirst();
            }
            while (!state.chatBurst.isEmpty()
                    && now - state.chatBurst.peekFirst() > CHAT_BURST_NANOS) {
                state.chatBurst.removeFirst();
            }
            if (state.chatMinute.size() > 60) {
                state.chatMinuteFlags++;
                if (state.chatMinuteFlags >= 2) {
                    flag(player, "Chat", "Sustained chat rate exceeded 60 messages in a rolling minute.");
                    state.chatMinuteFlags = 0;
                    state.chatMinute.clear();
                }
            } else if (state.chatBurst.size() > 4) {
                state.chatBurstFlags++;
                if (state.chatBurstFlags >= 2) {
                    flag(player, "Chat", "Repeated chat bursts exceeded four messages in two seconds.");
                    state.chatBurstFlags = 0;
                    state.chatBurst.clear();
                }
            } else {
                state.chatBurstFlags = Math.max(0, state.chatBurstFlags - 1);
                state.chatMinuteFlags = Math.max(0, state.chatMinuteFlags - 1);
            }
        }
    }

    private void reviewBrand(Player player, WrapperPlayClientPluginMessage message) {
        if (!enabled("client-brand")) return;
        String channel = message.getChannelName();
        if (!"minecraft:brand".equals(channel) && !"MC|Brand".equals(channel)) return;
        byte[] raw = message.getData();
        if (raw == null || raw.length == 0) return;
        String brand = new String(raw, StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String token : REVIEW_BRANDS) {
            if (brand.contains(token)) {
                // Brand strings are self-reported metadata, not proof of cheating.
                flag(player, "Client Brand", "Client reported a brand string requiring staff review: " + token + ".");
                return;
            }
        }
    }

    private void reviewExploit(Player player, Integer windowId, Integer slot,
                               Integer entityId, long now) {
        if (!enabled("exploit")) return;
        if (windowId != null && (windowId < 0 || windowId > 200)) {
            flag(player, "Exploit", "Inventory click packet contained an out-of-range window id.");
        }
        if (slot != null && (slot < -1 || slot > 60)) {
            flag(player, "Exploit", "Inventory click packet contained an out-of-range slot.");
        }
        if (entityId != null && (entityId < -1 || entityId > 20000)) {
            flag(player, "Exploit", "Entity interaction packet contained an out-of-range entity id.");
        }
    }

    private void reviewCreative(Player player, PacketState state, int slot, long now) {
        if (slot < -1 || slot > 60) {
            if (enabled("exploit")) flag(player, "Exploit", "Creative inventory packet contained an invalid slot.");
        }
        if (!enabled("creative")) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(player.getUniqueId());
            if (online == null || !online.isOnline()) return;
            if (online.getGameMode() != GameMode.CREATIVE) {
                flag(online, "Creative", "Creative-inventory packet was received while the player was not in Creative mode.");
                return;
            }
            if (now - state.creativeWindowStart > 50_000_000L) {
                state.creativeWindowStart = now;
                state.creativeActions = 0;
            }
            state.creativeActions++;
            if (state.creativeActions > 5) {
                flag(online, "Creative", "More than five creative-inventory actions arrived within one 50 ms window.");
                state.creativeActions = 0;
            }
        });
    }

    private void reviewInventoryClick(Player player, PacketState state, long now) {
        if (!enabled("inventory")) return;
        synchronized (state.inventoryClicks) {
            state.inventoryClicks.addLast(now);
            while (!state.inventoryClicks.isEmpty()
                    && now - state.inventoryClicks.peekFirst() > SECOND_NANOS) {
                state.inventoryClicks.removeFirst();
            }
            if (state.inventoryClicks.size() > 20) {
                flag(player, "Inventory", "Inventory click rate exceeded 20 actions in one second.");
                state.inventoryClicks.clear();
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(player.getUniqueId());
            if (online == null || !online.isOnline()) return;
            InventoryType type = online.getOpenInventory().getTopInventory().getType();
            if (!isContainer(type)) return;
            synchronized (state.chestClicks) {
                state.chestClicks.addLast(now);
                while (!state.chestClicks.isEmpty()
                        && now - state.chestClicks.peekFirst() > 500_000_000L) {
                    state.chestClicks.removeFirst();
                }
                state.chestSessionClicks++;
                if (state.chestClicks.size() > 6) {
                    state.chestBurstFlags++;
                    if (state.chestBurstFlags >= 3) {
                        flag(online, "Chest Stealer", "Repeated container click bursts exceeded six clicks in 500 ms.");
                        state.chestBurstFlags = 0;
                    }
                }
                if (state.chestSessionClicks > 40) {
                    flag(online, "Chest Stealer", "Container interaction exceeded 40 clicks during one open session.");
                    state.chestSessionClicks = 0;
                }
            }
        });
    }

    private boolean isContainer(InventoryType type) {
        return switch (type) {
            case CHEST, ENDER_CHEST, BARREL, SHULKER_BOX, HOPPER, DISPENSER, DROPPER -> true;
            default -> false;
        };
    }

    private void reviewVehicleInput(Player player, PacketState state,
                                    float forward, float sideways, long now) {
        if (!enabled("vehicle")) return;
        if (!Float.isFinite(forward) || !Float.isFinite(sideways)) {
            flag(player, "Vehicle", "Vehicle steering packet contained non-finite input.");
            return;
        }
        if (now - state.steerWindowStart > 50_000_000L) {
            state.steerWindowStart = now;
            state.steerPackets = 0;
        }
        state.steerPackets++;
        if (state.steerPackets > 3) {
            state.steerBurstFlags++;
            if (state.steerBurstFlags >= 3) {
                flag(player, "Vehicle", "Repeated vehicle steering packet bursts exceeded three packets per 50 ms.");
                state.steerBurstFlags = 0;
            }
        }
        double magnitude = Math.hypot(forward, sideways);
        if (magnitude > 2.0) {
            flag(player, "Vehicle", String.format(Locale.ROOT,
                    "Vehicle steering magnitude %.2f exceeded the allowed input range.", magnitude));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            PacketState state = states.computeIfAbsent(player.getUniqueId(), ignored -> new PacketState());
            state.chestSessionClicks = 0;
            state.chestClicks.clear();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            PacketState state = states.get(player.getUniqueId());
            if (state != null) {
                state.chestSessionClicks = 0;
                state.chestClicks.clear();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !enabled("chest-stealer")) return;
        if (!isContainer(event.getView().getTopInventory().getType())) return;
        PacketState state = states.computeIfAbsent(player.getUniqueId(), ignored -> new PacketState());
        long now = System.nanoTime();
        synchronized (state.chestClicks) {
            state.chestClicks.addLast(now);
            while (!state.chestClicks.isEmpty()
                    && now - state.chestClicks.peekFirst() > 500_000_000L) {
                state.chestClicks.removeFirst();
            }
            state.chestSessionClicks++;
            if (state.chestClicks.size() > 6) {
                state.chestBurstFlags++;
                if (state.chestBurstFlags >= 3) {
                    flag(player, "Chest Stealer", "Repeated container click bursts exceeded six clicks in 500 ms.");
                    state.chestBurstFlags = 0;
                }
            }
            if (state.chestSessionClicks > 40) {
                flag(player, "Chest Stealer", "Container interaction exceeded 40 clicks during one open session.");
                state.chestSessionClicks = 0;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSprintToggle(PlayerToggleSprintEvent event) {
        if (!enabled("sprint")) return;
        Player player = event.getPlayer();
        PacketState state = states.computeIfAbsent(player.getUniqueId(), ignored -> new PacketState());
        long now = System.nanoTime();
        synchronized (state.sprintToggles) {
            state.sprintToggles.addLast(now);
            while (!state.sprintToggles.isEmpty()
                    && now - state.sprintToggles.peekFirst() > SECOND_NANOS) {
                state.sprintToggles.removeFirst();
            }
            if (state.sprintToggles.size() > 4) {
                state.sprintWindows++;
                state.sprintToggles.clear();
                if (state.sprintWindows >= 3) {
                    flag(player, "Sprint", "Repeated sprint-toggle bursts exceeded four state changes per second.");
                    state.sprintWindows = 0;
                }
            } else if (state.sprintWindows > 0) {
                state.sprintWindows--;
            }
        }
    }

    private boolean enabled(String check) {
        return plugin.getConfig().getBoolean("checks.windfall.packet." + check + ".enabled", true);
    }

    private void flag(Player player, String check, String reason) {
        if (player == null || !player.isOnline() || !plugin.isEnabled()) return;
        PacketState state = states.computeIfAbsent(player.getUniqueId(), ignored -> new PacketState());
        int vl = state.violationLevels.merge(check, 1, (old, one) ->
                old == Integer.MAX_VALUE ? Integer.MAX_VALUE : old + 1);
        plugin.getAlertManager().send(check, player, vl, reason);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    public void clearAll() {
        states.clear();
    }

    private static final class PacketState {
        private final Deque<Long> packetTimes = new ArrayDeque<>();
        private final Deque<Long> chatMinute = new ArrayDeque<>();
        private final Deque<Long> chatBurst = new ArrayDeque<>();
        private final Deque<Long> inventoryClicks = new ArrayDeque<>();
        private final Deque<Long> chestClicks = new ArrayDeque<>();
        private final Deque<Long> sprintToggles = new ArrayDeque<>();
        private final Map<String, Integer> violationLevels = new HashMap<>();
        private long packetTimesGuard;
        private long creativeWindowStart;
        private long steerWindowStart;
        private long lastXBits;
        private int packetBurstWindows;
        private int invalidRotations;
        private int duplicateMovement;
        private int chatMinuteFlags;
        private int chatBurstFlags;
        private int creativeActions;
        private int invalidVehicleInteract;
        private int steerPackets;
        private int steerBurstFlags;
        private int chestSessionClicks;
        private int chestBurstFlags;
        private int sprintWindows;
        private double lastX;
        private double lastY;
        private double lastZ;
        private float lastYaw;
        private float lastPitch;
        private boolean hasLastMovement;
    }
}
