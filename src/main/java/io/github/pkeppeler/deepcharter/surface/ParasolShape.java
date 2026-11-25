package io.github.pkeppeler.deepcharter.surface;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.surface.GiantPiece.Lip;
import io.github.pkeppeler.deepcharter.surface.GreatStalkLayout.Cell;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import net.minecraft.core.Direction;

/**
 * The shape of a parasol (#1202): a tall stem, curving gently, on a flared foot, under a broad, flat cap with glowing
 * gills underneath and a thin glowing rim, like a lamp-post of the deep. The real-world model is the parasol mushroom
 * (<i>Macrolepiota</i>). The generator rolls {@link Params} from a seed and makes the blocks as a {@link GiantLayout},
 * pure data, so the shape can be inspected without a world.
 *
 * <p>The stem is three cells thick (#1218): a core with an arm on each side, leaning as the core leans. Its foot is a
 * root flare two cells out, so it stands rather than sticks up. The cap is a height field: each column's top is the
 * plane of the dome at its middle, so the cap slopes instead of stepping, and its dome rises in proportion to its
 * radius, so a cap stays flat however wide it is. Its edge is a ring of rim cells, a thin lip that hangs past the
 * gills. A cap is never wider than a chunk (ADR 0088): its radius is at most {@link #MOST_RADIUS}.
 */
public final class ParasolShape {
    /** The shortest stem a parasol grows: under this much room a site grows spires instead. */
    public static final int LEAST_STEM = 12;

    public static final int MOST_STEM = 26;
    public static final int LEAST_RADIUS = 4;
    /** A cap of this radius is a chunk wide (15 cells), so it fits only with its stem at the chunk's middle. */
    public static final int MOST_RADIUS = 7;
    /** The most cells sideways a stem's curve moves its cap. */
    public static final int MOST_DRIFT = 2;

    public static final double MOST_TILT_DEGREES = 12;
    /** The rows a parasol needs over its stem for its cap: its tallest dome, and air above it. */
    public static final int CAP_ROWS = 4;

    private static final int PIXELS = 16;
    /**
     * The lowest a cap's surface is inside its rim, in pixels over the bottom of the gills: one full cell of cap over
     * them.
     */
    private static final double LEAST_TOP = 18;

    private static final double UMBO = 0.22;
    /** How many cells out from the stem its foot's flare reaches. */
    private static final int FOOT = 2;
    /** The widest cap a twin has: wider, and it reaches under the first's cap to its stem. */
    private static final int TWIN_RADIUS = 3;

    private ParasolShape() {}

    /** How far a parasol has grown. */
    public enum Stage {
        /** Closed and deep: a steep dome, its rim a deep skirt. */
        BELL,
        /** Open: a flat dome with a knob at the middle. */
        OPEN,
        /** Old: the rim turned up and torn. */
        OLD
    }

    /**
     * How a zone's parasols grow: how often each stage grows, as weights, and cells added to every cap's radius (up to
     * {@link #MOST_RADIUS}).
     */
    public record Style(int bell, int open, int old, int radiusBonus) {
        public static final Style DEFAULT = new Style(20, 60, 20, 0);
        public static final Codec<Style> CODEC = RecordCodecBuilder.<Style>create(instance -> instance.group(
                                Codec.intRange(0, 100).fieldOf("bell").forGetter(Style::bell),
                                Codec.intRange(0, 100).fieldOf("open").forGetter(Style::open),
                                Codec.intRange(0, 100).fieldOf("old").forGetter(Style::old),
                                Codec.intRange(0, MOST_RADIUS - LEAST_RADIUS)
                                        .optionalFieldOf("radius_bonus", 0)
                                        .forGetter(Style::radiusBonus))
                        .apply(instance, Style::new))
                .validate(style -> style.bell + style.open + style.old > 0
                        ? DataResult.success(style)
                        : DataResult.error(() -> "a parasol style needs a stage with a weight over 0"));

        Stage pick(int roll) {
            int at = roll % (bell + open + old);
            return at < bell ? Stage.BELL : at < bell + open ? Stage.OPEN : Stage.OLD;
        }
    }

