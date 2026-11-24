package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class AirStuckCheck {

    private static final String ALERT_PERMISSION = "keenac.alerts";
    private static final String ALERT_FORMAT =
            "&bKeenAC &8» &f%s &7failed &eAirStuck &8(VL: %d)";

    private final KeenAC plugin;
    private final Map<UUID, PlayerState> states = new HashMap<>();
    private long serverTick;

    public AirStuckCheck(KeenAC plugin) {
        this.plugin = plugin;
    }

    public void tick() {
        serverTick++;

        if (!plugin.getConfig().getBoolean("checks.air-stuck.enabled", true)) {
            for (PlayerState state : states.values()) {
                clearSuspicion(state);
            }
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            inspect(player);
        }

        Iterator<UUID> iterator = states.keySet().iterator();
        while (iterator.hasNext()) {
            if (Bukkit.getPlayer(iterator.next()) == null) {
                iterator.remove();
            }
        }
    }

    private void inspect(Player player) {
        PlayerState state = states.computeIfAbsent(
                player.getUniqueId(), ignored -> new PlayerState(player.getLocation()));

        if (isExemptMovementState(player)) {
            state.worldId = player.getWorld().getUID();
            state.anchor = player.getLocation().clone();
            state.stationaryTicks = 0;
            clearSuspicion(state);
            return;
        }

        Location current = player.getLocation();
        if (!state.worldId.equals(current.getWorld().getUID())) {
            state.worldId = current.getWorld().getUID();
            state.anchor = current.clone();
            state.stationaryTicks = 0;
            clearSuspicion(state);
            return;
        }

        double movementTolerance = Math.max(0.001,
                plugin.getConfig().getDouble("checks.air-stuck.movement-tolerance", 0.02));
        double velocityTolerance = Math.max(0.001,
                plugin.getConfig().getDouble("checks.air-stuck.velocity-tolerance", 0.02));

        boolean moved = state.anchor.distanceSquared(current)
                > movementTolerance * movementTolerance;
        boolean hasVelocity = player.getVelocity().lengthSquared()
                > velocityTolerance * velocityTolerance;

        if (player.isOnGround() || moved || hasVelocity) {
            state.anchor = current.clone();
            state.stationaryTicks = 0;
            clearSuspicion(state);
            return;
        }

        state.stationaryTicks++;

        int requiredTicks = Math.max(1,
                plugin.getConfig().getInt("checks.air-stuck.stationary-ticks", 10));

        if (plugin.getConfig().getBoolean("checks.air-stuck.ping-compensation.enabled", true)) {
            int maxExtraTicks = Math.max(0,
                    plugin.getConfig().getInt("checks.air-stuck.ping-compensation.max-extra-ticks", 40));
            int pingExtraTicks = Math.max(0, player.getPing()) / 50;
            requiredTicks += Math.min(maxExtraTicks, pingExtraTicks);
        }

        if (state.stationaryTicks >= requiredTicks) {
            flag(player, state);
        }
    }

    private boolean isExemptMovementState(Player player) {
        // There is deliberately no operator/OP exemption: OPs are checked like every other player.
        if (player.isDead() || !player.isValid()) {
            return true;
        }

        if (player.getGameMode() == GameMode.SPECTATOR
                || player.getAllowFlight()
                || player.isFlying()
                || player.isInsideVehicle()
                || player.isGliding()
                || player.isSwimming()
                || player.isClimbing()
                || player.isInWater()
                || player.isInLava()
                || player.isSleeping()) {
            return true;
        }

        return player.hasPotionEffect(PotionEffectType.LEVITATION)
                || player.hasPotionEffect(PotionEffectType.SLOW_FALLING);
    }

    private void flag(Player player, PlayerState state) {
        if (!state.flagged) {
            state.flagged = true;
            state.violationLevel++;
            state.nextAlertTick = serverTick;
            state.alertedDuringCurrentFlag = false;
        }

        if (serverTick < state.nextAlertTick) {
            return;
        }

        long cooldownTicks = Math.max(3L,
                plugin.getConfig().getLong("alerts.delay-seconds", 3L)) * 20L;

        if (state.hasAlerted && serverTick - state.lastAlertTick < cooldownTicks) {
            state.nextAlertTick = state.lastAlertTick + cooldownTicks;
            return;
        }

        // The first alert reports the initial VL. Further alerts for a continuous violation
        // increase VL once per cooldown interval.
        if (state.alertedDuringCurrentFlag) {
            state.violationLevel++;
        }

        String message = ChatColor.translateAlternateColorCodes('&',
                String.format(ALERT_FORMAT, player.getName(), state.violationLevel));

        Bukkit.getConsoleSender().sendMessage(message);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission(ALERT_PERMISSION)) {
                online.sendMessage(message);
            }
        }

        state.hasAlerted = true;
        state.lastAlertTick = serverTick;
        state.nextAlertTick = serverTick + cooldownTicks;
        state.alertedDuringCurrentFlag = true;
    }

    private void clearSuspicion(PlayerState state) {
        state.flagged = false;
        state.stationaryTicks = 0;
        state.alertedDuringCurrentFlag = false;
        state.nextAlertTick = 0L;
    }

    public boolean isFlagged(Player player) {
        PlayerState state = states.get(player.getUniqueId());
        return state != null && state.flagged
                && plugin.getConfig().getBoolean("checks.air-stuck.cancel-actions", true);
    }

    public void reset(Player player) {
        states.remove(player.getUniqueId());
    }

    public void forget(UUID playerId) {
        states.remove(playerId);
    }

    public void clearAll() {
        states.clear();
    }

    private static final class PlayerState {
        private UUID worldId;
        private Location anchor;
        private int stationaryTicks;
        private int violationLevel;
        private boolean flagged;
        private boolean hasAlerted;
        private boolean alertedDuringCurrentFlag;
        private long lastAlertTick;
        private long nextAlertTick;

        private PlayerState(Location initialLocation) {
            this.worldId = initialLocation.getWorld().getUID();
            this.anchor = initialLocation.clone();
        }
    }
}
