package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.Zones;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds where the {@code hollows-concepts} scenario (#1147) stands its camera in each zone of layer 3: a generated cavern of the
 * campaign world, never a staged room. A spot is a free cell over a floor whose view, in one of eight headings, runs furthest through the
 * cavern (a fan of rays) and takes in the most glowcaps; its focus is the brightest floor cell the camera sees a few blocks ahead,
 * where the mock-ups stand in the glowcaps' own light. Runs on the server thread over chunks the scenario has forced.
 */
final class HollowsSpots {
    private static final Logger LOGGER = LoggerFactory.getLogger(HollowsSpots.class);
    /** Candidates stand within this many blocks of the region's middle, every {@link #GRID} blocks. */
    static final int SEARCH_RADIUS = 24;
    /** The rays of the fan reach this far, so the forced region must reach {@code SEARCH_RADIUS + RAY_REACH} round the middle. */
    static final int RAY_REACH = 40;

    private static final int GRID = 3;
    /** Candidates stay this far inside their zone, so the camera's biome blend never reaches the next one. */
    private static final int ZONE_INSET = 8;
    /** Free cells over the floor that a candidate needs: its feet stand {@link #FEET_RISE} over it, the eye 1.62 over the feet. */
    private static final int HEADROOM = 4;

    static final int FEET_RISE = 2;
    private static final int[] FAN_YAWS = {-30, -15, 0, 15, 30};
    private static final int[] FAN_PITCHES = {-15, 0, 15};
    /** A glowcap within the view's cone, between these distances, adds this much to the score: a lit view reads, a black one does not. */
    private static final int GLOW_NEAR = 4;

    private static final int GLOW_FAR = 32;
    private static final double GLOW_CONE = 35;
    private static final int GLOW_WEIGHT = 4;
    private static final int GLOW_CAP = 30;
    /** The focus is a floor cell this far ahead (in blocks), within this many degrees of the heading and this far up or down from the camera's floor. */
    private static final int FOCUS_NEAR = 7;

    private static final int FOCUS_FAR = 16;
    private static final double FOCUS_CONE = 25;
    private static final int FOCUS_RISE = 6;
    /** The tall focus, where a giant fungus grows: further off, with the most free height over it. */
    private static final int TALL_NEAR = 10;

    private static final int TALL_FAR = 20;
    private static final int TALL_CLEAR_CAP = 26;
    private static final int TALL_CLEAR_LEAST = 12;
    /** Only the best candidates are tried for a focus, in order of score. */
    private static final int TRIED = 200;

    private HollowsSpots() {}

    /**
     * A camera: feet at {@code feet}, facing {@code yaw}; {@code floorY} is the floor under it, {@code focus} the floor cell the mock-ups
     * stand on (the free cell over the floor), {@code tall} the one a giant fungus grows from and {@code clear} the free height over it.
     */
    record Spot(
            String zone, BlockPos feet, float yaw, int floorY, BlockPos focus, BlockPos tall, int clear, int score) {
        Vec3 eye() {
            return new Vec3(feet.getX() + 0.5, feet.getY() + EYE, feet.getZ() + 0.5);
        }

        /** The yaw and pitch, in that order, at which the eye looks at {@code target}. */
        float[] look(Vec3 target) {
            Vec3 d = target.subtract(eye());
            return new float[] {
                (float) Math.toDegrees(Math.atan2(-d.x, d.z)),
                (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)))
            };
        }

        /** The unit vector of the heading, flat. */
        Vec3 forward() {
            return heading(yaw);
        }

        /** The unit vector a quarter turn to the right of the heading, flat. */
        Vec3 right() {
            Vec3 f = forward();
            return new Vec3(-f.z, 0, f.x);
        }

        /** The cell {@code ahead} blocks along the heading and {@code across} to its right of {@code from}, {@code up} over it. */
        BlockPos at(BlockPos from, double ahead, double across, int up) {
            Vec3 f = forward();
            Vec3 r = right();
            return BlockPos.containing(
                    from.getX() + 0.5 + f.x * ahead + r.x * across,
                    from.getY() + up,
                    from.getZ() + 0.5 + f.z * ahead + r.z * across);
        }
    }

    private static final double EYE = 1.62;

    private record Candidate(BlockPos feet, int floorY, float yaw, int score) {}

    /**
     * The spot of zone {@code zone} (0 is the Garden) of layer 3's {@code band} in {@code level}, round ({@code x}, {@code z}), or
     * empty, with what it found in the log, if no candidate there has a focus.
     */
    static Optional<Spot> find(ServerLevel level, LayerChain.Band band, int zone, int x, int z) {
        Zones.Span span = Zones.span(band.minY(), band.height(), zone);
        String name = Zones.of(3, band.minY(), band.height(), span.low())
                .orElseThrow()
                .id()
                .getPath();
        List<BlockPos> glowcaps = glowcaps(level, x, z, span);
        List<Candidate> candidates = new ArrayList<>();
        for (int cx = x - SEARCH_RADIUS; cx <= x + SEARCH_RADIUS; cx += GRID) {
            for (int cz = z - SEARCH_RADIUS; cz <= z + SEARCH_RADIUS; cz += GRID) {
                for (int y = span.high() - ZONE_INSET; y >= span.low() + ZONE_INSET; y--) {
                    if (!isFloor(level, new BlockPos(cx, y, cz), HEADROOM)) {
                        continue;
                    }
                    BlockPos feet = new BlockPos(cx, y + FEET_RISE, cz);
                    for (int heading = 0; heading < 8; heading++) {
                        float yaw = heading * 45f;
                        int score = fan(level, feet, yaw) + glowScore(glowcaps, feet, yaw);
                        candidates.add(new Candidate(feet, y, yaw, score));
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::score).reversed());
        for (Candidate c : candidates.subList(0, Math.min(TRIED, candidates.size()))) {
            Spot probe = new Spot(name, c.feet(), c.yaw(), c.floorY(), c.feet(), c.feet(), 0, c.score());
            Optional<BlockPos> focus = focus(level, probe);
            if (focus.isEmpty()) {
                continue;
            }
            BlockPos tall = focus.get();
            int clear = clearHeight(level, tall);
            Optional<BlockPos> roomier = tallFocus(level, probe);
            if (roomier.isPresent()) {
                tall = roomier.get();
                clear = clearHeight(level, tall);
            }
            return Optional.of(new Spot(name, c.feet(), c.yaw(), c.floorY(), focus.get(), tall, clear, c.score()));
        }
        LOGGER.info(
                "[hollows-concepts] no camera spot in {} (Y {} to {}) round {}, {}: {} candidates, {} glowcaps",
                name,
                span.low(),
                span.high(),
                x,
                z,
                candidates.size(),
                glowcaps.size());
        return Optional.empty();
    }

    /** A cell that the camera can see through: air, or a glowcap (a small plant). */
    static boolean open(BlockState state) {
        return state.isAir() || state.is(LayerBlocks.GLOWCAP);
    }

    /** A solid cell with {@code headroom} free cells over it. */
    private static boolean isFloor(ServerLevel level, BlockPos pos, int headroom) {
        if (open(level.getBlockState(pos))) {
            return false;
        }
        for (int up = 1; up <= headroom; up++) {
            if (!open(level.getBlockState(pos.above(up)))) {
                return false;
            }
        }
        return true;
    }

    private static Vec3 heading(float yaw) {
        double r = Math.toRadians(yaw);
        return new Vec3(-Math.sin(r), 0, Math.cos(r));
    }

    private static Vec3 direction(float yaw, float pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    /** How far the fan of rays from the eye runs through open cells, summed, each ray up to {@link #RAY_REACH}. */
    private static int fan(ServerLevel level, BlockPos feet, float yaw) {
        Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + EYE, feet.getZ() + 0.5);
        int sum = 0;
        for (int dyaw : FAN_YAWS) {
            for (int pitch : FAN_PITCHES) {
                Vec3 d = direction(yaw + dyaw, pitch);
                int t = 1;
                while (t <= RAY_REACH && open(level.getBlockState(BlockPos.containing(eye.add(d.scale(t)))))) {
                    t++;
                }
                sum += t - 1;
            }
        }
        return sum;
    }

    private static int glowScore(List<BlockPos> glowcaps, BlockPos feet, float yaw) {
        int seen = 0;
        for (BlockPos g : glowcaps) {
            if (inCone(feet, yaw, g, GLOW_NEAR, GLOW_FAR, GLOW_CONE)
                    && Math.abs(g.getY() - feet.getY()) < GLOW_FAR / 2) {
                seen++;
            }
        }
        return Math.min(seen, GLOW_CAP) * GLOW_WEIGHT;
    }

    private static boolean inCone(BlockPos from, float yaw, BlockPos to, double near, double far, double cone) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double distance = Math.hypot(dx, dz);
        if (distance < near || distance > far) {
            return false;
        }
        double bearing = Math.toDegrees(Math.atan2(-dx, dz));
        return Math.abs(Mth.wrapDegrees(bearing - yaw)) <= cone;
    }

    private static List<BlockPos> glowcaps(ServerLevel level, int x, int z, Zones.Span span) {
        List<BlockPos> found = new ArrayList<>();
        int reach = SEARCH_RADIUS + GLOW_FAR;
        for (int gx = x - reach; gx <= x + reach; gx++) {
            for (int gz = z - reach; gz <= z + reach; gz++) {
                for (int y = span.low(); y <= span.high(); y++) {
                    BlockPos pos = new BlockPos(gx, y, gz);
                    if (level.getBlockState(pos).is(LayerBlocks.GLOWCAP)) {
                        found.add(pos);
                    }
                }
            }
        }
        return found;
    }

    /** The floor cell under ({@code x}, {@code z}) near the camera's floor: the free cell over the highest solid one, or empty. */
    static Optional<BlockPos> floorAt(ServerLevel level, int x, int z, int nearY, int rise) {
        for (int y = nearY + rise; y >= nearY - rise; y--) {
            if (isFloor(level, new BlockPos(x, y, z), 2)) {
                return Optional.of(new BlockPos(x, y + 1, z));
            }
        }
        return Optional.empty();
    }

    /** The brightest free cell over a floor in the camera's view {@link #FOCUS_NEAR} to {@link #FOCUS_FAR} blocks ahead, in sight of the eye. */
    private static Optional<BlockPos> focus(ServerLevel level, Spot spot) {
        BlockPos best = null;
        int bestLight = -1;
        double bestOff = Double.MAX_VALUE;
        BlockPos feet = spot.feet();
        for (int dx = -FOCUS_FAR; dx <= FOCUS_FAR; dx++) {
            for (int dz = -FOCUS_FAR; dz <= FOCUS_FAR; dz++) {
                BlockPos column = feet.offset(dx, 0, dz);
                if (!inCone(feet, spot.yaw(), column, FOCUS_NEAR, FOCUS_FAR, FOCUS_CONE)) {
                    continue;
                }
                Optional<BlockPos> cell = floorAt(level, column.getX(), column.getZ(), spot.floorY(), FOCUS_RISE);
                if (cell.isEmpty() || !inSight(level, spot.eye(), cell.get())) {
                    continue;
                }
                int light = level.getBrightness(LightLayer.BLOCK, cell.get());
                double off = Math.abs(Math.hypot(dx, dz) - (FOCUS_NEAR + FOCUS_FAR) / 2.0);
                if (light > bestLight || (light == bestLight && off < bestOff)) {
                    best = cell.get();
                    bestLight = light;
                    bestOff = off;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** The free cell over a flat floor {@link #TALL_NEAR} to {@link #TALL_FAR} blocks ahead with the most free height over its 3 x 3, at least {@link #TALL_CLEAR_LEAST}. */
    private static Optional<BlockPos> tallFocus(ServerLevel level, Spot spot) {
        BlockPos best = null;
        int bestClear = TALL_CLEAR_LEAST - 1;
        BlockPos feet = spot.feet();
        for (int dx = -TALL_FAR; dx <= TALL_FAR; dx++) {
            for (int dz = -TALL_FAR; dz <= TALL_FAR; dz++) {
                BlockPos column = feet.offset(dx, 0, dz);
                if (!inCone(feet, spot.yaw(), column, TALL_NEAR, TALL_FAR, FOCUS_CONE)) {
                    continue;
                }
                Optional<BlockPos> cell = floorAt(level, column.getX(), column.getZ(), spot.floorY(), FOCUS_RISE);
                if (cell.isEmpty() || !inSight(level, spot.eye(), cell.get()) || !flat(level, cell.get())) {
                    continue;
                }
                int clear = clearHeight(level, cell.get());
                if (clear > bestClear) {
                    best = cell.get();
                    bestClear = clear;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** True when the 3 x 3 round {@code cell} is free and stands on solid floor in every column: a great stalk grows only there. */
    private static boolean flat(ServerLevel level, BlockPos cell) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!isFloor(level, cell.offset(dx, -1, dz), 1)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The free height over the 3 x 3 round {@code cell}, up to {@link #TALL_CLEAR_CAP}. */
    static int clearHeight(ServerLevel level, BlockPos cell) {
        int clear = TALL_CLEAR_CAP;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int up = 0;
                while (up < clear && open(level.getBlockState(cell.offset(dx, up, dz)))) {
                    up++;
                }
                clear = up;
            }
        }
        return clear;
    }

    /** True when every cell on the line from {@code eye} to the middle of {@code cell} is open. */
    private static boolean inSight(ServerLevel level, Vec3 eye, BlockPos cell) {
        Vec3 target = Vec3.atCenterOf(cell);
        Vec3 d = target.subtract(eye);
        double length = d.length();
        Vec3 step = d.scale(0.5 / length);
        for (double t = 0.5; t < length - 0.5; t += 0.5) {
            if (!open(level.getBlockState(BlockPos.containing(eye.add(step.scale(t / 0.5)))))) {
                return false;
            }
        }
        return true;
    }
}
