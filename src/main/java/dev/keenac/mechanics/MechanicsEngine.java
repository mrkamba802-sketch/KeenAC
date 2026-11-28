package dev.keenac.mechanics;

import dev.keenac.KeenAC;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.BoundingBox;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared server-side mechanics sampler. Capture is intentionally restricted to
 * the primary thread; PacketEvents listeners must pass primitive values to the
 * main-thread processing queues rather than calling Bukkit from packet threads.
 */
public final class MechanicsEngine implements Listener {
    private final KeenAC plugin;
    private final Map<UUID, Long> lastTeleportNanos = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastVelocityNanos = new ConcurrentHashMap<>();
    private final Map<UUID, KnockbackPhysics.Impulse> lastVelocityImpulses = new ConcurrentHashMap<>();
    private final Map<UUID, KnockbackPhysics.CombatImpulse> lastCombatImpulses = new ConcurrentHashMap<>();
    private final Map<UUID, KnockbackPhysics.SprintState> clientSprintStates = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDamageNanos = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastItemChangeNanos = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastGlideChangeNanos = new ConcurrentHashMap<>();
    private final Map<UUID, CachedWorldSnapshot> worldSnapshotCache = new ConcurrentHashMap<>();

    public MechanicsEngine(KeenAC plugin) {
        this.plugin = plugin;
    }

    public MechanicsSnapshot capture(Player player) {
        return capture(player, player == null ? null : player.getLocation());
    }

