package dev.keenac.mechanics;

/** Immutable world-space axis-aligned collision box copied from a Paper VoxelShape. */
public record CollisionBox(
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ
) {
    public CollisionBox {
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                || maxX < minX || maxY < minY || maxZ < minZ) {
            throw new IllegalArgumentException("Invalid collision box");
        }
    }
}
