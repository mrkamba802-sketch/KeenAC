package dev.keenac.mechanics;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Server-authoritative immutable snapshot of a player's mechanics at one
 * observed position. Potion amplifier values are stored as Minecraft levels
 * (amplifier + 1), including custom levels above the normal brewing range.
 */
public record MechanicsSnapshot(
        UUID playerId,
        String worldName,
        long capturedAtNanos,
        double x,
        double y,
        double z,
        double velocityX,
        double velocityY,
        double velocityZ,
        int pingMs,
        double tps,
        String gameMode,
        boolean onGround,
        boolean sprinting,
        boolean sneaking,
        boolean blocking,
        boolean swimming,
        boolean inWater,
        boolean flying,
        boolean gliding,
        boolean inVehicle,
        boolean climbing,
        boolean riptiding,
        boolean usingItem,
        float fallDistance,
        double walkSpeed,
        String mainHandType,
        String offHandType,
        String activeItemType,
        String bootsType,
        String leggingsType,
        String chestplateType,
        String helmetType,
        String inventoryType,
        int selectedHotbarSlot,
        Map<String, EffectState> potionEffects,
        Map<String, AttributeState> attributes,
        Map<String, Integer> enchantmentLevels,
        WorldMechanicsSnapshot world,
        long teleportAgeMs,
        long velocityAgeMs,
        long damageAgeMs,
        long itemChangeAgeMs,
        long glideChangeAgeMs
) {
    public MechanicsSnapshot {
        worldName = worldName == null ? "" : worldName;
        gameMode = gameMode == null ? "UNKNOWN" : gameMode;
        mainHandType = safe(mainHandType);
        offHandType = safe(offHandType);
        activeItemType = safe(activeItemType);
        bootsType = safe(bootsType);
        leggingsType = safe(leggingsType);
        chestplateType = safe(chestplateType);
        helmetType = safe(helmetType);
        inventoryType = safe(inventoryType);
        potionEffects = potionEffects == null ? Map.of() : Map.copyOf(potionEffects);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        enchantmentLevels = enchantmentLevels == null ? Map.of() : Map.copyOf(enchantmentLevels);
        world = world == null ? WorldMechanicsSnapshot.empty() : world;
    }

    public int potionLevel(String effectKey) {
        if (effectKey == null) return 0;
        EffectState effect = potionEffects.get(normalize(effectKey));
        return effect == null ? 0 : effect.level();
    }

    public boolean hasPotionEffect(String effectKey) {
        return potionLevel(effectKey) > 0;
    }

    public int enchantmentLevel(String slot, String enchantmentKey) {
        if (slot == null || enchantmentKey == null) return 0;
        return enchantmentLevels.getOrDefault(
                normalize(slot) + ":" + normalize(enchantmentKey), 0);
    }

    public int enchantmentLevel(String enchantmentKey) {
        if (enchantmentKey == null) return 0;
        String suffix = ":" + normalize(enchantmentKey);
        return enchantmentLevels.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith(suffix))
                .mapToInt(Map.Entry::getValue)
                .max().orElse(0);
    }

    public AttributeState attribute(String attributeKey) {
        if (attributeKey == null) return null;
        return attributes.get(normalize(attributeKey));
    }

    public double attributeRatio(String attributeKey) {
        AttributeState state = attribute(attributeKey);
        if (state == null || !Double.isFinite(state.baseValue())
                || state.baseValue() <= 0.0 || !Double.isFinite(state.value())) {
            return 1.0;
        }
        return state.value() / state.baseValue();
    }

    private static String normalize(String key) {
        String value = key.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("minecraft:")) value = value.substring("minecraft:".length());
        return value;
    }

    private static String safe(String value) {
        return value == null ? "UNKNOWN" : value;
    }

    public record EffectState(
            int level,
            int durationTicks,
            boolean ambient,
            boolean particles,
            boolean icon
    ) {
        public EffectState {
            level = Math.max(1, level);
            durationTicks = Math.max(0, durationTicks);
        }
    }

    public record AttributeState(double baseValue, double value) { }
}
