package dev.keenac.engine.core.physics;
import dev.keenac.engine.core.player.TrackedPlayer;
public final class BoundingBox {
    public final double minX, minY, minZ;
    public final double maxX, maxY, maxZ;
    public BoundingBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }
    public BoundingBox expand(double x, double y, double z) {
        return new BoundingBox(minX - x, minY - y, minZ - z, maxX + x, maxY + y, maxZ + z);
    }
    public BoundingBox expand(double margin) {
        return expand(margin, margin, margin);
    }
    public BoundingBox offset(double x, double y, double z) {
        return new BoundingBox(minX + x, minY + y, minZ + z, maxX + x, maxY + y, maxZ + z);
    }
    public boolean intersects(BoundingBox other) {
        return maxX > other.minX && minX < other.maxX
                && maxY > other.minY && minY < other.maxY
                && maxZ > other.minZ && minZ < other.maxZ;
    }
    public boolean intersectsRay(double originX, double originY, double originZ,
                                 double dirX, double dirY, double dirZ,
                                 double maxDistance) {
        double rayLength = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (rayLength == 0.0) return false;
        SlabRange range = new SlabRange(0.0, maxDistance);
        return range.clip(originX, dirX / rayLength, minX, maxX)
                && range.clip(originY, dirY / rayLength, minY, maxY)
                && range.clip(originZ, dirZ / rayLength, minZ, maxZ)
                && range.tMax >= 0.0;
    }
    private static final class SlabRange {
        private double tMin;
        private double tMax;
        SlabRange(double tMin, double tMax) {
            this.tMin = tMin;
            this.tMax = tMax;
        }
        boolean clip(double origin, double dirUnit, double slabMin, double slabMax) {
            if (Math.abs(dirUnit) < 1e-9) {
                return origin >= slabMin && origin <= slabMax;
            }
            double inv = 1.0 / dirUnit;
            double t1 = (slabMin - origin) * inv;
            double t2 = (slabMax - origin) * inv;
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            if (t1 > tMin) tMin = t1;
            if (t2 < tMax) tMax = t2;
            return tMin <= tMax;
        }
    }
    public double getCenterX() { return (minX + maxX) * 0.5; }
    public double getCenterY() { return (minY + maxY) * 0.5; }
    public double getCenterZ() { return (minZ + maxZ) * 0.5; }
    public double getWidth() { return maxX - minX; }
    public double getHeight() { return maxY - minY; }
    public double getDepth() { return maxZ - minZ; }
    public boolean contains(double x, double y, double z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }
    public boolean containsXZ(double x, double z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }
    public double distanceTo(double x, double y, double z) {
        double dx = Math.max(0, Math.max(minX - x, x - maxX));
        double dy = Math.max(0, Math.max(minY - y, y - maxY));
        double dz = Math.max(0, Math.max(minZ - z, z - maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    public double distanceToEdge(double x, double y, double z) {
        return distanceTo(x, y, z);
    }
    public BoundingBox addCoords(double x1, double y1, double z1, double x2, double y2, double z2) {
        double nminX = Math.min(minX, Math.min(x1, x2));
        double nminY = Math.min(minY, Math.min(y1, y2));
        double nminZ = Math.min(minZ, Math.min(z1, z2));
        double nmaxX = Math.max(maxX, Math.max(x1, x2));
        double nmaxY = Math.max(maxY, Math.max(y1, y2));
        double nmaxZ = Math.max(maxZ, Math.max(z1, z2));
        return new BoundingBox(nminX, nminY, nminZ, nmaxX, nmaxY, nmaxZ);
    }
    public static BoundingBox fromPlayer(double x, double y, double z, TrackedPlayer.Pose pose, int protocol) {
        double halfWidth = PhysicsConstants.PLAYER_WIDTH * 0.5;
        double height = VersionPhysics.getPlayerHeight(pose, protocol);
        return new BoundingBox(x - halfWidth, y, z - halfWidth, x + halfWidth, y + height, z + halfWidth);
    }
    public static BoundingBox fromPlayer(double x, double y, double z, boolean sneaking, int protocol) {
        double halfWidth = PhysicsConstants.PLAYER_WIDTH * 0.5;
        double height = VersionPhysics.getPlayerHeight(sneaking, protocol);
        return new BoundingBox(x - halfWidth, y, z - halfWidth, x + halfWidth, y + height, z + halfWidth);
    }
    public static BoundingBox fromPlayerLook(double x, double y, double z, float yaw, float pitch,
                                              boolean sneaking, int protocol) {
        return fromPlayer(x, y, z, sneaking, protocol);
    }
}
