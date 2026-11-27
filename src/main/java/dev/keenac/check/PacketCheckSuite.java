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
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.github.retrooper.packetevents.PacketEvents;
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
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.scheduler.BukkitTask;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KeenAC packet and inventory check adaptations for modern Paper/PacketEvents.
 *
 * All signals are advisory and are sent through KeenAC's shared evidence and
 * alert pipeline. No client packet is cancelled and no player is kicked here.
 */
public final class PacketCheckSuite extends PacketListenerAbstract implements Listener {

    private final KeenAC plugin;
    private final Map<UUID, PacketState> states = new ConcurrentHashMap<>();
    private final BukkitTask transactionProbeTask;
    private int transactionSequence;

    public PacketCheckSuite(KeenAC plugin) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
        long probeInterval = CheckSettings.longValue(
                plugin, "packet.transaction", "probe-interval-ticks", 40L);
        this.transactionProbeTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::sendTransactionProbes, probeInterval, probeInterval);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID id = player.getUniqueId();
        var type = event.getPacketType();
        long now = System.nanoTime();
        PacketState state = states.computeIfAbsent(id, ignored -> new PacketState());

        if (type == PacketType.Play.Client.PONG) {
            handleTransactionPong(player, state, new WrapperPlayClientPong(event).getId(), now);
            return;
        }

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
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player online = Bukkit.getPlayer(id);
                    PacketState live = states.get(id);
                    if (online != null && online.isOnline() && live != null) {
                        reviewMovementFields(online, live, x, y, z, yaw, pitch);
                    }
                });
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
                            if (live.invalidVehicleInteract >= CheckSettings.integer(plugin, "packet.vehicle", "invalid-interact-confirmations", 4)) {
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
                    && now - state.packetTimes.peekFirst() > CheckSettings.millisNanos(plugin, "packet.packet-order", "burst-window-ms", 100L)) {
                state.packetTimes.removeFirst();
            }
            if (state.packetTimes.size() > CheckSettings.integer(plugin, "packet.packet-order", "max-packets-per-window", 70)) {
                state.packetBurstWindows++;
                state.packetTimes.clear();
                if (state.packetBurstWindows >= CheckSettings.integer(plugin, "packet.packet-order", "confirm-windows", 3)) {
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
                    || y < player.getWorld().getMinHeight() - CheckSettings.integer(plugin, "packet.bad-packets", "world-margin", 64)
                    || y > player.getWorld().getMaxHeight() + CheckSettings.integer(plugin, "packet.bad-packets", "world-margin", 64)) {
                flag(player, "Bad Packets", "Movement packet contained non-finite fields or coordinates outside the world safety bounds.");
            } else if (yaw < -CheckSettings.decimal(plugin, "packet.bad-packets", "max-yaw-degrees", 180.0) || yaw > CheckSettings.decimal(plugin, "packet.bad-packets", "max-yaw-degrees", 180.0) || pitch < -CheckSettings.decimal(plugin, "packet.bad-packets", "max-pitch-degrees", 90.0) || pitch > CheckSettings.decimal(plugin, "packet.bad-packets", "max-pitch-degrees", 90.0)) {
                state.invalidRotations++;
                if (state.invalidRotations >= CheckSettings.integer(plugin, "packet.bad-packets", "confirm-invalid-rotations", 3)) {
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
            if (state.duplicateMovement >= CheckSettings.integer(plugin, "packet.packet-order", "duplicate-samples", 12)) {
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
        if (enabled("crash") && message.length() > CheckSettings.integer(plugin, "packet.crash", "max-chat-length", 32767)) {
            flag(player, "Crash", "Chat payload exceeded the protocol string length limit.");
        }
        if (!enabled("chat")) return;

        synchronized (state.chatMinute) {
            state.chatMinute.addLast(now);
            state.chatBurst.addLast(now);
            while (!state.chatMinute.isEmpty()
                    && now - state.chatMinute.peekFirst() > CheckSettings.millisNanos(plugin, "packet.chat", "minute-window-ms", 60000L)) {
                state.chatMinute.removeFirst();
            }
            while (!state.chatBurst.isEmpty()
                    && now - state.chatBurst.peekFirst() > CheckSettings.millisNanos(plugin, "packet.chat", "burst-window-ms", 2000L)) {
                state.chatBurst.removeFirst();
            }
            if (state.chatMinute.size() > CheckSettings.integer(plugin, "packet.chat", "max-messages-per-minute", 60)) {
                state.chatMinuteFlags++;
                if (state.chatMinuteFlags >= CheckSettings.integer(plugin, "packet.chat", "confirm-minute-windows", 2)) {
                    flag(player, "Chat", "Sustained chat rate exceeded 60 messages in a rolling minute.");
                    state.chatMinuteFlags = 0;
                    state.chatMinute.clear();
                }
            } else if (state.chatBurst.size() > CheckSettings.integer(plugin, "packet.chat", "max-burst-messages", 4)) {
                state.chatBurstFlags++;
                if (state.chatBurstFlags >= CheckSettings.integer(plugin, "packet.chat", "confirm-burst-windows", 2)) {
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
        if (enabled("crash") && raw.length > CheckSettings.integer(plugin, "packet.crash", "max-plugin-message-bytes", 32767)) {
            flag(player, "Crash", "Plugin-message payload exceeded the conservative 32 KiB review limit.");
        }
        String brand = new String(raw, StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String token : CheckSettings.strings(plugin, "packet.client-brand", "review-brands", List.of("wurst", "liquidbounce", "rusherhack", "meteor", "inertia", "aristois", "rise", "vape", "phobos", "salhack", "tenacity"))) {
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
        if (windowId != null && (windowId < 0 || windowId > CheckSettings.integer(plugin, "packet.exploit", "max-window-id", 200))) {
            flag(player, "Exploit", "Inventory click packet contained an out-of-range window id.");
        }
        if (slot != null && (slot < -1 || slot > CheckSettings.integer(plugin, "packet.exploit", "max-slot", 60))) {
            flag(player, "Exploit", "Inventory click packet contained an out-of-range slot.");
        }
        if (entityId != null && (entityId < -1 || entityId > CheckSettings.integer(plugin, "packet.exploit", "max-entity-id", 20000))) {
            flag(player, "Exploit", "Entity interaction packet contained an out-of-range entity id.");
        }
    }

    private void reviewCreative(Player player, PacketState state, int slot, long now) {
        if (slot < -1 || slot > CheckSettings.integer(plugin, "packet.creative", "max-slot", 60)) {
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
            if (now - state.creativeWindowStart > CheckSettings.millisNanos(plugin, "packet.creative", "action-window-ms", 50L)) {
                state.creativeWindowStart = now;
                state.creativeActions = 0;
            }
            state.creativeActions++;
            if (state.creativeActions > CheckSettings.integer(plugin, "packet.creative", "max-actions-per-window", 5)) {
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
                    && now - state.inventoryClicks.peekFirst() > CheckSettings.millisNanos(plugin, "packet.inventory", "click-window-ms", 1000L)) {
                state.inventoryClicks.removeFirst();
            }
            if (state.inventoryClicks.size() > CheckSettings.integer(plugin, "packet.inventory", "max-clicks-per-window", 20)) {
                flag(player, "Inventory", "Inventory click rate exceeded 20 actions in one second.");
                state.inventoryClicks.clear();
            }
        }

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
        if (now - state.steerWindowStart > CheckSettings.millisNanos(plugin, "packet.vehicle", "steer-window-ms", 50L)) {
            state.steerWindowStart = now;
            state.steerPackets = 0;
        }
        state.steerPackets++;
        if (state.steerPackets > CheckSettings.integer(plugin, "packet.vehicle", "max-steer-packets-per-window", 3)) {
            state.steerBurstFlags++;
            if (state.steerBurstFlags >= CheckSettings.integer(plugin, "packet.vehicle", "confirm-burst-windows", 3)) {
                flag(player, "Vehicle", "Repeated vehicle steering packet bursts exceeded three packets per 50 ms.");
                state.steerBurstFlags = 0;
            }
        }
        double magnitude = Math.hypot(forward, sideways);
        if (magnitude > CheckSettings.decimal(plugin, "packet.vehicle", "max-input-magnitude", 2.0)) {
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
                    && now - state.chestClicks.peekFirst() > CheckSettings.millisNanos(plugin, "packet.chest-stealer", "burst-window-ms", 500L)) {
                state.chestClicks.removeFirst();
            }
            state.chestSessionClicks++;
            if (state.chestClicks.size() > CheckSettings.integer(plugin, "packet.chest-stealer", "max-clicks-per-burst", 6)) {
                state.chestBurstFlags++;
                if (state.chestBurstFlags >= CheckSettings.integer(plugin, "packet.chest-stealer", "confirm-bursts", 3)) {
                    flag(player, "Chest Stealer", "Repeated container click bursts exceeded six clicks in 500 ms.");
                    state.chestBurstFlags = 0;
                }
            }
            if (state.chestSessionClicks > CheckSettings.integer(plugin, "packet.chest-stealer", "max-clicks-per-session", 40)) {
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
                    && now - state.sprintToggles.peekFirst() > CheckSettings.millisNanos(plugin, "packet.sprint", "toggle-window-ms", 1000L)) {
                state.sprintToggles.removeFirst();
            }
            if (state.sprintToggles.size() > CheckSettings.integer(plugin, "packet.sprint", "max-toggles-per-window", 4)) {
                state.sprintWindows++;
                state.sprintToggles.clear();
                if (state.sprintWindows >= CheckSettings.integer(plugin, "packet.sprint", "confirm-windows", 3)) {
                    flag(player, "Sprint", "Repeated sprint-toggle bursts exceeded four state changes per second.");
                    state.sprintWindows = 0;
                }
            } else if (state.sprintWindows > 0) {
                state.sprintWindows--;
            }
        }
    }

    private boolean enabled(String check) {
        return CheckSettings.enabled(plugin, "packet." + check, true);
    }

    private void flag(Player player, String check, String reason) {
        if (player == null || !plugin.isEnabled()) return;
        UUID id = player.getUniqueId();
        Runnable deliver = () -> {
            Player online = Bukkit.getPlayer(id);
            if (online == null || !online.isOnline() || !plugin.isEnabled()) return;
            PacketState state = states.computeIfAbsent(id, ignored -> new PacketState());
            int vl = state.violationLevels.merge(check, 1, (old, one) ->
                    old == Integer.MAX_VALUE ? Integer.MAX_VALUE : old + 1);
            plugin.getAlertManager().send(check, online, vl, reason);
        };
        if (Bukkit.isPrimaryThread()) {
            deliver.run();
        } else {
            try {
                Bukkit.getScheduler().runTask(plugin, deliver);
            } catch (RuntimeException ignored) {
                // Ignore packet-thread flags while the plugin is shutting down.
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    private void sendTransactionProbes() {
        if (!enabled("transaction") || !plugin.isEnabled()) return;
        long now = System.nanoTime();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PacketState state = states.computeIfAbsent(player.getUniqueId(), ignored -> new PacketState());
            int ping = Math.max(0, plugin.getPlayerContextManager().snapshot(player).pingMs());
            long timeoutNanos = Math.max(CheckSettings.longValue(plugin, "packet.transaction", "minimum-timeout-ms", 2500L), Math.min(CheckSettings.longValue(plugin, "packet.transaction", "maximum-timeout-ms", 8000L), ping * CheckSettings.longValue(plugin, "packet.transaction", "ping-multiplier", 3L) + CheckSettings.longValue(plugin, "packet.transaction", "ping-offset-ms", 1000L)))
                    * 1_000_000L;

            for (Map.Entry<Integer, Long> entry : state.pendingPings.entrySet()) {
                if (now - entry.getValue() > timeoutNanos
                        && state.pendingPings.remove(entry.getKey(), entry.getValue())) {
                    synchronized (state.skippedTransactions) {
                        state.skippedTransactions.addLast(now);
                        trimTimes(state.skippedTransactions, now, CheckSettings.millisNanos(plugin, "packet.transaction", "review-window-ms", 10000L));
                    }
                }
            }

            synchronized (state.skippedTransactions) {
                trimTimes(state.skippedTransactions, now, CheckSettings.millisNanos(plugin, "packet.transaction", "review-window-ms", 10000L));
                if (state.skippedTransactions.size() >= CheckSettings.integer(plugin, "packet.transaction", "missing-probes-confirm", 3)) {
                    flag(player, "Transaction", "Three or more server ping probes went unanswered within the rolling review window.");
                    state.skippedTransactions.clear();
                }
            }

            int probeId = 0x4B454E00 | (++transactionSequence & 0xFF);
            state.pendingPings.put(probeId, now);
            try {
                PacketEvents.getAPI().getPlayerManager().sendPacket(
                        player, new WrapperPlayServerPing(probeId));
            } catch (RuntimeException exception) {
                state.pendingPings.remove(probeId);
                plugin.getLogger().fine("Could not send transaction probe to "
                        + player.getName() + ": " + exception.getMessage());
            }
        }
    }

    private void handleTransactionPong(Player player, PacketState state, int id, long now) {
        if (!enabled("transaction")) return;
        Long sentAt = state.pendingPings.remove(id);
        if (sentAt != null) return;
        if ((id & 0xFFFFFF00) != 0x4B454E00) return;

        synchronized (state.unknownTransactions) {
            state.unknownTransactions.addLast(now);
            trimTimes(state.unknownTransactions, now, CheckSettings.millisNanos(plugin, "packet.transaction", "review-window-ms", 10000L));
            if (state.unknownTransactions.size() >= CheckSettings.integer(plugin, "packet.transaction", "unmatched-pong-confirm", 2)) {
                flag(player, "Transaction", "Repeated duplicate or unmatched responses to KeenAC ping probes.");
                state.unknownTransactions.clear();
            }
        }
    }

    private void trimTimes(Deque<Long> times, long now, long windowNanos) {
        while (!times.isEmpty() && now - times.peekFirst() > windowNanos) {
            times.removeFirst();
        }
    }

    public void clearAll() {
        if (transactionProbeTask != null) transactionProbeTask.cancel();
        states.clear();
    }

    private static final class PacketState {
        private final Deque<Long> packetTimes = new ArrayDeque<>();
        private final Deque<Long> chatMinute = new ArrayDeque<>();
        private final Deque<Long> chatBurst = new ArrayDeque<>();
        private final Deque<Long> inventoryClicks = new ArrayDeque<>();
        private final Deque<Long> chestClicks = new ArrayDeque<>();
        private final Deque<Long> sprintToggles = new ArrayDeque<>();
        private final Deque<Long> skippedTransactions = new ArrayDeque<>();
        private final Deque<Long> unknownTransactions = new ArrayDeque<>();
        private final Map<Integer, Long> pendingPings = new ConcurrentHashMap<>();
        private final Map<String, Integer> violationLevels = new ConcurrentHashMap<>();

        private long creativeWindowStart;
        private long steerWindowStart;

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
