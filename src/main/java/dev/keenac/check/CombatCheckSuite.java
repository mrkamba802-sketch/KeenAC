package dev.keenac.check;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KeenAC combat-check adaptation for KeenAC.
 *
 * Packet callbacks only copy primitive identifiers and schedule Bukkit reads
 * onto the primary thread. This suite is advisory: it never cancels attacks,
 * kicks players, or applies punishments.
 */
public final class CombatCheckSuite extends PacketListenerAbstract implements Listener {
    private static final long HISTORY_NANOS = 1_500_000_000L;

    private final KeenAC plugin;
    private final Map<UUID, CombatState> states = new ConcurrentHashMap<>();
    private final Map<Integer, TargetMovement> targetMovement = new HashMap<>();

    public CombatCheckSuite(KeenAC plugin) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || event.getPacketType() != PacketType.Play.Server.SPAWN_ENTITY) return;
        WrapperPlayServerSpawnEntity packet = new WrapperPlayServerSpawnEntity(event);
        if (packet.getEntityType() != EntityTypes.END_CRYSTAL) return;

        UUID id = player.getUniqueId();
        int entityId = packet.getEntityId();
        long now = System.nanoTime();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(id);
            if (online == null || !online.isOnline()) return;
            CombatState state = state(online);
            state.crystalSpawnTimes.put(entityId, now);
            state.lastCrystalSpawnNanos = now;
            state.crystalSpawnTimes.entrySet().removeIf(entry ->
                    now - entry.getValue() > 5_000_000_000L);
        });
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
            return;
        }

        WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
        UUID id = player.getUniqueId();
        int entityId = packet.getEntityId();
        WrapperPlayClientInteractEntity.InteractAction action = packet.getAction();

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(id);
            if (online == null || !online.isOnline()) return;
            if (action == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                handleAttack(online, entityId);
            } else {
                handleInteract(online, entityId);
            }
        });
    }

    private void handleAttack(Player attacker, int targetId) {
        if (!attacker.isOnline()) return;
        CombatState state = state(attacker);
        long now = System.nanoTime();

        // KillAura A: detect repeated switching across too many distinct targets
        // in consecutive one-second windows. AimCheck separately reviews rotation
        // smoothness and target tracking, avoiding duplicate rotation heuristics here.
        if (enabled("kill-aura")) {
            if (state.auraWindowStart == 0L) state.auraWindowStart = now;
            if (now - state.auraWindowStart > CheckSettings.millisNanos(plugin, "combat.kill-aura", "window-ms", 1000L)) {
                if (state.auraTargets.size() > CheckSettings.integer(plugin, "combat.kill-aura", "max-distinct-targets", 4)) state.auraWindows++;
                else state.auraWindows = Math.max(0, state.auraWindows - 1);
                if (state.auraWindows >= CheckSettings.integer(plugin, "combat.kill-aura", "confirm-windows", 2)) {
                    flag(attacker, "Kill Aura", "Repeated attack windows contained more than four distinct entity targets.");
                    state.auraWindows = 0;
                }
                state.auraTargets.clear();
                state.auraWindowStart = now;
            }
            state.auraTargets.add(targetId);
        }

        // Autoclicker A: stable attack intervals over a sufficiently large sample.
        if (enabled("autoclicker")) {
            if (state.lastAttackNanos != 0L) {
                long interval = now - state.lastAttackNanos;
                if (interval / 1_000_000.0 >= CheckSettings.decimal(plugin, "combat.autoclicker", "min-interval-ms", 20.0) && interval / 1_000_000.0 <= CheckSettings.decimal(plugin, "combat.autoclicker", "max-interval-ms", 500.0)) {
                    state.attackIntervals.addLast(interval / 1_000_000.0);
                    while (state.attackIntervals.size() > CheckSettings.integer(plugin, "combat.autoclicker", "sample-size", 32)) state.attackIntervals.removeFirst();
                    reviewAutoclicker(attacker, state);
                } else if (interval > 500_000_000L) {
                    state.attackIntervals.clear();
                }
            }
        }
        state.lastAttackNanos = now;

        // Multi Interact A: a normal client cannot attack multiple distinct
        // entities in one client tick. Repeated packets for one target are
        // deliberately handled by the autoclicker signal instead.
        if (enabled("multi-interact")) {
            if (now - state.attackWindowStart > CheckSettings.millisNanos(plugin, "combat.multi-interact", "window-ms", 50L)) {
                state.attackWindowStart = now;
                state.attackTargets.clear();
            }
            state.attackTargets.add(targetId);
            if (state.attackTargets.size() > CheckSettings.integer(plugin, "combat.multi-interact", "max-distinct-targets", 1)) {
                flag(attacker, "Multi Interact", "Different entity attack targets arrived inside one 50 ms packet window.");
                state.attackTargets.clear();
            }
        }

        // Self Interact: the player's own entity id is never a valid attack target.
        if (enabled("self-interact") && targetId == attacker.getEntityId()) {
            flag(attacker, "Self Interact", "Attack packet targeted the sender's own entity id.");
        }

        if (enabled("crystal")) {
            state.crystalSpawnTimes.entrySet().removeIf(entry ->
                    now - entry.getValue() > CheckSettings.millisNanos(plugin, "combat.crystal", "stale-entity-ms", 60000L));

            long sinceSpawn = state.lastCrystalSpawnNanos == 0L
                    ? Long.MAX_VALUE : (now - state.lastCrystalSpawnNanos) / 1_000_000L;
            if (sinceSpawn < CheckSettings.longValue(plugin, "combat.crystal", "buffer-decay-window-ms", 100L)) {
                if (sinceSpawn < CheckSettings.longValue(plugin, "combat.crystal", "fast-cycle-window-ms", 40L)) {
                    state.crystalBuffer += CheckSettings.decimal(plugin, "combat.crystal", "fast-buffer-add", 2.0);
                    if (state.crystalBuffer > CheckSettings.decimal(plugin, "combat.crystal", "fast-buffer-threshold", 6.0)) {
                        flag(attacker, "Crystal", "Repeated crystal place-to-attack cycles completed in under 40 ms.");
                        state.crystalBuffer = CheckSettings.decimal(plugin, "combat.crystal", "fast-buffer-reset", 3.0);
                    }
                } else {
                    state.crystalBuffer = Math.max(0.0, state.crystalBuffer - CheckSettings.decimal(plugin, "combat.crystal", "fast-buffer-decay", 0.25));
                }
            } else {
                state.crystalBuffer = Math.max(0.0, state.crystalBuffer - CheckSettings.decimal(plugin, "combat.crystal", "idle-buffer-decay", 0.1));
            }

            Long spawnedAt = state.crystalSpawnTimes.get(targetId);
            if (spawnedAt != null && now - spawnedAt >= 0L
                    && now - spawnedAt <= CheckSettings.millisNanos(plugin, "combat.crystal", "spawn-tick-window-ms", 50L)) {
                state.crystalBuffer += CheckSettings.decimal(plugin, "combat.crystal", "spawn-buffer-add", 1.5);
                if (state.crystalBuffer > CheckSettings.decimal(plugin, "combat.crystal", "spawn-buffer-threshold", 5.0)) {
                    flag(attacker, "Crystal", "Repeated attacks targeted an end crystal during its spawn tick (entity-id prediction review).");
                    state.crystalBuffer = 0.0;
                }
            }
        }

        if (enabled("criticals")) {
            if (attacker.isOnGround()) {
                state.invalidCriticalAttacks = Math.max(0, state.invalidCriticalAttacks - 1);
            } else if (!attacker.isInsideVehicle() && !attacker.isGliding()
                    && !attacker.isSwimming() && !attacker.isClimbing()
                    && !attacker.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING)
                    && !attacker.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION)) {
                double downwardVelocity = -attacker.getVelocity().getY();
                boolean validFallingMotion = downwardVelocity > CheckSettings.decimal(plugin, "combat.criticals", "min-downward-velocity", 0.11)
                        && downwardVelocity < CheckSettings.decimal(plugin, "combat.criticals", "max-downward-velocity", 0.50);
                // Mirror KeenAC's deltaY >= -0.01 guard: an attack during a
                // clear upward jump is not considered a failed critical attempt.
                if (!validFallingMotion && downwardVelocity >= -CheckSettings.decimal(plugin, "combat.criticals", "upward-tolerance", 0.01)) {
                    state.invalidCriticalAttacks++;
                    if (state.invalidCriticalAttacks >= CheckSettings.integer(plugin, "combat.criticals", "confirm-attacks", 4)) {
                        flag(attacker, "Criticals",
                                "Repeated airborne attacks lacked the expected falling motion for a vanilla critical hit.");
                        state.invalidCriticalAttacks = 0;
                    }
                } else {
                    state.invalidCriticalAttacks = Math.max(0, state.invalidCriticalAttacks - 1);
                }
            }
        }
    }

    private void handleInteract(Player player, int targetId) {
        if (!enabled("interact-cursor")) return;
        Entity target = findEntity(player, targetId);
        if (target == null || target == player || target.getWorld() != player.getWorld()) return;
        if (player.getGameMode().name().equals("SPECTATOR")) return;

        if (!isLookingAt(player, target, CheckSettings.decimal(plugin, "combat.interact-cursor", "max-distance", 4.5))) {
            CombatState state = state(player);
            state.badCursorInteractions++;
            if (state.badCursorInteractions >= CheckSettings.integer(plugin, "combat.interact-cursor", "confirm-interactions", 3)) {
                flag(player, "Interact Cursor", "Repeated entity interaction packets did not align with the server-side look ray.");
                state.badCursorInteractions = 0;
            }
        } else {
            state(player).badCursorInteractions = Math.max(0, state(player).badCursorInteractions - 1);
        }
    }

    private void reviewAutoclicker(Player player, CombatState state) {
        if (state.attackIntervals.size() < CheckSettings.integer(plugin, "combat.autoclicker", "minimum-samples", 20)) return;
        double mean = 0.0;
        for (double interval : state.attackIntervals) mean += interval;
        mean /= state.attackIntervals.size();

        double variance = 0.0;
        for (double interval : state.attackIntervals) {
            double diff = interval - mean;
            variance += diff * diff;
        }
        double deviation = Math.sqrt(variance / state.attackIntervals.size());

        // Ignore very slow clicking and use a conservative threshold. A single
        // regular burst is never enough to flag.
        if (mean >= CheckSettings.decimal(plugin, "combat.autoclicker", "mean-min-ms", 48.0)
                && mean <= CheckSettings.decimal(plugin, "combat.autoclicker", "mean-max-ms", 160.0)
                && deviation <= CheckSettings.decimal(plugin, "combat.autoclicker", "max-standard-deviation-ms", 1.35)) {
            state.autoclickerWindows++;
            if (state.autoclickerWindows >= CheckSettings.integer(plugin, "combat.autoclicker", "confirm-windows", 2)) {
                flag(player, "Autoclicker", String.format(java.util.Locale.ROOT,
                        "Repeated low-variance attack timing (mean %.1f ms, deviation %.2f ms).",
                        mean, deviation));
                state.autoclickerWindows = 0;
                state.attackIntervals.clear();
            }
        } else {
            state.autoclickerWindows = Math.max(0, state.autoclickerWindows - 1);
        }
    }

    private void reviewBacktrack(Player attacker, Player target, CombatState state, long now) {
        if (!enabled("backtrack")) return;
        TargetMovement movement = targetMovement.get(target.getEntityId());
        if (movement == null || now - movement.lastMovedNanos > CheckSettings.millisNanos(plugin, "combat.backtrack", "target-movement-max-age-ms", 180L)) return;
        int ping = plugin.getPlayerContextManager().snapshot(attacker).pingMs();
        if (ping > CheckSettings.integer(plugin, "combat.backtrack", "max-ping-ms", 150) || movement.lastDelta < CheckSettings.decimal(plugin, "combat.backtrack", "min-target-displacement", 0.55)) return;

        double currentDistance = distanceToBox(attacker.getEyeLocation(), target);
        // Existing Reach is the authoritative distance check. This additional
        // Backtrack signal only reviews a recent, large target displacement.
        if (currentDistance > CheckSettings.decimal(plugin, "combat.backtrack", "min-distance", 3.65) && currentDistance <= CheckSettings.decimal(plugin, "combat.backtrack", "max-distance", 5.25)) {
            state.backtrackHits++;
            if (state.backtrackHits >= CheckSettings.integer(plugin, "combat.backtrack", "confirm-hits", 3)) {
                flag(attacker, "Backtrack", String.format(java.util.Locale.ROOT,
                        "Repeated attacks followed a recent target displacement of %.2f blocks (ping %d ms).",
                        movement.lastDelta, ping));
                state.backtrackHits = 0;
            }
        } else {
            state.backtrackHits = Math.max(0, state.backtrackHits - 1);
        }
    }

    private void reviewHitbox(Player attacker, Player target, CombatState state, long now) {
        if (!enabled("hitboxes")) return;
        int ping = plugin.getPlayerContextManager().snapshot(attacker).pingMs();
        if (ping > CheckSettings.integer(plugin, "combat.hitboxes", "max-ping-ms", 120) || target.isDead() || target.getWorld() != attacker.getWorld()) return;
        double distance = distanceToBox(attacker.getEyeLocation(), target);
        if (distance > CheckSettings.decimal(plugin, "combat.hitboxes", "min-distance", 3.2) && distance < CheckSettings.decimal(plugin, "combat.hitboxes", "max-distance", 4.4) && !isLookingAt(attacker, target, CheckSettings.decimal(plugin, "combat.hitboxes", "look-distance", 4.5))) {
            state.hitboxHits++;
            if (state.hitboxHits >= CheckSettings.integer(plugin, "combat.hitboxes", "confirm-hits", 3)) {
                flag(attacker, "Hitboxes", "Repeated attacks were outside the server-side look-ray intersection with the target hitbox.");
                state.hitboxHits = 0;
            }
        } else {
            state.hitboxHits = Math.max(0, state.hitboxHits - 1);
        }
    }

    private Entity findEntity(Player viewer, int entityId) {
        for (Entity entity : viewer.getWorld().getEntities()) {
            if (entity.getEntityId() == entityId) return entity;
        }
        return null;
    }

    private boolean isLookingAt(Player player, Entity target, double maxDistance) {
        Location eye = player.getEyeLocation();
        RayTraceResult result = player.getWorld().rayTraceEntities(
                eye, eye.getDirection(), maxDistance, 0.05,
                entity -> entity != player);
        return result != null && result.getHitEntity() != null
                && result.getHitEntity().getUniqueId().equals(target.getUniqueId());
    }

    private double distanceToBox(Location eye, Entity target) {
        var box = target.getBoundingBox();
        double x = clamp(eye.getX(), box.getMinX(), box.getMaxX());
        double y = clamp(eye.getY(), box.getMinY(), box.getMaxY());
        double z = clamp(eye.getZ(), box.getMinZ(), box.getMaxZ());
        return eye.toVector().distance(new Vector(x, y, z));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;
        Player player = event.getPlayer();
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double delta = Math.sqrt(dx * dx + dy * dy + dz * dz);
        long now = System.nanoTime();

        TargetMovement movement = targetMovement.computeIfAbsent(player.getEntityId(),
                ignored -> new TargetMovement());
        movement.lastMovedNanos = now;
        movement.lastDelta = delta;

        if (enabled("macro") && delta > CheckSettings.decimal(plugin, "combat.macro", "min-movement-delta", 0.025) && player.isOnGround()
                && !player.isInsideVehicle() && !player.isFlying()) {
            CombatState state = state(player);
            int direction = quantizeDirection(dx, dz);
            state.movementPattern.addLast(direction);
            while (state.movementPattern.size() > CheckSettings.integer(plugin, "combat.macro", "sequence-length", 12) * 4) state.movementPattern.removeFirst();
            reviewMacro(player, state);
        }
    }

    private void reviewMacro(Player player, CombatState state) {
        int sequenceLength = CheckSettings.integer(plugin, "combat.macro", "sequence-length", 12);\n        if (state.movementPattern.size() < sequenceLength * 3) return;
        Integer[] samples = state.movementPattern.toArray(new Integer[0]);
        int size = samples.length;
        boolean repeat = true;
        for (int i = 0; i < sequenceLength; i++) {
            if (!samples[size - sequenceLength + i].equals(samples[size - sequenceLength * 2 + i])
                    || !samples[size - sequenceLength * 2 + i].equals(samples[size - sequenceLength * 3 + i])) {
                repeat = false;
                break;
            }
        }
        if (repeat) {
            state.macroRepeats++;
            state.movementPattern.clear();
            if (state.macroRepeats >= CheckSettings.integer(plugin, "combat.macro", "confirm-repeats", 2) {
                flag(player, "Macro", "Repeated ground-movement direction sequence detected across three consecutive windows.");
                state.macroRepeats = 0;
            }
        }
    }

    private int quantizeDirection(double dx, double dz) {
        double deadzone = CheckSettings.decimal(plugin, "combat.macro", "direction-deadzone", 0.025);
        int x = Math.abs(dx) < deadzone ? 0 : (dx > 0 ? 1 : -1);
        int z = Math.abs(dz) < deadzone ? 0 : (dz > 0 ? 1 : -1);
        return (x + 1) * 3 + z + 1;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!enabled("fast-heal")) return;
        Player player = event.getPlayer();
        CombatState state = state(player);
        long now = System.nanoTime();
        long elapsed = now - state.lastConsumeNanos;
        state.lastConsumeNanos = now;
        if (elapsed > 0L && elapsed < CheckSettings.millisNanos(plugin, "combat.fast-heal", "minimum-consume-interval-ms", 1250L)) {
            state.fastConsumes++;
            if (state.fastConsumes >= CheckSettings.integer(plugin, "combat.fast-heal", "confirm-intervals", 2)) {
                flag(player, "Fast Heal", "Multiple item-consumption completions occurred faster than the vanilla use duration.");
                state.fastConsumes = 0;
            }
        } else {
            state.fastConsumes = Math.max(0, state.fastConsumes - 1);
        }
    }

    private boolean enabled(String check) {
        // KeenAC Fast Heal A is explicitly protocol-gated to legacy 1.6–1.8
        // clients (protocol 5–107), so it must not run by default on 1.21.4.
        boolean defaultEnabled = !"fast-heal".equals(check);
        return plugin.getConfig().getBoolean(
                "checks.checks.combat." + check + ".enabled", defaultEnabled);
    }

    private void flag(Player player, String check, String reason) {
        if (player == null || !player.isOnline() || !plugin.isEnabled()) return;
        CombatState state = state(player);
        int vl = state.violationLevels.merge(check, 1, (old, one) ->
                old == Integer.MAX_VALUE ? Integer.MAX_VALUE : old + 1);
        plugin.getAlertManager().send(check, player, vl, reason);
    }

    private CombatState state(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), ignored -> new CombatState());
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
        targetMovement.remove(event.getPlayer().getEntityId());
    }

    public void clearAll() {
        states.clear();
        targetMovement.clear();
    }

    private static final class CombatState {
        private final Deque<Double> attackIntervals = new ArrayDeque<>();
        private final Deque<Integer> movementPattern = new ArrayDeque<>();
        private final Map<String, Integer> violationLevels = new HashMap<>();
        private final Set<Integer> attackTargets = new HashSet<>();
        private final Set<Integer> auraTargets = new HashSet<>();
        private long lastAttackNanos;
        private long auraWindowStart;
        private long attackWindowStart;
        private long lastConsumeNanos;
        private long lastCrystalSpawnNanos;
        private final Map<Integer, Long> crystalSpawnTimes = new ConcurrentHashMap<>();
        private int autoclickerWindows;
        private int backtrackHits;
        private int hitboxHits;
        private int badCursorInteractions;
        private int macroRepeats;
        private int invalidCriticalAttacks;
        private double crystalBuffer;
        private int auraWindows;
        private int fastConsumes;
    }

    private static final class TargetMovement {
        private long lastMovedNanos;
        private double lastDelta;
    }
}
