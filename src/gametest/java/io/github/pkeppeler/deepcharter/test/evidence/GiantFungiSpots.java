package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.surface.ParasolShape;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds where the {@code giant-fungi} scenario (#1202) stands its camera in a zone of layer 3: a generated cavern of
 * the campaign world, never a staged room. A spot is a free cell over a floor whose view, in one of eight headings,
 * runs furthest through the cavern (a fan of rays) and takes in the most glowcaps; its tall focus is the floor cell
 * with the most free height a giant can be grown on. A view of the mix ({@link #mix}) looks instead at the densest
 * group of giants the world grew, and a view of a pair ({@link #pair}) at two giants of two forms in neighbouring
 * chunks. Copied from {@code HollowsSpots} of the #1147 concept round (on its branch), which
 * the before and after stills share a camera with. Runs on the server thread over chunks the scenario has forced.
 */
final class GiantFungiSpots {
    private static final Logger LOGGER = LoggerFactory.getLogger(GiantFungiSpots.class);
    /** Candidates stand within this many blocks of the region's middle, every {@link #GRID} blocks. */
    static final int SEARCH_RADIUS = 24;
    /**
     * The rays of the fan reach this far, so the forced region must reach {@code SEARCH_RADIUS + RAY_REACH} round the
     * middle.
     */
    static final int RAY_REACH = 40;

    private static final int GRID = 3;
    /** Candidates stay this far inside their zone, so the camera's biome blend never reaches the next one. */
    private static final int ZONE_INSET = 8;
    /**
     * Free cells over the floor that a candidate needs: its feet stand {@link #FEET_RISE} over it, the eye 1.62 over
     * the feet.
     */
    private static final int HEADROOM = 4;

    static final int FEET_RISE = 2;
    private static final int[] FAN_YAWS = {-30, -15, 0, 15, 30};
    private static final int[] FAN_PITCHES = {-15, 0, 15};
    /**
     * A glowcap within the view's cone, between these distances, adds this much to the score: a lit view reads, a black
     * one does not.
     */
    private static final int GLOW_NEAR = 4;

    private static final int GLOW_FAR = 32;
    private static final double GLOW_CONE = 35;
    private static final int GLOW_WEIGHT = 4;
    private static final int GLOW_CAP = 30;
    /**
     * The focus is a floor cell this far ahead (in blocks), within this many degrees of the heading and this far up or
     * down from the camera's floor.
     */
    private static final int FOCUS_NEAR = 7;

    private static final int FOCUS_FAR = 16;
    private static final double FOCUS_CONE = 25;
    private static final int FOCUS_RISE = 6;
    /** The tall focus, where a giant fungus grows: further off, with the most free height over it. */
    private static final int TALL_NEAR = 10;

    private static final int TALL_FAR = 20;
    private static final int TALL_CLEAR_CAP = 34;
    private static final int TALL_CLEAR_LEAST = 12;
    /** The free height a stage needs at its tall focus: the tallest parasol's stem and its cap. */
    private static final int STAGE_CLEAR = ParasolShape.MOST_STEM + ParasolShape.CAP_ROWS;
    /** Only the best candidates are tried for a focus, in order of score. */
    private static final int TRIED = 200;

    private GiantFungiSpots() {}

    /**
     * A camera: feet at {@code feet}, facing {@code yaw}; {@code floorY} is the floor under it, {@code focus} the floor
     * cell the mock-ups stand on (the free cell over the floor), {@code tall} the one a giant fungus grows from and
     * {@code clear} the free height over it.
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

        /**
         * The cell {@code ahead} blocks along the heading and {@code across} to its right of {@code from}, {@code up}
         * over it.
         */
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
     * The spot of zone {@code zone} (0 is the Garden) of layer 3's {@code band} in {@code level}, round ({@code x},
     * {@code z}), or empty, with what it found in the log, if no candidate there has a focus.
     */
    static Optional<Spot> find(ServerLevel level, LayerChain.Band band, int zone, int x, int z) {
        Zones.Span span = Zones.span(band.minY(), band.height(), zone);
        String name = Zones.of(3, band.minY(), band.height(), span.low())
                .orElseThrow()
                .id()
                .getPath();
        List<BlockPos> glowcaps = scan(level, x, z, span, GiantFungiSpots::glowcap);
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
            // The forms are grown at the tall focus, so a view with no room for the tallest of them is no stage.
            Optional<BlockPos> tall = tallFocus(level, probe);
            if (tall.isEmpty() || clearHeight(level, tall.get()) < STAGE_CLEAR) {
                continue;
            }
            return Optional.of(new Spot(
                    name,
                    c.feet(),
                    c.yaw(),
                    c.floorY(),
                    focus.get(),
                    tall.get(),
                    clearHeight(level, tall.get()),
                    c.score()));
        }
        LOGGER.info(
                "[giant-fungi] no camera spot in {} (Y {} to {}) round {}, {}: {} candidates, {} glowcaps",
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

    private static boolean glowcap(BlockState state) {
        return state.is(LayerBlocks.GLOWCAP);
    }

    /** A block that marks a giant fungus from afar: a parasol's rim, a spire's needle or a great stalk's cap. */
    static boolean landmark(BlockState state) {
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.equals("parasol_rim") || path.equals("spire_tip") || path.equals("great_stalk_cap");
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

    private static List<BlockPos> scan(ServerLevel level, int x, int z, Zones.Span span, Predicate<BlockState> wanted) {
        List<BlockPos> found = new ArrayList<>();
        int reach = SEARCH_RADIUS + GLOW_FAR;
        for (int gx = x - reach; gx <= x + reach; gx++) {
            for (int gz = z - reach; gz <= z + reach; gz++) {
                for (int y = span.low(); y <= span.high(); y++) {
                    BlockPos pos = new BlockPos(gx, y, gz);
                    if (wanted.test(level.getBlockState(pos))) {
                        found.add(pos);
                    }
                }
            }
        }
        return found;
    }

    /**
     * The floor cell under ({@code x}, {@code z}) near the camera's floor: the free cell over the highest solid one, or
     * empty.
     */
    static Optional<BlockPos> floorAt(ServerLevel level, int x, int z, int nearY, int rise) {
        for (int y = nearY + rise; y >= nearY - rise; y--) {
            if (isFloor(level, new BlockPos(x, y, z), 2)) {
                return Optional.of(new BlockPos(x, y + 1, z));
            }
        }
        return Optional.empty();
    }

    /**
     * The brightest free cell over a floor in the camera's view {@link #FOCUS_NEAR} to {@link #FOCUS_FAR} blocks ahead,
     * in sight of the eye.
     */
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

    /**
     * The free cell over a flat floor {@link #TALL_NEAR} to {@link #TALL_FAR} blocks ahead with the most free height
     * over its 3 x 3, at least {@link #TALL_CLEAR_LEAST}.
     */
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

    /**
     * True when the 3 x 3 round {@code cell} is free and stands on solid floor in every column: a great stalk grows
     * only there.
     */
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

    /** A view of the mix the world grew: the camera's feet, and the point among the giants it looks at. */
    record MixView(String zone, BlockPos feet, Vec3 target, int giants) {}

    /**
     * The mix's camera sees a giant's mark from this near to this far, within {@link #GLOW_CONE} of its heading, and
     * never past {@link #IN_FOG} of the zone's fog.
     */
    private static final int MIX_NEAR = 8;

    private static final int MIX_FAR = 26;
    /** The share of a zone's fog distance a camera sees a giant within: past it the fog has nearly hidden it. */
    private static final double IN_FOG = 0.7;
    /** Candidate cameras for the mix stand every this many blocks. */
    private static final int MIX_GRID = 4;

    /**
     * A camera on the giants of zone {@code zone} the world grew round ({@code x}, {@code z}): of the floors every
     * {@link #MIX_GRID} blocks and the eight headings, the one that sees the most giants' marks (a rim, a needle, a
     * great stalk's cap) {@link #MIX_NEAR} to {@link #MIX_FAR} blocks off, inside the fog, with nothing in the way, a
     * giant counted once, and of those the one whose view runs furthest open; it looks at the middle of the giants it
     * sees. Empty if it sees none.
     */
    static Optional<MixView> mix(ServerLevel level, LayerChain.Band band, int zone, int x, int z) {
        Zones.Span span = Zones.span(band.minY(), band.height(), zone);
        String name = Zones.of(3, band.minY(), band.height(), span.low())
                .orElseThrow()
                .id()
                .getPath();
        List<BlockPos> marks = scan(level, x, z, span, GiantFungiSpots::landmark);
        LOGGER.info("[giant-fungi] {}: {} giant marks round {}, {}", name, marks.size(), x, z);
        double far = Math.min(MIX_FAR, IN_FOG * fogEnd(level, span, x, z));
        MixView best = null;
        int bestSeen = 0;
        for (int cx = x - SEARCH_RADIUS; cx <= x + SEARCH_RADIUS; cx += MIX_GRID) {
            for (int cz = z - SEARCH_RADIUS; cz <= z + SEARCH_RADIUS; cz += MIX_GRID) {
                for (int y = span.high() - ZONE_INSET; y >= span.low() + ZONE_INSET; y--) {
                    if (!isFloor(level, new BlockPos(cx, y, cz), HEADROOM)) {
                        continue;
                    }
                    BlockPos feet = new BlockPos(cx, y + FEET_RISE, cz);
                    Vec3 eye = new Vec3(cx + 0.5, feet.getY() + EYE, cz + 0.5);
                    for (int heading = 0; heading < 8; heading++) {
                        float yaw = heading * 45f;
                        List<BlockPos> seen = new ArrayList<>();
                        for (BlockPos mark : marks) {
                            if (inCone(feet, yaw, mark, MIX_NEAR, far, GLOW_CONE)
                                    && seen.stream().noneMatch(other -> other.distSqr(mark) < 25)
                                    && inSight(level, eye, mark)) {
                                seen.add(mark);
                            }
                        }
                        // The giants it sees first, then how far the view runs open, so a wall at the eye never
                        // fills it.
                        int score = seen.isEmpty() ? 0 : seen.size() * 1000 + fan(level, feet, yaw);
                        if (score > bestSeen) {
                            bestSeen = score;
                            Vec3 middle = Vec3.ZERO;
                            for (BlockPos mark : seen) {
                                middle = middle.add(Vec3.atCenterOf(mark).scale(1.0 / seen.size()));
                            }
                            best = new MixView(name, feet, middle, seen.size());
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** A giant the world grew in one chunk: its form, and its cells. */
    private record Grown(ChunkPos chunk, String form, List<BlockPos> cells) {
        Vec3 middle() {
            Vec3 sum = Vec3.ZERO;
            for (BlockPos cell : cells) {
                sum = sum.add(Vec3.atCenterOf(cell));
            }
            return sum.scale(1.0 / cells.size());
        }

        /** {@link #PAIR_SAMPLES} of its cells, spread through it from its foot to its top, a camera is checked on. */
        List<BlockPos> samples() {
            List<BlockPos> sorted = new ArrayList<>(cells);
            sorted.sort(Comparator.comparingInt((BlockPos pos) -> pos.getY())
                    .thenComparingInt(pos -> pos.getX())
                    .thenComparingInt(pos -> pos.getZ()));
            List<BlockPos> picked = new ArrayList<>();
            for (int i = 0; i < PAIR_SAMPLES; i++) {
                picked.add(sorted.get(i * (sorted.size() - 1) / (PAIR_SAMPLES - 1)));
            }
            return picked;
        }
    }

    /** A camera on a pair of giants: where it stands, where it looks, and how well it sees them. */
    private record PairView(BlockPos feet, Vec3 target, int score) {}

    /** The cells of a giant a camera is checked against. */
    private static final int PAIR_SAMPLES = 16;
    /**
     * A pair's camera stands this near to this far from the middle of each giant, and never past {@link #IN_FOG} of the
     * zone's fog; nearer still in a zone of thick fog, at half that.
     */
    private static final int PAIR_NEAR = 12;

    private static final int PAIR_FAR = 30;
    /**
     * The most degrees across and up the two giants take in the frame: the default 70-degree field is 70 high and
     * about 100 wide at the evidence frame's 16:9, less a margin round them.
     */
    private static final double PAIR_ACROSS = 84;

    private static final double PAIR_UP = 60;
    /** Candidate cameras for a pair stand every this many blocks. */
    private static final int PAIR_GRID = 2;

    /** The form of a giant's block: empty for a block of none, and "" for the flare, which every form but one has. */
    private static Optional<String> form(BlockState state) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (!id.getNamespace().equals("deepcharter")) {
            return Optional.empty();
        }
        String path = id.getPath();
        if (path.startsWith("parasol_")) {
            return Optional.of("parasol");
        }
        if (path.startsWith("spire")) {
            return Optional.of("spires");
        }
        if (path.startsWith("great_stalk")) {
            return Optional.of("great stalk");
        }
        return path.equals("fungal_flare") ? Optional.of("") : Optional.empty();
    }

    /** Whether {@code state} is a block of a giant fungus. */
    static boolean giant(BlockState state) {
        return form(state).isPresent();
    }

    /**
     * A camera on two giants the world grew in neighbouring chunks of zone {@code zone} round ({@code x}, {@code z}),
     * of two forms, both {@link #PAIR_NEAR} to {@link #PAIR_FAR} blocks off, inside the fog and whole in the frame; of
     * those, the one
     * that sees the most of the less seen giant, then of both. It looks at the middle of the two as the frame sees
     * them. Empty if no two such giants have a camera.
     */
    static Optional<MixView> pair(ServerLevel level, LayerChain.Band band, int zone, int x, int z) {
        Zones.Span span = Zones.span(band.minY(), band.height(), zone);
        String name = Zones.of(3, band.minY(), band.height(), span.low())
                .orElseThrow()
                .id()
                .getPath();
        Map<ChunkPos, List<BlockPos>> cells = new HashMap<>();
        Map<ChunkPos, String> forms = new HashMap<>();
        for (BlockPos pos : scan(level, x, z, span, GiantFungiSpots::giant)) {
            ChunkPos chunk = ChunkPos.containing(pos);
            cells.computeIfAbsent(chunk, key -> new ArrayList<>()).add(pos);
            String form = form(level.getBlockState(pos)).orElseThrow();
            if (!form.isEmpty()) {
                forms.put(chunk, form);
            }
        }
        List<Grown> grown = new ArrayList<>();
        cells.forEach((chunk, list) -> {
            if (forms.containsKey(chunk)) {
                grown.add(new Grown(chunk, forms.get(chunk), list));
            }
        });
        PairView best = null;
        String bestPair = "";
        for (int i = 0; i < grown.size(); i++) {
            for (int j = i + 1; j < grown.size(); j++) {
                Grown a = grown.get(i);
                Grown b = grown.get(j);
                int apartX = Math.abs(a.chunk().x() - b.chunk().x());
                int apartZ = Math.abs(a.chunk().z() - b.chunk().z());
                if (a.form().equals(b.form()) || apartX > 1 || apartZ > 1) {
                    continue;
                }
                Optional<PairView> view = pairCamera(level, span, a, b, x, z);
                if (view.isPresent() && (best == null || view.get().score() > best.score())) {
                    best = view.get();
                    bestPair = a.form() + " in " + a.chunk() + " and " + b.form() + " in " + b.chunk();
                }
            }
        }
        LOGGER.info("[giant-fungi] {}: {} giants round {}, {}; pair {}", name, grown.size(), x, z, bestPair);
        return best == null ? Optional.empty() : Optional.of(new MixView(name, best.feet(), best.target(), 2));
    }

    /**
     * The best camera on {@code a} and {@code b}, if any sees some of each with both whole in the frame, standing in
     * the region scanned round ({@code x}, {@code z}).
     */
    private static Optional<PairView> pairCamera(ServerLevel level, Zones.Span span, Grown a, Grown b, int x, int z) {
        int reach = SEARCH_RADIUS + GLOW_FAR;
        Vec3 middleA = a.middle();
        Vec3 middleB = b.middle();
        Vec3 middle = middleA.add(middleB).scale(0.5);
        List<BlockPos> samplesA = a.samples();
        List<BlockPos> samplesB = b.samples();
        List<BlockPos> both = new ArrayList<>(a.cells());
        both.addAll(b.cells());
        PairView best = null;
        int cx0 = (int) Math.floor(middle.x);
        int cz0 = (int) Math.floor(middle.z);
        double far = Math.min(PAIR_FAR, IN_FOG * fogEnd(level, span, cx0, cz0));
        double near = Math.min(PAIR_NEAR, far / 2);
        for (int cx = cx0 - PAIR_FAR; cx <= cx0 + PAIR_FAR; cx += PAIR_GRID) {
            for (int cz = cz0 - PAIR_FAR; cz <= cz0 + PAIR_FAR; cz += PAIR_GRID) {
                double toA = Math.hypot(middleA.x - cx - 0.5, middleA.z - cz - 0.5);
                double toB = Math.hypot(middleB.x - cx - 0.5, middleB.z - cz - 0.5);
                boolean inRegion = Math.abs(cx - x) <= reach && Math.abs(cz - z) <= reach;
                if (!inRegion || toA < near || toA > far || toB < near || toB > far) {
                    continue;
                }
                for (int y = span.high() - ZONE_INSET; y >= span.low() + ZONE_INSET; y--) {
                    if (!isFloor(level, new BlockPos(cx, y, cz), HEADROOM)) {
                        continue;
                    }
                    BlockPos feet = new BlockPos(cx, y + FEET_RISE, cz);
                    Vec3 eye = new Vec3(cx + 0.5, feet.getY() + EYE, cz + 0.5);
                    Optional<Vec3> aim = frame(eye, both);
                    if (aim.isEmpty()) {
                        continue;
                    }
                    int seenA = seen(level, eye, samplesA);
                    int seenB = seen(level, eye, samplesB);
                    int score = Math.min(seenA, seenB) * 100 + seenA + seenB;
                    if (Math.min(seenA, seenB) > 0 && (best == null || score > best.score())) {
                        best = new PairView(feet, aim.get(), score);
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * The point the eye at {@code eye} looks at to have every one of {@code cells} in the frame, in the middle of their
     * spread across and up; empty if they spread wider than {@link #PAIR_ACROSS} or higher than {@link #PAIR_UP}.
     */
    private static Optional<Vec3> frame(Vec3 eye, List<BlockPos> cells) {
        Vec3 middle = Vec3.ZERO;
        for (BlockPos cell : cells) {
            middle = middle.add(Vec3.atCenterOf(cell).scale(1.0 / cells.size()));
        }
        double heading = Math.atan2(middle.z - eye.z, middle.x - eye.x);
        double left = 0;
        double right = 0;
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for (BlockPos cell : cells) {
            Vec3 d = Vec3.atCenterOf(cell).subtract(eye);
            double across = Mth.wrapDegrees(Math.toDegrees(Math.atan2(d.z, d.x) - heading));
            left = Math.min(left, across);
            right = Math.max(right, across);
            double up = Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
            low = Math.min(low, up);
            high = Math.max(high, up);
        }
        if (right - left > PAIR_ACROSS || high - low > PAIR_UP) {
            return Optional.empty();
        }
        double yaw = heading + Math.toRadians((left + right) / 2);
        double pitch = Math.toRadians((low + high) / 2);
        return Optional.of(eye.add(
                Math.cos(yaw) * Math.cos(pitch) * 10, Math.sin(pitch) * 10, Math.sin(yaw) * Math.cos(pitch) * 10));
    }

    /** The distance the fog of the zone {@code span} ends at, read halfway up its rows at ({@code x}, {@code z}). */
    private static float fogEnd(ServerLevel level, Zones.Span span, int x, int z) {
        BlockPos at = new BlockPos(x, (span.low() + span.high()) / 2, z);
        return level.environmentAttributes().getValue(EnvironmentAttributes.FOG_END_DISTANCE, at);
    }

    private static int seen(ServerLevel level, Vec3 eye, List<BlockPos> samples) {
        int seen = 0;
        for (BlockPos sample : samples) {
            if (inSight(level, eye, sample)) {
                seen++;
            }
        }
        return seen;
    }

    /**
     * The feet of a camera about {@code distance} blocks from {@code base} (2 either way) on a floor within 10 rows of
     * it, whose eye sees both {@code target} and the cell over {@code base}, and, {@code flank} blocks to either side
     * of each, the points beside them, so no rock near the camera fills a side of the frame (0 for no such check); the
     * heading nearest {@code prefer} first; empty if no heading has one.
     */
    static Optional<BlockPos> cameraAt(
            ServerLevel level, BlockPos base, Vec3 target, int distance, float prefer, int flank) {
        BlockPos foot = base.above(2);
        for (int turn = 0; turn < 32; turn++) {
            // 0, +1, -1, +2, -2 ... steps of 11.25 degrees from the preferred heading.
            int step = (turn + 1) / 2 * (turn % 2 == 0 ? -1 : 1);
            double yaw = Math.toRadians(prefer + 180 + step * 11.25);
            for (int d : new int[] {distance, distance - 1, distance + 1, distance - 2, distance + 2}) {
                int x = (int) Math.floor(base.getX() + 0.5 - Math.sin(yaw) * d);
                int z = (int) Math.floor(base.getZ() + 0.5 + Math.cos(yaw) * d);
                Optional<BlockPos> floor = floorAt(level, x, z, base.getY(), 10);
                if (floor.isEmpty()) {
                    continue;
                }
                BlockPos feet = floor.get().above(FEET_RISE - 1);
                Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + EYE, feet.getZ() + 0.5);
                if (open(level.getBlockState(BlockPos.containing(eye)))
                        && inSight(level, eye, BlockPos.containing(target))
                        && inSight(level, eye, foot)
                        && flanksInSight(level, eye, target, foot, flank)) {
                    return Optional.of(feet);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Whether the eye sees the points {@code flank} blocks to either side of {@code target} and of {@code foot}, across
     * its line of sight.
     */
    private static boolean flanksInSight(ServerLevel level, Vec3 eye, Vec3 target, BlockPos foot, int flank) {
        if (flank == 0) {
            return true;
        }
        Vec3 across =
                new Vec3(target.z - eye.z, 0, eye.x - target.x).normalize().scale(flank);
        for (Vec3 point : List.of(target, Vec3.atCenterOf(foot))) {
            for (int sign : new int[] {-1, 1}) {
                if (!inSight(level, eye, BlockPos.containing(point.add(across.scale(sign))))) {
                    return false;
                }
            }
        }
        return true;
    }
}