    /**
     * A rolled parasol.
     *
     * @param stage      how far it has grown
     * @param stemHeight the stem's cells, from the floor to the gills
     * @param radius     the cap's radius in cells
     * @param drift      the cells the stem's curve moves the cap sideways, one direction for each, in the order it
     *     grows them
     * @param leanRows   the rows each cell of drift takes: 5 for a gentle curve, 3 for a tighter one
     * @param tilt       how far the cap tips toward the curve, in degrees
     * @param dome       the dome's rise over the rim, in pixels
     * @param lobes      how many lobes the rim has
     * @param lobeDepth  how deep they are, in cells
     * @param lobePhase  where the first lobe is, in radians
     * @param tears      the angles of the rim's tears, in radians
     * @param tearWidth  how wide each tear is, in cells at the rim
     * @param ringRow    the stem row the ring is on, or -1 for none
     * @param lamps      gill lamps under the cap: real light
     * @param glowcaps   glowcaps round the foot
     * @param twin       whether a smaller parasol grows from the same flare
     */
    public record Params(
            Stage stage,
            int stemHeight,
            int radius,
            List<Direction> drift,
            int leanRows,
            double tilt,
            double dome,
            int lobes,
            double lobeDepth,
            double lobePhase,
            List<Double> tears,
            int tearWidth,
            int ringRow,
            int lamps,
            int glowcaps,
            boolean twin) {
        public Params {
            drift = List.copyOf(drift);
            tears = List.copyOf(tears);
        }
    }

    static double unit(IntUnaryOperator random) {
        return random.applyAsInt(1 << 16) / 65536.0;
    }

    private static double between(IntUnaryOperator random, double low, double high) {
        return low + (high - low) * unit(random);
    }

    static Direction horizontal(IntUnaryOperator random) {
        return Direction.from2DDataValue(random.applyAsInt(4));
    }

    /**
     * A parasol rolled from {@code random} ({@code random.applyAsInt(n)} is from 0 up to but not including n) for a
     * site with room for a stem of {@code mostStem} cells, in a zone whose parasols grow as {@code style}; empty where
     * that is under {@link #LEAST_STEM}.
     */
    public static Optional<Params> roll(IntUnaryOperator random, int mostStem, Style style) {
        if (mostStem < LEAST_STEM) {
            return Optional.empty();
        }
        Stage stage = style.pick(random.applyAsInt(style.bell() + style.open() + style.old()));
        int height = Math.min(LEAST_STEM + random.applyAsInt(MOST_STEM - LEAST_STEM + 1), mostStem);
        // A cell over 0.28 of the height, give or take one, so a cap is wide for its stem (#1218): 6 to 7 the norm.
        int radius = (int) Math.round(0.28 * height) + random.applyAsInt(3) + style.radiusBonus();
        if (stage == Stage.BELL) {
            radius--;
        }
        radius = Math.clamp(radius, LEAST_RADIUS, MOST_RADIUS);
        int driftRoll = random.applyAsInt(20);
        int cells = driftRoll < 6 ? 0 : driftRoll < 15 ? 1 : MOST_DRIFT;
        List<Direction> drift = new ArrayList<>();
        Direction way = horizontal(random);
        for (int i = 0; i < cells; i++) {
            // A second cell goes on the same way or turns a quarter, so a curve can run on a diagonal.
            drift.add(i == 0 ? way : random.applyAsInt(2) == 0 ? way : way.getClockWise());
        }
        int leanRows = 2 + 5 * cells < height * 0.55 ? 5 : 3;
        double tilt = cells == 0 ? 0 : between(random, 0, MOST_TILT_DEGREES);
        // The dome's rise in pixels for each cell of radius, so a wide cap is as flat as a narrow one (#1218).
        double dome = radius
                * switch (stage) {
                    case BELL -> between(random, 3.5, 4.5);
                    case OPEN -> between(random, 1.6, 2.4);
                    case OLD -> between(random, 1.8, 2.4);
                };
        int lobes = 3 + random.applyAsInt(4);
        double lobeDepth = between(random, 0, 1);
        double lobePhase = between(random, 0, 2 * Math.PI);
        int tearCount = stage == Stage.OLD ? 1 + random.applyAsInt(2) : random.applyAsInt(4) == 0 ? 1 : 0;
        List<Double> tears = new ArrayList<>();
        for (int i = 0; i < tearCount; i++) {
            tears.add(between(random, 0, 2 * Math.PI));
        }
        int tearWidth = 1 + random.applyAsInt(2);
        int firstStraight = 2 + leanRows * cells;
        int ringRow = -1;
        if (random.applyAsInt(5) != 0) {
            int low = Math.max(firstStraight, (int) Math.ceil(height * 0.55));
            int high = (int) Math.floor(height * 0.75);
            if (low <= high) {
                ringRow = low + random.applyAsInt(high - low + 1);
            }
        }
        GiantFungusTuning tuning = GiantFungusTuning.DEFAULT;
        int lamps = random.applyAsInt(tuning.mostGillLamps() + 1);
        int glowcaps = tuning.parasolGlowcaps() + random.applyAsInt(3);
        boolean twin = random.applyAsInt(100) < 15;
        return Optional.of(new Params(
                stage, height, radius, drift, leanRows, tilt, dome, lobes, lobeDepth, lobePhase, tears, tearWidth,
                ringRow, lamps, glowcaps, twin));
    }

