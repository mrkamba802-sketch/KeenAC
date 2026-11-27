package dev.keenac.alert;

import dev.keenac.KeenAC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class AlertManager {
    private static final String CHECK_MARKER = "\u0001KEENAC_CHECK\u0001";

    private final KeenAC plugin;
    private final Set<UUID> enabled = new HashSet<>();
    private final Map<AlertKey, Integer> alertLevels = new ConcurrentHashMap<>();
    private final Map<AlertKey, DeliveryState> deliveryStates = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cooldownScheduler = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "KeenAC-AlertCooldown");
                    thread.setDaemon(true);
                    return thread;
                }
            });
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();

    public AlertManager(KeenAC plugin) {
        this.plugin = plugin;
    }

    public boolean toggle(Player player) {
        UUID id = player.getUniqueId();
        if (enabled.remove(id)) return false;
        enabled.add(id);
        return true;
    }

    public void enableForStaff(Player player) {
        if (plugin.getConfig().getBoolean("alerts.auto-enable-for-staff", true)
                && player.hasPermission(plugin.getConfig().getString(
                        "commands.alerts-permission", "keenac.alerts"))) {
            enabled.add(player.getUniqueId());
        }
    }

    public boolean isEnabled(Player player) {
        return enabled.contains(player.getUniqueId());
    }

    public void send(String checkName, Player suspect, int violationLevel) {
        send(checkName, suspect, violationLevel, "Confirmed check violation.");
    }

    public void send(String checkName, Player suspect, int violationLevel, String reason) {
        if (suspect == null || !plugin.isEnabled()) return;

        UUID playerId = suspect.getUniqueId();
        recordEvidence(playerId, checkName, violationLevel, reason);
        if (!plugin.getConfig().getBoolean("alerts.enabled", true)) return;
        AlertKey key = new AlertKey(playerId, checkName);
        DeliveryState state = deliveryStates.computeIfAbsent(key, ignored -> new DeliveryState());
        PendingAlert immediate = null;
        long now = System.nanoTime();
        long cooldownNanos = Math.max(0L,
                plugin.getConfig().getLong("alerts.delay-seconds", 3L)) * 1_000_000_000L;

        synchronized (state) {
            long remaining = state.lastSentNanos == 0L
                    ? 0L : cooldownNanos - (now - state.lastSentNanos);
            if (remaining <= 0L) {
                state.lastSentNanos = now;
                state.pending = null;
                if (state.scheduled != null) {
                    state.scheduled.cancel(false);
                    state.scheduled = null;
                }
                immediate = new PendingAlert(playerId, checkName, reason);
            } else {
                // Keep the latest context while the same player/check is cooling down.
                // This guarantees a one-shot violation is delivered after the real-time delay.
                state.pending = new PendingAlert(playerId, checkName, reason);
                if (state.scheduled == null || state.scheduled.isDone()) {
                    schedulePending(key, state, remaining);
                }
            }
        }

        if (immediate != null) {
            dispatch(immediate);
        }
    }

    private void recordEvidence(UUID playerId, String checkName, int violationLevel, String reason) {
        Runnable capture = () -> {
            if (!plugin.isEnabled()) return;
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                plugin.getEvidenceManager().record(player, checkName, violationLevel, reason);
            }
        };
        if (Bukkit.isPrimaryThread()) {
            capture.run();
        } else {
            try {
                Bukkit.getScheduler().runTask(plugin, capture);
            } catch (RuntimeException ignored) {
                // Ignore evidence capture if the plugin is already shutting down.
            }
        }
    }

    private void schedulePending(AlertKey key, DeliveryState state, long delayNanos) {
        try {
            state.scheduled = cooldownScheduler.schedule(() -> {
                if (!plugin.isEnabled()) return;
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> flushPending(key, state));
                } catch (RuntimeException ignored) {
                    // The plugin may be shutting down while a cooldown is expiring.
                }
            }, Math.max(1L, delayNanos), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException ignored) {
            state.pending = null;
            state.scheduled = null;
        }
    }

    private void flushPending(AlertKey key, DeliveryState state) {
        if (!plugin.isEnabled() || !plugin.getConfig().getBoolean("alerts.enabled", true)) {
            synchronized (state) {
                state.pending = null;
                state.scheduled = null;
            }
            return;
        }

        PendingAlert pending;
        synchronized (state) {
            pending = state.pending;
            if (pending == null) {
                state.scheduled = null;
                return;
            }

            long now = System.nanoTime();
            long cooldownNanos = Math.max(0L,
                    plugin.getConfig().getLong("alerts.delay-seconds", 3L)) * 1_000_000_000L;
            long remaining = state.lastSentNanos == 0L
                    ? 0L : cooldownNanos - (now - state.lastSentNanos);
            if (remaining > 0L) {
                schedulePending(key, state, remaining);
                return;
            }

            state.lastSentNanos = now;
            state.pending = null;
            state.scheduled = null;
        }

        dispatch(pending);
    }

    private void dispatch(PendingAlert pending) {
        Runnable deliver = () -> {
            Player suspect = Bukkit.getPlayer(pending.playerId);
            if (suspect != null && suspect.isOnline()
                    && plugin.getConfig().getBoolean("alerts.enabled", true)) {
                emit(pending.checkName, suspect, pending.reason);
            }
        };
        if (Bukkit.isPrimaryThread()) {
            deliver.run();
        } else {
            try {
                Bukkit.getScheduler().runTask(plugin, deliver);
            } catch (RuntimeException ignored) {
                // The plugin may be shutting down before this delivery runs.
            }
        }
    }

    private void emit(String checkName, Player suspect, String reason) {
        // The x1, x2, x3 sequence advances only when an alert is actually emitted.
        AlertKey key = new AlertKey(suspect.getUniqueId(), checkName);
        int alertLevel = alertLevels.merge(key, 1, (current, one) ->
                current == Integer.MAX_VALUE ? Integer.MAX_VALUE : current + 1);

        String prefix = plugin.getConfig().getString("alerts.prefix", "&bKeenAC &8»");
        String format = plugin.getConfig().getString(
                "alerts.format", "{prefix} &f{player} &7failed &c{check} &8(x{vl})");
        String safeReason = reason == null || reason.isBlank()
                ? "No additional reason was recorded." : reason;
        var context = plugin.getPlayerContextManager().snapshot(suspect);
        int ping = context.pingMs();
        double tps = context.tps();
        String tpsText = String.format(Locale.ROOT, "%.2f", tps);

        String templated = format
                .replace("{prefix}", prefix)
                .replace("{player}", suspect.getName())
                .replace("{check}", CHECK_MARKER)
                .replace("{vl}", Integer.toString(alertLevel))
                .replace("{reason}", safeReason)
                .replace("{ping}", Integer.toString(ping))
                .replace("{tps}", tpsText);

        String plainMessage = templated.replace(CHECK_MARKER, checkName);
        if (plugin.getConfig().getBoolean("alerts.console", true)) {
            Bukkit.getConsoleSender().sendMessage(
                    ChatColor.translateAlternateColorCodes('&', plainMessage));
        }

        String permission = plugin.getConfig().getString(
                "commands.alerts-permission", "keenac.alerts");
        int markerIndex = templated.indexOf(CHECK_MARKER);
        Component message;
        if (markerIndex >= 0) {
            String before = templated.substring(0, markerIndex);
            String after = templated.substring(markerIndex + CHECK_MARKER.length());
            Component details = legacy.deserialize(
                    "&bПроверка: &f" + checkName
                            + "\n&bИгрок: &f" + suspect.getName()
                            + "\n&bПричина: &f" + safeReason
                            + "\n&bПинг: &f" + ping + " ms"
                            + "\n&bTPS: &f" + tpsText
                            + "\n&bУровень: &fх" + alertLevel);
            Component check = legacy.deserialize("&c" + checkName)
                    .hoverEvent(HoverEvent.showText(details));
            message = legacy.deserialize(before)
                    .append(check)
                    .append(legacy.deserialize(after));
        } else {
            message = legacy.deserialize(plainMessage);
        }

        for (Player recipient : Bukkit.getOnlinePlayers()) {
            if (enabled.contains(recipient.getUniqueId())
                    && recipient.hasPermission(permission)) {
                recipient.sendMessage(message);
            }
        }
    }

    public void clear(Player player) {
        UUID id = player.getUniqueId();
        enabled.remove(id);
        alertLevels.keySet().removeIf(key -> key.playerId().equals(id));
        deliveryStates.entrySet().removeIf(entry -> {
            if (!entry.getKey().playerId().equals(id)) return false;
            synchronized (entry.getValue()) {
                if (entry.getValue().scheduled != null) {
                    entry.getValue().scheduled.cancel(false);
                }
                entry.getValue().pending = null;
            }
            return true;
        });
    }

    public void clearAll() {
        enabled.clear();
        alertLevels.clear();
        deliveryStates.values().forEach(state -> {
            synchronized (state) {
                if (state.scheduled != null) state.scheduled.cancel(false);
                state.pending = null;
                state.scheduled = null;
            }
        });
        deliveryStates.clear();
        cooldownScheduler.shutdownNow();
    }

    private record AlertKey(UUID playerId, String checkName) { }
    private record PendingAlert(UUID playerId, String checkName, String reason) { }

    private static final class DeliveryState {
        private long lastSentNanos;
        private PendingAlert pending;
        private ScheduledFuture<?> scheduled;
    }
}
