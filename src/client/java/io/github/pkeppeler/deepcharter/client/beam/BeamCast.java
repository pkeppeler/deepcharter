package io.github.pkeppeler.deepcharter.client.beam;

/**
 * How long a fake light beam is (#248): a walk through the block grid from the lamp along its direction that stops where the beam
 * enters the first solid block, or at the lamp's reach. It allocates nothing, so a beam can be cast every frame; one cast is one
 * walk of at most {@code reach} blocks of the grid (a few dozen cell lookups), and a pod casts one a frame.
 */
public final class BeamCast {
    private BeamCast() {}

    /** Whether the block cell at x, y, z stops a beam. */
    public interface Solid {
        boolean at(int x, int y, int z);
    }

    /**
     * The distance from (ox, oy, oz) along the unit vector (dx, dy, dz) to the first solid cell, at most {@code max}. A lamp that
     * starts inside a solid cell has no beam.
     */
    public static double length(
            Solid solid, double ox, double oy, double oz, double dx, double dy, double dz, double max) {
        int x = (int) Math.floor(ox);
        int y = (int) Math.floor(oy);
        int z = (int) Math.floor(oz);
        if (solid.at(x, y, z)) {
            return 0;
        }
        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;
        double deltaX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double deltaY = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double deltaZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
        double nextX = dx == 0 ? Double.POSITIVE_INFINITY : (dx > 0 ? x + 1 - ox : ox - x) * deltaX;
        double nextY = dy == 0 ? Double.POSITIVE_INFINITY : (dy > 0 ? y + 1 - oy : oy - y) * deltaY;
        double nextZ = dz == 0 ? Double.POSITIVE_INFINITY : (dz > 0 ? z + 1 - oz : oz - z) * deltaZ;
        while (true) {
            double distance;
            if (nextX <= nextY && nextX <= nextZ) {
                distance = nextX;
                x += stepX;
                nextX += deltaX;
            } else if (nextY <= nextZ) {
                distance = nextY;
                y += stepY;
                nextY += deltaY;
            } else {
                distance = nextZ;
                z += stepZ;
                nextZ += deltaZ;
            }
            if (distance >= max) {
                return max;
            }
            if (solid.at(x, y, z)) {
                return distance;
            }
        }
    }

    /**
     * How far a lamp's beam reaches in open air: nothing when the lamp is dark or the pod has no lights part (light level 0), else
     * {@code styleLength} scaled by the lamp's light level out of 15, so a better lights part throws a longer beam.
     */
    public static double reach(boolean lit, int lightLevel, double styleLength) {
        if (!lit || lightLevel <= 0) {
            return 0;
        }
        return styleLength * Math.min(lightLevel, 15) / 15.0;
    }
}
