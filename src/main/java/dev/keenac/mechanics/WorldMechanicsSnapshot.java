package dev.keenac.mechanics;

import java.util.Locale;
import java.util.Set;
import java.util.List;

/**
 * Immutable view of blocks intersecting the player's predicted bounding box,
 * plus the support block. It deliberately stores material keys rather than
 * Bukkit Block instances so checks can safely retain the snapshot.
 */
public record WorldMechanicsSnapshot(
        String worldName,
        String blockBelow,
        String blockAtFeet,
        String blockAtHead,
        Set<String> intersectingMaterials,
        boolean inWater,
        boolean inPowderSnow,
        boolean climbing,
        boolean inCobweb,
        boolean chunkDataComplete,
        List<CollisionBox> collisionBoxes,
        double supportSlipperiness
) {
    public WorldMechanicsSnapshot {
        worldName = worldName == null ? "" : worldName;
        blockBelow = normalize(blockBelow);
        blockAtFeet = normalize(blockAtFeet);
        blockAtHead = normalize(blockAtHead);
        intersectingMaterials = intersectingMaterials == null
                ? Set.of() : Set.copyOf(intersectingMaterials);
        collisionBoxes = collisionBoxes == null ? List.of() : List.copyOf(collisionBoxes);
        if (!Double.isFinite(supportSlipperiness)) supportSlipperiness = 0.6;
    }

    public static WorldMechanicsSnapshot empty() {
        return new WorldMechanicsSnapshot("", "UNKNOWN", "UNKNOWN", "UNKNOWN",
                Set.of(), false, false, false, false, false, List.of(), 0.6);
    }

    public boolean hasMaterial(String material) {
        String key = normalize(material);
        return blockBelow.equals(key) || blockAtFeet.equals(key) || blockAtHead.equals(key)
                || intersectingMaterials.contains(key);
    }

    public boolean hasAlternativeMovementSurface() {
        if (inWater || inPowderSnow || climbing) return true;
        return hasAny("BUBBLE_COLUMN", "HONEY_BLOCK", "SLIME_BLOCK",
                "ICE", "PACKED_ICE", "BLUE_ICE", "FROSTED_ICE",
                "SOUL_SAND", "SOUL_SOIL", "MUD",
                "LADDER", "VINE", "TWISTING_VINES", "WEEPING_VINES", "CAVE_VINES",
                "SCAFFOLDING", "SWEET_BERRY_BUSH");
    }

    public boolean hasAny(String... materials) {
        for (String material : materials) {
            if (hasMaterial(material)) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "UNKNOWN" : value.toUpperCase(Locale.ROOT);
    }
}