    /**
     * The same parasol made to fit a site where its cap does not have room: a stem {@code rows} shorter (down to {@link
     * #LEAST_STEM}, its ring kept on it) and a cap {@code narrower} cells narrower (down to {@link #LEAST_RADIUS}), its
     * dome lowered with it so it stays as flat; empty when neither can give.
     */
    public static Optional<Params> smaller(Params params, int rows, int narrower) {
        int height = Math.max(LEAST_STEM, params.stemHeight() - rows);
        int radius = Math.max(LEAST_RADIUS, params.radius() - narrower);
        int straightFrom = 2 + params.leanRows() * params.drift().size();
        if ((height == params.stemHeight() && radius == params.radius()) || straightFrom > height - 2) {
            return Optional.empty();
        }
        int ring = params.ringRow() >= straightFrom && params.ringRow() < height - 1 ? params.ringRow() : -1;
        return Optional.of(new Params(
                params.stage(),
                height,
                radius,
                params.drift(),
                params.leanRows(),
                params.tilt(),
                params.dome() * radius / params.radius(),
                params.lobes(),
                params.lobeDepth(),
                params.lobePhase(),
                params.tears(),
                params.tearWidth(),
                ring,
                params.lamps(),
                params.glowcaps(),
                params.twin()));
    }

    /**
     * The layout of a rolled parasol, with its stem's foot at the origin; {@code random} picks where its lamps and
     * glowcaps go.
     */
    public static GiantLayout layout(Params params, IntUnaryOperator random) {
        GiantLayout.Builder cells = new GiantLayout.Builder();
        Cell top = grow(cells, params, new Cell(0, 0, 0), 1, true);
        foot(cells);
        lamps(cells, params, top, random);
        if (params.twin()) {
            twin(cells, params, random);
        }
        glowcaps(cells, params.glowcaps(), random);
        return cells.build();
    }

    /**
     * Grows one parasol's stem and cap from {@code foot}, as part {@code owner}, a {@code thick} stem with an arm on
     * each side of its core from the row over the foot up; returns the cell under the middle of its cap.
     */
    private static Cell grow(GiantLayout.Builder cells, Params params, Cell foot, int owner, boolean thick) {
        List<Cell> core = new ArrayList<>();
        Cell at = foot;
        int row = 0;
        List<Direction> drift = params.drift();
        int stepsDone = 0;
        while (row < params.stemHeight()) {
            boolean leaning = row >= 2 && stepsDone < drift.size();
            if (leaning) {
                Direction way = drift.get(stepsDone);
                for (RodPiece piece : RodPiece.lean(params.leanRows())) {
                    if (row >= params.stemHeight()) {
                        break;
                    }
                    cells.put(at, new GiantPiece.Stem(piece, way, GiantPiece.Arm.NONE), owner);
                    core.add(at);
                    if (piece.stepsAside()) {
                        at = at.offset(way.getStepX(), 0, way.getStepZ());
                    } else {
                        at = at.offset(0, 1, 0);
                        row++;
                    }
                }
                stepsDone++;
                continue;
            }
            GiantPiece piece = row == params.ringRow()
                    ? new GiantPiece.Ring()
                    : new GiantPiece.Stem(RodPiece.STRAIGHT, Direction.NORTH, GiantPiece.Arm.NONE);
            cells.put(at, piece, owner);
            core.add(at);
            at = at.offset(0, 1, 0);
            row++;
        }
        if (thick) {
            arms(cells, core, foot.y(), owner);
        }
        cap(cells, params, at, owner);
        return at;
    }

