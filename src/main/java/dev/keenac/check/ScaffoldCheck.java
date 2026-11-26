package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Conservative, alert-only placement consistency review. */
public final class ScaffoldCheck implements Listener {
    private final KeenAC plugin;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Map<UUID, Long> velocity = new ConcurrentHashMap<>();
    private final Map<UUID, ConcurrentLinkedQueue<PlacementIntent>> packetPlacements =
            new ConcurrentHashMap<>();
    private volatile boolean enabled;
    private volatile int threshold;
    private volatile double decay;

    public ScaffoldCheck(KeenAC plugin) { this.plugin = plugin; reloadSettings(); }

    public void reloadSettings() {
        enabled = plugin.getConfig().getBoolean("checks.scaffold.enabled", true);
        threshold = Math.max(3, plugin.getConfig().getInt("checks.scaffold.threshold", 5));
        decay = Math.max(0, plugin.getConfig().getDouble("checks.scaffold.violation-decay", 0.05));
    }

    /**
     * PacketEvents callback. Store only the attempted target block coordinates;
     * the following Bukkit BlockPlaceEvent confirms the server accepted the placement.
     */
    public void recordPacketPlacement(UUID id, int clickedX, int clickedY, int clickedZ,
                                       int faceX, int faceY, int faceZ) {
        if (!enabled || id == null) return;
        ConcurrentLinkedQueue<PlacementIntent> queue =
                packetPlacements.computeIfAbsent(id, ignored -> new ConcurrentLinkedQueue<>());
        queue.add(new PlacementIntent(clickedX + faceX, clickedY + faceY,
                clickedZ + faceZ, System.nanoTime()));
        while (queue.size() > 16) {
            if (queue.poll() == null) break;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConfirmedPacketPlacement(BlockPlaceEvent event) {
        if (!enabled) return;
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        ConcurrentLinkedQueue<PlacementIntent> queue = packetPlacements.get(id);
        if (queue == null) return;

        long packetNow = System.nanoTime();
        PlacementIntent intent = queue.peek();
        while (intent != null && packetNow - intent.atNanos > 500_000_000L) {
            queue.poll();
            intent = queue.peek();
        }
        if (intent == null || intent.x != event.getBlock().getX()
                || intent.y != event.getBlock().getY()
                || intent.z != event.getBlock().getZ()) {
            return;
        }
        queue.poll();

        if (!eligible(player)) {
            states.remove(id);
            return;
        }

        long now = System.currentTimeMillis();
        State state = states.computeIfAbsent(id, ignored -> new State());
        if (state.lastPlace > 0 && now - state.lastPlace > 900) {
            state.score = Math.max(0, state.score - decay * 4);
            state.streak = 0;
        }

        boolean unusualContext = !player.isOnGround()
                && player.getLocation().getBlockY() <= event.getBlock().getY();
        if (unusualContext) {
            state.streak++;
            state.score += 1.0;
        } else {
            state.streak = Math.max(0, state.streak - 1);
            state.score = Math.max(0, state.score - decay);
        }
        state.lastPlace = now;
        if (state.streak >= threshold) {
            state.vl++;
            int vl = state.vl;
            int streak = state.streak;
            plugin.getAlertManager().send("Scaffold", player, vl,
                    "Repeated block placements formed a scaffold-like pattern (" + streak
                            + " consecutive contextual samples).");
            if (!state.alerting) {
                plugin.getLogger().warning("Scaffold placement review for " + player.getName()
                        + " (VL=" + vl + ", consecutive contextual samples=" + streak + ")");
            }
            state.alerting = true;
        } else {
            state.alerting = false;
        }
    }

    private boolean eligible(Player p) {
        if (!p.isOnline() || p.isDead() || p.isInsideVehicle() || p.isFlying()
                || p.isGliding() || p.isSwimming() || p.isRiptiding() || p.isClimbing()
                || p.isInWater() || p.isInLava() || p.isSleeping()
                || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR
                || p.hasPotionEffect(PotionEffectType.LEVITATION)
                || p.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                || p.getPing() > plugin.getConfig().getInt("checks.scaffold.max-ping-ms", 250)) return false;
        Long t = velocity.get(p.getUniqueId());
        return t == null || System.currentTimeMillis() - t > 1200;
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onVelocity(PlayerVelocityEvent e) { velocity.put(e.getPlayer().getUniqueId(), System.currentTimeMillis()); states.remove(e.getPlayer().getUniqueId()); }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onTeleport(PlayerTeleportEvent e) { reset(e.getPlayer().getUniqueId()); }
    @EventHandler(priority=EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) { reset(e.getPlayer().getUniqueId()); }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onGlide(EntityToggleGlideEvent e) { if (e.getEntity() instanceof Player p) reset(p.getUniqueId()); }
    @EventHandler(priority=EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) { reset(e.getPlayer().getUniqueId()); }
    @EventHandler(priority=EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) { reset(e.getPlayer().getUniqueId()); }
    private void reset(UUID id) {
        states.remove(id);
        velocity.remove(id);
        packetPlacements.remove(id);
    }
    public void clearAll() {
        states.clear();
        velocity.clear();
        packetPlacements.values().forEach(ConcurrentLinkedQueue::clear);
        packetPlacements.clear();
    }
    private record PlacementIntent(int x, int y, int z, long atNanos) { }
    private static final class State { int streak, vl; double score; long lastPlace; boolean alerting; }
}