    public MechanicsSnapshot capture(Player player, Location observedLocation) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Mechanics snapshots must be captured on the Bukkit primary thread.");
        }
        if (player == null || observedLocation == null || observedLocation.getWorld() == null) {
            throw new IllegalArgumentException("A player and observed world location are required.");
        }

        long now = System.nanoTime();
        Location location = observedLocation;
        World world = location.getWorld();
        Map<String, MechanicsSnapshot.EffectState> effects = captureEffects(player);
        Map<String, MechanicsSnapshot.AttributeState> attributes = captureAttributes(player);
        Map<String, Integer> enchantments = captureEnchantments(player);
        WorldMechanicsSnapshot worldSnapshot = captureWorld(player, location);

        double[] tpsSamples = Bukkit.getTPS();
        double tps = tpsSamples.length == 0 ? 20.0 : tpsSamples[0];
        if (!Double.isFinite(tps)) tps = 20.0;

        var velocity = player.getVelocity();
        boolean usingItem = player.hasActiveItem();
        String activeItem = usingItem ? material(player.getActiveItem()) : "AIR";

        return new MechanicsSnapshot(
                player.getUniqueId(),
                world.getName(),
                now,
                location.getX(), location.getY(), location.getZ(),
                velocity.getX(), velocity.getY(), velocity.getZ(),
                Math.max(0, player.getPing()),
                tps,
                player.getGameMode().name(),
                player.isOnGround(),
                player.isSprinting(),
                player.isSneaking(),
                player.isBlocking(),
                player.isSwimming(),
                player.isInWater() || player.isSwimming() || worldSnapshot.inWater(),
                player.isFlying(),
                player.isGliding(),
                player.isInsideVehicle(),
                player.isClimbing(),
                player.isRiptiding(),
                usingItem,
                player.getFallDistance(),
                player.getWalkSpeed(),
                material(player.getInventory().getItemInMainHand()),
                material(player.getInventory().getItemInOffHand()),
                activeItem,
                material(player.getInventory().getBoots()),
                material(player.getInventory().getLeggings()),
                material(player.getInventory().getChestplate()),
                material(player.getInventory().getHelmet()),
                player.getOpenInventory().getType().name(),
                player.getInventory().getHeldItemSlot(),
                effects,
                attributes,
                enchantments,
                worldSnapshot,
                ageMillis(now, lastTeleportNanos.get(player.getUniqueId())),
                ageMillis(now, lastVelocityNanos.get(player.getUniqueId())),
                ageMillis(now, lastDamageNanos.get(player.getUniqueId())),
                ageMillis(now, lastItemChangeNanos.get(player.getUniqueId())),
                ageMillis(now, lastGlideChangeNanos.get(player.getUniqueId()))
        );
    }

    /** Latest accepted server velocity event, captured on the primary thread. */
    public KnockbackPhysics.Impulse lastVelocityImpulse(UUID playerId) {
        return playerId == null ? null : lastVelocityImpulses.get(playerId);
    }

    /** Latest PvP knockback event and its server-side combat context. */
    public KnockbackPhysics.CombatImpulse lastCombatImpulse(UUID playerId) {
        return playerId == null ? null : lastCombatImpulses.get(playerId);
    }

    /** Packet-thread-safe sprint action capture; only primitive values are stored. */
    public void recordClientSprintAction(UUID playerId, boolean sprinting) {
        if (playerId == null) return;
        long now = System.nanoTime();
        clientSprintStates.compute(playerId, (ignored, previous) -> {
            if (previous == null) {
                return new KnockbackPhysics.SprintState(
                        sprinting, sprinting ? now : 0L, sprinting ? 0L : now);
            }
            if (previous.sprinting() == sprinting) return previous;
            return new KnockbackPhysics.SprintState(
                    sprinting,
                    sprinting ? now : previous.lastStartedAtNanos(),
                    sprinting ? previous.lastStoppedAtNanos() : now);
        });
    }

    public MovementPhysics.Profile movementProfile(MechanicsSnapshot snapshot) {
        int potionCap = Math.max(1, Math.min(100,
                plugin.getConfig().getInt("mechanics.potion-level-cap", 100)));
        double attributeCap = plugin.getConfig().getDouble(
                "mechanics.movement-attribute-cap", 64.0);
        return MovementPhysics.profile(snapshot, potionCap, attributeCap);
    }

    public double allowedCobwebDistance(MechanicsSnapshot snapshot, double elapsedTicks) {
        double base = plugin.getConfig().getDouble(
                "checks.no-web.max-move-distance", 0.054);
        double buffer = plugin.getConfig().getDouble(
                "checks.no-web.movement-buffer", 0.0015);
        double strict = plugin.getConfig().getDouble(
                "checks.no-web.speed-multiplier", 1.035);
        return MovementPhysics.allowedCobwebDistance(
                snapshot, movementProfile(snapshot), elapsedTicks, base, buffer, strict);
    }

    public double allowedItemUseSpeed(Player player, boolean inWater, boolean includePotionEffects) {
        MechanicsSnapshot snapshot = capture(player);
        int potionCap = Math.max(1, Math.min(100,
                plugin.getConfig().getInt("mechanics.potion-level-cap", 100)));
        double attributeCap = plugin.getConfig().getDouble(
                "mechanics.movement-attribute-cap", 64.0);
        return MovementPhysics.allowedItemUseSpeed(snapshot, inWater, includePotionEffects,
                potionCap, attributeCap);
    }

    private Map<String, MechanicsSnapshot.EffectState> captureEffects(Player player) {
        Map<String, MechanicsSnapshot.EffectState> result = new HashMap<>();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            String key = effect.getType().getKey().getKey().toLowerCase(Locale.ROOT);
            result.put(key, new MechanicsSnapshot.EffectState(
                    effect.getAmplifier() + 1,
                    effect.getDuration(),
                    effect.isAmbient(),
                    effect.hasParticles(),
                    effect.hasIcon()
            ));
        }
        return result;
    }

    /**
     * Reflect over the server's registered Attribute constants so newer
     * Paper attributes are captured without hard-coding a version-specific
     * list in the anticheat.
     */
    private Map<String, MechanicsSnapshot.AttributeState> captureAttributes(Player player) {
        Map<String, MechanicsSnapshot.AttributeState> result = new HashMap<>();
        for (Field field : Attribute.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || !Attribute.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                Attribute attribute = (Attribute) field.get(null);
                if (attribute == null) continue;
                AttributeInstance instance = player.getAttribute(attribute);
                if (instance == null) continue;
                result.put(field.getName().toLowerCase(Locale.ROOT),
                        new MechanicsSnapshot.AttributeState(
                                instance.getBaseValue(), instance.getValue()));
            } catch (IllegalAccessException | RuntimeException ignored) {
                // Some server forks expose synthetic Attribute fields; skip only those.
            }
        }

        AttributeInstance movement = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (movement != null) {
            result.putIfAbsent("movement_speed",
                    new MechanicsSnapshot.AttributeState(
                            movement.getBaseValue(), movement.getValue()));
        }
        return result;
    }

    private Map<String, Integer> captureEnchantments(Player player) {
        Map<String, Integer> result = new HashMap<>();
        var inventory = player.getInventory();
        captureItemEnchantments(result, "main_hand", inventory.getItemInMainHand());
        captureItemEnchantments(result, "off_hand", inventory.getItemInOffHand());
        captureItemEnchantments(result, "boots", inventory.getBoots());
        captureItemEnchantments(result, "leggings", inventory.getLeggings());
        captureItemEnchantments(result, "chestplate", inventory.getChestplate());
        captureItemEnchantments(result, "helmet", inventory.getHelmet());
        if (player.hasActiveItem()) {
            captureItemEnchantments(result, "active_item", player.getActiveItem());
        }
        return result;
    }

    private void captureItemEnchantments(Map<String, Integer> output, String slot, ItemStack item) {
        if (item == null || item.getType().isAir()) return;
        for (Map.Entry<Enchantment, Integer> entry : item.getEnchantments().entrySet()) {
            String key = entry.getKey().getKey().getKey().toLowerCase(Locale.ROOT);
            output.put(slot + ":" + key, Math.max(0, entry.getValue()));
        }
    }

    private WorldMechanicsSnapshot captureWorld(Player player, Location location) {
        World world = location.getWorld();
        int centerX = floor(location.getX());
        int centerY = floor(location.getY());
        int centerZ = floor(location.getZ());
        long nowNanos = System.nanoTime();
        UUID playerId = player.getUniqueId();
        CachedWorldSnapshot cached = worldSnapshotCache.get(playerId);
        if (cached != null && cached.worldName().equals(world.getName())
                && cached.centerX() == centerX && cached.centerY() == centerY
                && cached.centerZ() == centerZ
                && nowNanos - cached.capturedAtNanos() < 100_000_000L) {
            return cached.snapshot();
        }

        BoundingBox liveBox = player.getBoundingBox();
        double height = Math.max(1.0, Math.min(3.0,
                liveBox.getMaxY() - liveBox.getMinY()));

        int playerMinX = floor(location.getX() - liveBox.getWidthX() * 0.5 + 0.001);
        int playerMaxX = floor(location.getX() + liveBox.getWidthX() * 0.5 - 0.001);
        int playerMinY = floor(location.getY() + 0.001);
        int playerMaxY = floor(location.getY() + height - 0.001);
        int playerMinZ = floor(location.getZ() - liveBox.getWidthZ() * 0.5 + 0.001);
        int playerMaxZ = floor(location.getZ() + liveBox.getWidthZ() * 0.5 - 0.001);

        Set<String> intersecting = new HashSet<>();
        boolean complete = true;
        for (int x = playerMinX; x <= playerMaxX; x++) {
            for (int y = playerMinY; y <= playerMaxY; y++) {
                for (int z = playerMinZ; z <= playerMaxZ; z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        complete = false;
                        continue;
                    }
                    String type = world.getBlockAt(x, y, z).getType().name();
                    if (!"AIR".equals(type) && !"CAVE_AIR".equals(type)
                            && !"VOID_AIR".equals(type)) {
                        intersecting.add(type);
                    }
                }
            }
        }

        String below = readBlock(world, floor(location.getX()),
                floor(location.getY() - 0.02), floor(location.getZ()));
        String feet = readBlock(world, floor(location.getX()),
                floor(location.getY() + 0.05), floor(location.getZ()));
        String head = readBlock(world, floor(location.getX()),
                floor(location.getY() + height - 0.05), floor(location.getZ()));

        // Capture nearby precise collision shapes for a short movement horizon.
        // The three-block-wide horizontal area covers ordinary one-tick motion
        // and the step-up attempt without loading chunks on behalf of the check.
        java.util.List<CollisionBox> collisions = new java.util.ArrayList<>();
        for (int x = centerX - 1; x <= centerX + 1; x++) {
            for (int y = centerY - 1; y <= centerY + 2; y++) {
                for (int z = centerZ - 1; z <= centerZ + 1; z++) {
                    if (y < world.getMinHeight() || y >= world.getMaxHeight()
                            || !world.isChunkLoaded(x >> 4, z >> 4)) {
                        complete = false;
                        continue;
                    }
                    Block block = world.getBlockAt(x, y, z);
                    for (BoundingBox shape : block.getCollisionShape().getBoundingBoxes()) {
                        collisions.add(new CollisionBox(
                                x + shape.getMinX(), y + shape.getMinY(), z + shape.getMinZ(),
                                x + shape.getMaxX(), y + shape.getMaxY(), z + shape.getMaxZ()
                        ));
                        if (collisions.size() >= 512) break;
                    }
                }
            }
        }

        boolean water = player.isInWater() || player.isSwimming()
                || contains(intersecting, "WATER", "BUBBLE_COLUMN", "KELP", "KELP_PLANT");
        boolean powderSnow = contains(intersecting, "POWDER_SNOW")
                || "POWDER_SNOW".equals(below);
        boolean climbing = player.isClimbing()
                || contains(intersecting, "LADDER", "VINE", "TWISTING_VINES",
                "WEEPING_VINES", "CAVE_VINES", "SCAFFOLDING");
        boolean cobweb = contains(intersecting, "COBWEB", "WEB");

        double slipperiness = 0.6;
        int supportX = floor(location.getX());
        int supportY = floor(location.getY() - 0.02);
        int supportZ = floor(location.getZ());
        if (supportY >= world.getMinHeight() && supportY < world.getMaxHeight()
                && world.isChunkLoaded(supportX >> 4, supportZ >> 4)) {
            try {
                slipperiness = world.getBlockAt(supportX, supportY, supportZ)
                        .getType().getSlipperiness();
            } catch (RuntimeException ignored) {
                slipperiness = 0.6;
            }
        }

        WorldMechanicsSnapshot snapshot = new WorldMechanicsSnapshot(world.getName(), below, feet, head,
                intersecting, water, powderSnow, climbing, cobweb, complete,
                collisions, slipperiness);
        worldSnapshotCache.put(playerId, new CachedWorldSnapshot(
                world.getName(), centerX, centerY, centerZ, nowNanos, snapshot));
        return snapshot;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        worldSnapshotCache.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        worldSnapshotCache.clear();
    }

    private record CachedWorldSnapshot(
            String worldName,
            int centerX,
            int centerY,
            int centerZ,
            long capturedAtNanos,
            WorldMechanicsSnapshot snapshot
    ) { }

    private String readBlock(World world, int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) return "OUT_OF_WORLD";
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return "UNLOADED";
        return world.getBlockAt(x, y, z).getType().name();
    }

    private boolean contains(Set<String> values, String... keys) {
        for (String key : keys) {
            if (values.contains(key)) return true;
        }
        return false;
    }

    private String material(ItemStack item) {
        return item == null ? "AIR" : item.getType().name();
    }

    private int floor(double value) {
        return (int) Math.floor(value);
    }

    private long ageMillis(long now, Long eventNanos) {
        return eventNanos == null ? -1L : Math.max(0L, (now - eventNanos) / 1_000_000L);
    }

    private long ageMillis(long now, long eventNanos) {
        return eventNanos <= 0L ? -1L : Math.max(0L, (now - eventNanos) / 1_000_000L);
    }

    private double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private void mark(Map<UUID, Long> target, UUID id) {
        if (id != null) target.put(id, System.nanoTime());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastVelocityImpulses.remove(id);
        lastCombatImpulses.remove(id);
        mark(lastTeleportNanos, id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastVelocityImpulses.remove(id);
        lastCombatImpulses.remove(id);
        mark(lastTeleportNanos, id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastVelocityImpulses.remove(id);
        lastCombatImpulses.remove(id);
        mark(lastTeleportNanos, id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPushedByAttack(EntityPushedByEntityAttackEvent event) {
        if (!(event.getEntity() instanceof Player target)) return;
        if (!(event.getPushedBy() instanceof Player attacker)) return;

        long now = System.nanoTime();
        UUID attackerId = attacker.getUniqueId();
        KnockbackPhysics.SprintState sprintState = clientSprintStates.get(attackerId);
        long sprintStartAge = sprintState == null ? -1L
                : ageMillis(now, sprintState.lastStartedAtNanos());
        long stopToStart = sprintState == null
                || sprintState.lastStoppedAtNanos() <= 0L
                || sprintState.lastStartedAtNanos() < sprintState.lastStoppedAtNanos()
                ? -1L
                : (sprintState.lastStartedAtNanos() - sprintState.lastStoppedAtNanos())
                        / 1_000_000L;
        boolean attackerSprinting = attacker.isSprinting();
        long sprintResetWindow = Math.max(50L, Math.min(500L,
                plugin.getConfig().getLong(
                        "mechanics.prediction.knockback.sprint-reset-window-ms", 300L)));
        boolean sprintReset = KnockbackPhysics.isSprintResetWithinWindow(
                attackerSprinting, sprintStartAge, stopToStart, sprintResetWindow);

        var force = event.getKnockback();
        var before = target.getVelocity();
        var targetResistance = target.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        var attackerKnockback = attacker.getAttribute(Attribute.ATTACK_KNOCKBACK);
        int knockbackEnchantment = captureEnchantments(attacker)
                .getOrDefault("main_hand:knockback", 0);
        lastCombatImpulses.put(target.getUniqueId(), new KnockbackPhysics.CombatImpulse(
                new KnockbackPhysics.MotionVector(force.getX(), force.getY(), force.getZ()),
                new KnockbackPhysics.MotionVector(before.getX(), before.getY(), before.getZ()),
                KnockbackPhysics.MotionVector.zero(),
                false, 0L, now, event.getCause().name(), attackerId,
                attackerSprinting, sprintReset, sprintStartAge, stopToStart,
                targetResistance == null ? 0.0 : finiteOrZero(targetResistance.getValue()),
                attackerKnockback == null ? 0.0 : finiteOrZero(attackerKnockback.getValue()),
                knockbackEnchantment, Math.max(0, attacker.getPing())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        long now = System.nanoTime();
        var velocity = event.getVelocity();
        lastVelocityNanos.put(id, now);
        lastVelocityImpulses.put(id, new KnockbackPhysics.Impulse(
                velocity.getX(), velocity.getY(), velocity.getZ(), now));

        KnockbackPhysics.CombatImpulse combatImpulse = lastCombatImpulses.get(id);
        if (combatImpulse != null && now >= combatImpulse.timestampNanos()
                && now - combatImpulse.timestampNanos() <= 250_000_000L) {
            lastCombatImpulses.put(id, combatImpulse.withAppliedVelocity(
                    new KnockbackPhysics.MotionVector(
                            velocity.getX(), velocity.getY(), velocity.getZ()), now));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            mark(lastDamageNanos, player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGlideToggle(EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player player) {
            mark(lastGlideChangeNanos, player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldItem(PlayerItemHeldEvent event) {
        mark(lastItemChangeNanos, event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHandSwap(PlayerSwapHandItemsEvent event) {
        mark(lastItemChangeNanos, event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        mark(lastItemChangeNanos, event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        mark(lastItemChangeNanos, event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastTeleportNanos.remove(id);
        lastVelocityNanos.remove(id);
        lastVelocityImpulses.remove(id);
        lastCombatImpulses.remove(id);
        clientSprintStates.remove(id);
        lastDamageNanos.remove(id);
        lastItemChangeNanos.remove(id);
        lastGlideChangeNanos.remove(id);
        worldSnapshotCache.remove(id);
    }

    public void clearAll() {
        lastTeleportNanos.clear();
        lastVelocityNanos.clear();
        lastVelocityImpulses.clear();
        lastCombatImpulses.clear();
        clientSprintStates.clear();
        lastDamageNanos.clear();
        lastItemChangeNanos.clear();
        lastGlideChangeNanos.clear();
        worldSnapshotCache.clear();
    }
}