    /**
     * An arm on each side of every cell of the core above {@code footRow}, where the core itself is not: a cell of a
     * split row has its other half beside it, which draws the arm that would be there.
     */
    private static void arms(GiantLayout.Builder cells, List<Cell> core, int footRow, int owner) {
        for (Cell cell : core) {
            if (cell.y() == footRow) {
                continue;
            }
            RodPiece piece = RodPiece.STRAIGHT;
            Direction facing = Direction.NORTH;
            if (cells.piece(cell) instanceof GiantPiece.Stem stem) {
                piece = stem.piece();
                facing = stem.facing();
            }
            for (GiantPiece.Arm arm :
                    List.of(GiantPiece.Arm.FRONT, GiantPiece.Arm.BACK, GiantPiece.Arm.LEFT, GiantPiece.Arm.RIGHT)) {
                Direction side = arm.side(facing);
                Cell next = cell.offset(side.getStepX(), 0, side.getStepZ());
                if (!cells.has(next)) {
                    cells.put(next, new GiantPiece.Stem(piece, facing, arm), owner);
                }
            }
        }
    }

    /**
     * The surface of the cap over the bottom of its gills, in pixels, at a point {@code dx}, {@code dz} cells from its
     * middle.
     */
    static double surface(Params params, double dx, double dz) {
        double rho = Math.min(1, Math.hypot(dx, dz) / params.radius());
        double rise = switch (params.stage()) {
            case BELL -> params.dome() * Math.pow(1 - rho * rho, 0.7);
            case OPEN -> params.dome() * (1 - rho * rho) + (rho < UMBO ? 5 * (1 - rho / UMBO) : 0);
            case OLD -> params.dome() * (1 - rho * rho);
        };
        double tipped = 0;
        if (!params.drift().isEmpty()) {
            // Down on the side the stem curves toward.
            double wx = 0;
            double wz = 0;
            for (Direction step : params.drift()) {
                wx += step.getStepX();
                wz += step.getStepZ();
            }
            double length = Math.hypot(wx, wz);
            tipped = -Math.tan(Math.toRadians(params.tilt())) * PIXELS * (dx * wx + dz * wz) / length;
        }
        return LEAST_TOP + rise + tipped;
    }

    /**
     * Whether the column {@code dx}, {@code dz} cells from the cap's middle is under the cap: inside the lobed, torn
     * outline.
     */
    static boolean inCap(Params params, int dx, int dz) {
        if (dx == 0 && dz == 0) {
            return true;
        }
        double angle = Math.atan2(dz, dx);
        double reach = params.radius()
                + 0.45
                - params.lobeDepth() * (0.5 + 0.5 * Math.cos(params.lobes() * angle + params.lobePhase()));
        double distance = Math.hypot(dx, dz);
        if (distance > reach) {
            return false;
        }
        for (double tear : params.tears()) {
            double off = Math.abs(Math.IEEEremainder(angle - tear, 2 * Math.PI));
            // A wedge tearWidth cells wide at the rim, reaching in from it a third of the radius.
            if (distance > params.radius() * 0.6 && off * params.radius() < params.tearWidth() * 0.5 + 0.1) {
                return false;
            }
        }
        return true;
    }

    private static int quantise(double pixels, int low, int high) {
        return Math.clamp(Math.round(pixels / 4) * 4, low, high);
    }

    /**
     * The columns of the cap, relative to its middle: those inside its outline ({@link #inCap}) that join the middle
     * side by side, so a lobe a tear cuts off falls away instead of floating.
     */
    private static Set<Long> capColumns(Params params) {
        Set<Long> reached = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        reached.add(column(0, 0));
        queue.add(new int[] {0, 0});
        while (!queue.isEmpty()) {
            int[] at = queue.poll();
            for (Direction side : Direction.Plane.HORIZONTAL) {
                int x = at[0] + side.getStepX();
                int z = at[1] + side.getStepZ();
                if (inCap(params, x, z) && reached.add(column(x, z))) {
                    queue.add(new int[] {x, z});
                }
            }
        }
        return reached;
    }

    private static long column(int dx, int dz) {
        return ((long) dx << 32) ^ (dz & 0xFFFFFFFFL);
    }

