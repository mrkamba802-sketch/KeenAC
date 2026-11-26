package dev.keenac.check;

import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
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

/** Conservative, alert-only placement consistency review. */
public final class ScaffoldCheck implements Listener {
    private final KeenAC plugin;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Map<UUID, Long> velocity = new ConcurrentHashMap<>();
    private volatile boolean enabled;
    private volatile int threshold;
    private volatile long cooldownMs;
    private volatile double decay;

    public ScaffoldCheck(KeenAC plugin) { this.plugin = plugin; reloadSettings(); }

    public void reloadSettings() {
        enabled = plugin.getConfig().getBoolean("checks.scaffold.enabled", true);
        threshold = Math.max(3, plugin.getConfig().getInt("checks.scaffold.threshold", 5));
        cooldownMs = Math.max(500, plugin.getConfig().getLong("checks.scaffold.cooldown-ms", 1000));
        decay = Math.max(0, plugin.getConfig().getDouble("checks.scaffold.violation-decay", 0.05));
    }

    /**
     * Called on the main thread after PacketEvents decodes a serverbound block-use packet.
     * The clicked face is used to derive the block position the client intended to place.
     */
    public void handlePacketPlacement(UUID id, int clickedX, int clickedY, int clickedZ,
                                       int faceX, int faceY, int faceZ, boolean mainHand) {
        if (!enabled || id == null) return;
        Player player = Bukkit.getPlayer(id);
        if (player == null || !player.isOnline()) return;
        if (!eligible(player)) {
            states.remove(id);
            return;
        }

        // A use-on-block packet can also mean food, buckets, doors or other
        // interactions. Only count it when the selected hand currently holds a block.
        org.bukkit.inventory.ItemStack held = mainHand
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        if (held == null || held.getType().isAir() || !held.getType().isBlock()) return;

        int placedY = clickedY + faceY;
        long now = System.currentTimeMillis();
        State state = states.computeIfAbsent(id, ignored -> new State());
        if (state.lastPlace > 0 && now - state.lastPlace > 900) {
            state.score = Math.max(0, state.score - decay * 4);
            state.streak = 0;
        }

        boolean unusualContext = !player.isOnGround()
                && player.getLocation().getBlockY() <= placedY;
        if (unusualContext) {
            state.streak++;
            state.score += 1.0;
        } else {
            state.streak = Math.max(0, state.streak - 1);
            state.score = Math.max(0, state.score - decay);
        }
        state.lastPlace = now;
        if (state.streak >= threshold && now - state.lastAlert >= cooldownMs) {
            state.lastAlert = now;
            state.vl++;
            int vl = state.vl;
            int streak = state.streak;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(id);
                if (online != null && online.isOnline() && plugin.getAlertManager() != null
                        && plugin.getConfig().getBoolean("checks.scaffold.enabled", true)) {
                    plugin.getAlertManager().send("Scaffold", online, vl);
                    plugin.getLogger().warning("Scaffold placement review for " + online.getName()
                            + " (VL=" + vl + ", consecutive contextual samples=" + streak + ")");
                }
            });
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
    private void reset(UUID id) { states.remove(id); velocity.remove(id); }
    public void clearAll() { states.clear(); velocity.clear(); }
    private static final class State { int streak, vl; double score; long lastPlace, lastAlert; }
}
