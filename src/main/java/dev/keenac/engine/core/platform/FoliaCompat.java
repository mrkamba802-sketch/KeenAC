package dev.keenac.engine.core.platform;
import dev.keenac.KeenAC;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
public final class FoliaCompat {
    private final boolean isFolia;
    private Method entitySchedulerRun;
    private boolean initialized;
    public FoliaCompat(boolean isFolia) {
        this.isFolia = isFolia;
    }
    public void init(Logger logger) {
        if (!isFolia) return;
        Class<?> entitySchedulerClass;
        try {
            entitySchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Folia EntityScheduler class not found", e);
            return;
        }
        Class<?>[][] signatures = {
            {Plugin.class, java.util.function.Consumer.class, Runnable.class},
            {Object.class, java.util.function.Consumer.class, Runnable.class}
        };
        for (Class<?>[] params : signatures) {
            try {
                entitySchedulerRun = entitySchedulerClass.getMethod("run", params);
                initialized = true;
                return;
            } catch (NoSuchMethodException ignored) {
            }
        }
        for (java.lang.reflect.Method m : entitySchedulerClass.getMethods()) {
            if (!m.getName().equals("run") || m.getParameterCount() != 3) continue;
            entitySchedulerRun = m;
            initialized = true;
            logger.info("KeenAC: Resolved EntityScheduler#run via method scan: " + m);
            return;
        }
        logger.warning("KeenAC: No viable EntityScheduler#run method found — entity scheduling degraded");
    }
    public void runOnEntity(Entity entity, Runnable task, Runnable fallback) {
        if (!isFolia || !initialized || entity == null) {
            if (task != null) task.run();
            return;
        }
        try {
            Object scheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
            entitySchedulerRun.invoke(scheduler, KeenAC.getInstance(),
                (java.util.function.Consumer<Object>) t -> task.run(), fallback);
        } catch (Exception e) {
            if (fallback != null) fallback.run();
        }
    }
    public void runOnPlayer(Player player, Runnable task, Runnable fallback) {
        runOnEntity(player, task, fallback);
    }
    public boolean isSameRegion(Entity a, Entity b) {
        if (!isFolia || a == null || b == null) return true;
        try {
            Method isOwnedByCurrentRegion = Entity.class.getMethod("isOwnedByCurrentRegion");
            if (!(Boolean) isOwnedByCurrentRegion.invoke(b)) return false;
            return true;
        } catch (Exception e) {
            return true;
        }
    }
    public void teleportAsync(Player player, Location location) {
        if (isFolia) {
            try {
                Method teleportAsync = Player.class.getMethod("teleportAsync", Location.class);
                teleportAsync.invoke(player, location);
                return;
            } catch (Exception e) {
                Bukkit.getLogger().warning("KeenAC: Folia teleportAsync failed, falling back to sync: " + e.getMessage());
            }
        }
        player.teleport(location);
    }
    public boolean isOwnedByCurrentRegion(Entity entity) {
        if (!isFolia || entity == null) return !isFolia || Bukkit.isPrimaryThread();
        try {
            Method method = Bukkit.class.getMethod("isOwnedByCurrentRegion", Entity.class);
            Object result = method.invoke(null, entity);
            return result instanceof Boolean ? (Boolean) result : true;
        } catch (Throwable ignored) {
            return true;
        }
    }
    public boolean isOwnedByCurrentRegion(Location location) {
        if (!isFolia || location == null) return !isFolia || Bukkit.isPrimaryThread();
        try {
            Method method = Bukkit.class.getMethod("isOwnedByCurrentRegion", Location.class);
            Object result = method.invoke(null, location);
            return result instanceof Boolean ? (Boolean) result : true;
        } catch (Throwable ignored) {
            return true;
        }
    }
    public Runnable runOnPlayerLater(Player player, long delayTicks, Runnable task) {
        if (player == null) return () -> {};
        if (!isFolia) {
            Bukkit.getScheduler().runTaskLater(KeenAC.getInstance(), task, delayTicks);
            return task;
        }
        runOnEntity(player, task, task);
        return task;
    }
    public boolean isFolia() { return isFolia; }
}