    private static void cap(GiantLayout.Builder cells, Params params, Cell gills, int owner) {
        int radius = params.radius();
        Set<Long> columns = capColumns(params);
        Lip lip = switch (params.stage()) {
            case BELL -> Lip.DEEP;
            case OPEN -> Lip.DOWN;
            case OLD -> Lip.UP;
        };
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!columns.contains(column(dx, dz))) {
                    continue;
                }
                Cell column = gills.offset(dx, 0, dz);
                Optional<Direction[]> open = openSides(columns, dx, dz);
                if (open.isPresent()) {
                    Direction[] sides = open.get();
                    // The rim's surface meets the cap's at the inner side of the cell.
                    double inX = dx - 0.5 * (sides[0].getStepX() + (sides.length > 1 ? sides[1].getStepX() : 0));
                    double inZ = dz - 0.5 * (sides[0].getStepZ() + (sides.length > 1 ? sides[1].getStepZ() : 0));
                    int level = quantise(surface(params, inX, inZ), 8, 32);
                    cells.put(column, new GiantPiece.Rim(sides[0], sides.length > 1, level, lip), owner);
                    continue;
                }
                cells.put(column, gills(dx, dz, false), owner);
                double height = Math.max(LEAST_TOP, surface(params, dx, dz));
                int rows = (int) Math.floor((height - 0.001) / PIXELS);
                for (int row = 1; row < rows; row++) {
                    cells.put(column.offset(0, row, 0), new GiantPiece.Cap(PIXELS, 0, 0, false), owner);
                }
                int level = quantise(height - rows * PIXELS, 4, PIXELS);
                int riseX = quantise(surface(params, dx + 0.5, dz) - surface(params, dx - 0.5, dz), -PIXELS, PIXELS);
                int riseZ = quantise(surface(params, dx, dz + 0.5) - surface(params, dx, dz - 0.5), -PIXELS, PIXELS);
                boolean umbo = params.stage() == Stage.OPEN && Math.hypot(dx, dz) < UMBO * radius;
                cells.put(column.offset(0, rows, 0), new GiantPiece.Cap(level, riseX, riseZ, umbo), owner);
            }
        }
    }

    /**
     * The sides of a cap cell that open to the air past the cap: one for an edge, two (a face and the face clockwise of
     * it) for a corner; empty for a cell inside the cap. A cell open on two opposite sides or on three faces the way
     * that points most outward.
     */
    private static Optional<Direction[]> openSides(Set<Long> columns, int dx, int dz) {
        List<Direction> open = new ArrayList<>();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (!columns.contains(column(dx + side.getStepX(), dz + side.getStepZ()))) {
                open.add(side);
            }
        }
        if (open.isEmpty()) {
            return Optional.empty();
        }
        if (open.size() == 2 && open.get(0).getClockWise() == open.get(1)) {
            return Optional.of(new Direction[] {open.get(0), open.get(1)});
        }
        if (open.size() == 2 && open.get(1).getClockWise() == open.get(0)) {
            return Optional.of(new Direction[] {open.get(1), open.get(0)});
        }
        if (open.size() == 1) {
            return Optional.of(new Direction[] {open.getFirst()});
        }
        return Optional.of(new Direction[] {Direction.getApproximateNearest(dx, 0, dz)});
    }

    /** A gill cell whose gills run away from the middle of the cap, toward the nearest of eight ways. */
    private static GiantPiece.Gills gills(int dx, int dz, boolean lit) {
        if (dx == 0 && dz == 0) {
            return new GiantPiece.Gills(Direction.NORTH, false, lit);
        }
        int eighth = Math.floorMod((int) Math.round(Math.atan2(dz, dx) / (Math.PI / 4)), 8);
        // Eighths count from east toward south: even ones are faces, odd ones the corner clockwise of the face before.
        Direction[] faces = {Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH};
        return new GiantPiece.Gills(faces[eighth / 2], eighth % 2 == 1, lit);
    }

    /**
     * The root flare round the stem's foot, {@link #FOOT} cells out (#1218): a tall wedge in each of the eight cells
     * round the core, under its arms, and a low one in each cell of the ring outside them, each rising toward the stem.
     */
    private static void foot(GiantLayout.Builder cells) {
        for (int dx = -FOOT; dx <= FOOT; dx++) {
            for (int dz = -FOOT; dz <= FOOT; dz++) {
                int out = Math.max(Math.abs(dx), Math.abs(dz));
                if (out == 0) {
                    continue;
                }
                boolean corner = Math.abs(dx) == out && Math.abs(dz) == out;
                Direction toward;
                if (corner) {
                    // Named by the face whose clockwise neighbour is the other: toward the stem in x, then in z.
                    Direction inX = dx > 0 ? Direction.WEST : Direction.EAST;
                    Direction inZ = dz > 0 ? Direction.NORTH : Direction.SOUTH;
                    toward = inX.getClockWise() == inZ ? inX : inZ;
                } else if (Math.abs(dx) == out) {
                    toward = dx > 0 ? Direction.WEST : Direction.EAST;
                } else {
                    toward = dz > 0 ? Direction.NORTH : Direction.SOUTH;
                }
                cells.putOptional(new Cell(dx, 0, dz), new GiantPiece.Flare(toward, corner, out == 1));
            }
        }
    }

    /** Up to {@code params.lamps()} gill lamps, in the gill cells beside the stem's top. */
    private static void lamps(GiantLayout.Builder cells, Params params, Cell top, IntUnaryOperator random) {
        List<Direction> sides =
                new ArrayList<>(Direction.Plane.HORIZONTAL.stream().toList());
        int lit = 0;
        while (lit < params.lamps() && !sides.isEmpty()) {
            Direction side = sides.remove(random.applyAsInt(sides.size()));
            Cell cell = top.offset(side.getStepX(), 0, side.getStepZ());
            if (cells.has(cell)) {
                cells.replace(cell, gills(side.getStepX(), side.getStepZ(), true));
                lit++;
            }
        }
    }

    /** {@code count} glowcaps round the foot, just outside its flare, where nothing else is. */
    static void glowcaps(GiantLayout.Builder cells, int count, IntUnaryOperator random) {
        int placed = 0;
        for (int tries = 0; placed < count && tries < count * 8; tries++) {
            double angle = between(random, 0, 2 * Math.PI);
            double distance = between(random, FOOT + 1, FOOT + 2.5);
            Cell cell = new Cell(
                    (int) Math.round(Math.cos(angle) * distance), 0, (int) Math.round(Math.sin(angle) * distance));
            if (!cells.has(cell)) {
                cells.putOptional(cell, new GiantPiece.Glowcap());
                placed++;
            }
        }
    }

    /**
     * A smaller parasol beside the first, just outside its flare and curving away from it, 50 to 70% as tall on a
     * slender stem, with a cap two cells narrower than the first's and at most {@link #TWIN_RADIUS}. Grown on the first
     * side of the first where it touches no part of it and keeps the whole at most {@link GiantLayout#MOST_ACROSS}
     * across; left out where no side does.
     */
    private static void twin(GiantLayout.Builder cells, Params params, IntUnaryOperator random) {
        int radius = Math.min(params.radius() - 2, TWIN_RADIUS);
        if (radius < 3) {
            return;
        }
        Direction away = params.drift().isEmpty()
                ? horizontal(random)
                : params.drift().getFirst().getOpposite();
        int height = (int) Math.round(params.stemHeight() * between(random, 0.5, 0.7));
        GiantLayout first = cells.build();
        // Away from the first's curve, then to either side of it, then toward it: the first way it fits.
        for (Direction way : List.of(away, away.getClockWise(), away.getCounterClockWise(), away.getOpposite())) {
            Params small = new Params(
                    params.stage(),
                    height,
                    radius,
                    List.of(way),
                    3,
                    params.tilt(),
                    params.dome() * 0.7,
                    params.lobes(),
                    params.lobeDepth(),
                    params.lobePhase() + 1,
                    List.of(),
                    1,
                    -1,
                    0,
                    0,
                    false);
            GiantLayout.Builder trial = new GiantLayout.Builder();
            Cell foot = new Cell((FOOT + 2) * way.getStepX(), 0, (FOOT + 2) * way.getStepZ());
            grow(trial, small, foot, 2, false);
            GiantLayout twin = trial.build();
            if (fits(cells, first, twin)) {
                twin.pieces().forEach((cell, piece) -> cells.put(cell, piece, 2));
                return;
            }
        }
    }

    /**
     * Whether {@code twin} grows beside {@code first}, which {@code cells} holds: it touches no part of it, and the two
     * are at most {@link GiantLayout#MOST_ACROSS} across.
     */
    private static boolean fits(GiantLayout.Builder cells, GiantLayout first, GiantLayout twin) {
        for (Cell cell : twin.pieces().keySet()) {
            if (!cells.freeFor(cell, 2)) {
                return false;
            }
        }
        int acrossX = Math.max(first.maxX(), twin.maxX()) - Math.min(first.minX(), twin.minX()) + 1;
        int acrossZ = Math.max(first.maxZ(), twin.maxZ()) - Math.min(first.minZ(), twin.minZ()) + 1;
        return acrossX <= GiantLayout.MOST_ACROSS && acrossZ <= GiantLayout.MOST_ACROSS;
    }
}
