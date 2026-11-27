package dev.keenac.engine.core.player;
import org.bukkit.entity.Player;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
public record AttributeSnapshot(
        double movementSpeed,
        double gravity,
        double jumpStrength,
        double stepHeight,
        double sneakingSpeed,
        double movementEfficiency,
        double waterMovementEfficiency,
        double blockBreakSpeed,
        double miningEfficiency,
        double blockInteractionRange,
        double entityInteractionRange,
        double safeFallDistance,
        double fallDamageMultiplier,
        double scale,
        double explosionKnockbackResistance,
        double airDragModifier,
        double frictionModifier
) {
    public static AttributeSnapshot defaults() {
        return new AttributeSnapshot(
                0.1, 0.08, 0.42, 0.6, 0.3, 0.0, 0.0,
                1.0, 0.0, 4.5, 3.0, 3.0, 1.0, 1.0, 0.0, 1.0, 1.0);
    }
    public static AttributeSnapshot capture(Player player) {
        AttributeSnapshot d = defaults();
        return new AttributeSnapshot(
                read(player, "MOVEMENT_SPEED", d.movementSpeed),
                read(player, "GRAVITY", d.gravity),
                read(player, "JUMP_STRENGTH", d.jumpStrength),
                read(player, "STEP_HEIGHT", d.stepHeight),
                read(player, "SNEAKING_SPEED", d.sneakingSpeed),
                read(player, "MOVEMENT_EFFICIENCY", d.movementEfficiency),
                read(player, "WATER_MOVEMENT_EFFICIENCY", d.waterMovementEfficiency),
                read(player, "BLOCK_BREAK_SPEED", d.blockBreakSpeed),
                read(player, "MINING_EFFICIENCY", d.miningEfficiency),
                read(player, "BLOCK_INTERACTION_RANGE", d.blockInteractionRange),
                read(player, "ENTITY_INTERACTION_RANGE", d.entityInteractionRange),
                read(player, "SAFE_FALL_DISTANCE", d.safeFallDistance),
                read(player, "FALL_DAMAGE_MULTIPLIER", d.fallDamageMultiplier),
                read(player, "SCALE", d.scale),
                read(player, "EXPLOSION_KNOCKBACK_RESISTANCE", d.explosionKnockbackResistance),
                read(player, "AIR_DRAG_MODIFIER", d.airDragModifier),
                read(player, "FRICTION_MODIFIER", d.frictionModifier)
        );
    }
    private static double read(Player player, String name, double fallback) {
        try {
            Class<?> type = Class.forName("org.bukkit.attribute.Attribute");
            Object key = null;
            try {
                Field field = type.getField(name);
                key = field.get(null);
            } catch (ReflectiveOperationException ignored) {
                if (type.isEnum()) {
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    Object value = Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), name);
                    key = value;
                }
            }
            if (key == null) return fallback;
            Method getter = player.getClass().getMethod("getAttribute", type);
            Object instance = getter.invoke(player, key);
            if (instance == null) return fallback;
            Method getValue = instance.getClass().getMethod("getValue");
            Object value = getValue.invoke(instance);
            if (!(value instanceof Number number)) return fallback;
            double result = number.doubleValue();
            return Double.isFinite(result) && result >= 0.0 ? result : fallback;
        } catch (ReflectiveOperationException | LinkageError exception) {
            return fallback;
        }
    }
}
