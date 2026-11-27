package io.github.pkeppeler.deepcharter.surface;

import io.github.pkeppeler.deepcharter.surface.GreatStalkLayout.Cell;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import net.minecraft.core.Direction;

/**
 * The shape of a spire cluster (#1202): three to seven tapering fungal points rising from one dark root mound, splaying
 * outward and forking like antlers, each tip lit like a cold candle-flame. The real-world models are candlesnuff fungus
 * and dead man's fingers (<i>Xylaria</i>). The generator rolls {@link Params} from a seed and makes the blocks as a
 * {@link GiantLayout}, pure data.
 *
 * <p>Each spire thins from its girth at the mound to girth 1 under its tip, a cell at a time, and leans away from the
 * middle of the cluster, a cell every three or five rows. No two spires touch face to face above the mound, so breaking
 * one leaves the others standing.
 */
public final class SpireShape {
    /** Rows {@link #shorter} takes off every spire. */
    private static final int SHORTEN_ROWS = 3;

    public static final int LEAST_COUNT = 3;
    public static final int MOST_COUNT = 7;
    /** The tallest spire of a cluster with room for it. */
    public static final int LEAST_TALLEST = 10;

    public static final int MOST_TALLEST = 26;
    /** The shortest a spire cluster can be at all: under this much room a site grows nothing. */
    public static final int LEAST_ROOM = 5;

    public static final int MOST_CLUSTER_RADIUS = 4;
    /** The rows a spire takes to lean a cell at the most: one cell in three rows, about 18 degrees. */
    public static final int STEEPEST_LEAN = 3;

    private SpireShape() {}

    /**
     * One spire of a cluster.
     *
     * @param x      its foot, across from the mound's middle
     * @param z      its foot, along
     * @param height its rows above the mound, the tip's included
     * @param girth  its girth at the mound, 1 to 4
     * @param lean   the rows it takes to lean a cell (3 or 5), or 0 for a spire that stands straight
     * @param leanAt the row its lean starts on
     * @param leans  how many cells it leans
     * @param way    the way it leans: away from the middle of the cluster
     * @param forks  the rows its forks spring from, each at 40 to 75% of its height
     */
    public record Spike(
            int x, int z, int height, int girth, int lean, int leanAt, int leans, Direction way, List<Integer> forks) {
        public Spike {
            forks = List.copyOf(forks);
        }
    }

    /**
     * A rolled cluster: its spires, the tallest first, how many rows of each tip glow, and how many glowcaps grow at
     * the mound.
     *
     * @param glowRows 1 to 3
     * @param glowcaps 1 to 3
     */
    public record Params(List<Spike> spikes, int glowRows, int glowcaps) {
        public Params {
            spikes = List.copyOf(spikes);
        }
    }

    /**
     * A cluster rolled from {@code random} for a site with {@code room} rows of air over the mound; empty under {@link
     * #LEAST_ROOM}. Every spire stops two rows under the roof, and a low roof grows a squat cluster of three or four.
     */
    public static Optional<Params> roll(IntUnaryOperator random, int room) {
        int most = room - 2;
        if (most < LEAST_ROOM) {
            return Optional.empty();
        }
        int tallest = Math.min(LEAST_TALLEST + random.applyAsInt(MOST_TALLEST - LEAST_TALLEST + 1), most);
        int count = tallest < LEAST_TALLEST
                ? LEAST_COUNT + random.applyAsInt(2)
                : LEAST_COUNT + random.applyAsInt(MOST_COUNT - LEAST_COUNT + 1);
        int clusterRadius = 2 + random.applyAsInt(MOST_CLUSTER_RADIUS - 1);
        double splay = 20 * ParasolShape.unit(random);
        List<Spike> spikes = new ArrayList<>();
        Set<Cell> feet = new HashSet<>();
        double turn = 2 * Math.PI * ParasolShape.unit(random);
        for (int i = 0; i < count; i++) {
            int x = 0;
            int z = 0;
            if (i > 0) {
                boolean found = false;
                for (int tries = 0; tries < 12 && !found; tries++) {
                    double angle = turn + 2 * Math.PI * (i - 1) / (count - 1) + (ParasolShape.unit(random) - 0.5) * 0.8;
                    double distance = 1.5 + (clusterRadius - 1.5) * ParasolShape.unit(random);
                    x = (int) Math.round(Math.cos(angle) * distance);
                    z = (int) Math.round(Math.sin(angle) * distance);
                    found = footFree(feet, x, z);
                }
                if (!found) {
                    continue;
                }
            }
            feet.add(new Cell(x, 0, z));
            int height = i == 0
                    ? tallest
                    : Math.max(3, (int) Math.round(tallest * (0.35 + 0.55 * ParasolShape.unit(random))));
            int girth = i == 0 ? 3 + random.applyAsInt(2) : 2 + random.applyAsInt(2);
            girth = Math.min(girth, Math.max(1, height / 3));
            Direction way = i == 0 || (x == 0 && z == 0)
                    ? ParasolShape.horizontal(random)
                    : Direction.getApproximateNearest(x, 0, z);
            // Each spire splays its own amount, five degrees either side of the cluster's.
            double angle = Math.clamp(splay + (ParasolShape.unit(random) - 0.5) * 10, 0, 25);
            int lean = angle < 8 ? 0 : angle < 15 ? 5 : STEEPEST_LEAN;
            int leanAt = 1 + random.applyAsInt(3);
            int leans = lean == 0 ? 0 : Math.max(1, Math.min(2, (height - leanAt - 2) / lean));
            if (lean != 0 && leanAt + leans * lean > height - 2) {
                lean = 0;
                leans = 0;
            }
            List<Integer> forks = new ArrayList<>();
            int forkCount = height >= 8 ? random.applyAsInt(3) : 0;
            for (int f = 0; f < forkCount; f++) {
                int row = (int) Math.round(height * (0.4 + 0.35 * ParasolShape.unit(random)));
                if (!forks.contains(row) && !forks.contains(row - 1) && !forks.contains(row + 1)) {
                    forks.add(row);
                }
            }
            spikes.add(new Spike(x, z, height, girth, lean, leanAt, leans, way, forks));
        }
        int glowRows = 1 + random.applyAsInt(3);
        int glowcaps = GiantFungusTuning.DEFAULT.spireGlowcaps() + random.applyAsInt(3);
        return Optional.of(new Params(spikes, glowRows, glowcaps));
    }

    /** Whether a foot at {@code x}, {@code z} is apart from every other: not on one, and touching none face to face. */
    private static boolean footFree(Set<Cell> feet, int x, int z) {
        for (Cell foot : feet) {
            if (Math.abs(foot.x() - x) + Math.abs(foot.z() - z) <= 1) {
                return false;
            }
        }
        return true;
    }

    /**
     * The same cluster {@link #SHORTEN_ROWS} rows shorter in every spire, for a site whose air does not have room for
     * it; empty when the tallest would be too short.
     */
    public static Optional<Params> shorter(Params params) {
        List<Spike> spikes = new ArrayList<>();
        for (Spike spike : params.spikes()) {
            int height = spike.height() - SHORTEN_ROWS;
            if (height < 3) {
                if (spikes.isEmpty()) {
                    return Optional.empty();
                }
                continue;
            }
            boolean leanFits = spike.lean() != 0 && spike.leanAt() + spike.leans() * spike.lean() <= height - 2;
            spikes.add(new Spike(
                    spike.x(),
                    spike.z(),
                    height,
                    Math.min(spike.girth(), Math.max(1, height / 3)),
                    leanFits ? spike.lean() : 0,
                    spike.leanAt(),
                    leanFits ? spike.leans() : 0,
                    spike.way(),
                    spike.forks().stream().filter(row -> row < height - 2).toList()));
        }
        return Optional.of(new Params(spikes, params.glowRows(), params.glowcaps()));
    }

    /** One cell of a rod's path, with the row of the rod it is on. */
    private record Step(Cell cell, RodPiece piece, Direction way, int row) {}

    /**
     * The cells of a rod from {@code foot}, {@code height} rows: {@code leans} cells toward {@code way} from row {@code
     * leanAt}, each over {@code lean} rows, or straight where {@code lean} is 0.
     */
    private static List<Step> path(Cell foot, int height, int lean, int leanAt, int leans, Direction way) {
        List<Step> steps = new ArrayList<>();
        Cell at = foot;
        int row = 0;
        int leanEnd = leanAt + lean * leans;
        while (row < height) {
            if (lean != 0 && row >= leanAt && row < leanEnd) {
                for (RodPiece piece : RodPiece.lean(lean)) {
                    steps.add(new Step(at, piece, way, row));
                    if (piece.stepsAside()) {
                        at = at.offset(way.getStepX(), 0, way.getStepZ());
                    } else {
                        at = at.offset(0, 1, 0);
                        row++;
                    }
                }
                continue;
            }
            steps.add(new Step(at, RodPiece.STRAIGHT, way, row));
            at = at.offset(0, 1, 0);
            row++;
        }
        return steps;
    }

    /** The girth of a branch that forks off a rod of {@code girth}. {@code tools/fungus/make.py} draws the same. */
    public static int branchGirth(int girth) {
        return Math.clamp(girth - 1, 1, 2);
    }

    /** The layout of a rolled cluster, the mound's middle at the origin; {@code random} picks where its glowcaps go. */
    public static GiantLayout layout(Params params, IntUnaryOperator random) {
        GiantLayout.Builder cells = new GiantLayout.Builder();
        mound(cells, params);
        int owner = 1;
        boolean lit = true;
        for (Spike spike : params.spikes()) {
            List<Step> rod = path(
                    new Cell(spike.x(), 1, spike.z()),
                    spike.height(),
                    spike.lean(),
                    spike.leanAt(),
                    spike.leans(),
                    spike.way());
            List<Step> kept = fitting(cells, rod, owner);
            if (kept.isEmpty()) {
                continue;
            }
            // Forks first, so the rod's fork cells know which grew.
            List<Integer> forked = new ArrayList<>();
            List<List<Step>> branches = new ArrayList<>();
            Set<Cell> claimed = new HashSet<>();
            kept.forEach(step -> claimed.add(step.cell()));
            int rows = kept.getLast().row() + 1;
            for (int forkRow : spike.forks()) {
                Optional<Step> at = kept.stream()
                        .filter(step -> step.row() == forkRow && step.piece() == RodPiece.STRAIGHT)
                        .findFirst();
                if (at.isEmpty() || forkRow >= rows - 3) {
                    continue;
                }
                Optional<List<Step>> branch = branch(cells, claimed, at.get(), spike, rows - forkRow, owner, random);
                if (branch.isPresent()) {
                    forked.add(forkRow);
                    branches.add(branch.get());
                    branch.get().forEach(step -> claimed.add(step.cell()));
                }
            }
            rod(cells, kept, spike.girth(), params.glowRows(), lit, forked, owner);
            for (List<Step> branch : branches) {
                rod(cells, branch, branchGirth(spike.girth()), params.glowRows(), false, List.of(), owner);
            }
            lit = false;
            owner++;
        }
        ParasolShape.glowcaps(cells, params.glowcaps(), random);
        return cells.build();
    }

    /**
     * The steps of {@code rod} that part {@code owner} can take, up to the first that touches another part; empty if
     * under three rows.
     */
    private static List<Step> fitting(GiantLayout.Builder cells, List<Step> rod, int owner) {
        int end = 0;
        while (end < rod.size() && cells.freeFor(rod.get(end).cell(), owner)) {
            end++;
        }
        // A rod never ends on the lower half of a split row: its tip goes on the cell beside it.
        while (end > 0 && rod.get(end - 1).piece().stepsAside()) {
            end--;
        }
        List<Step> kept = rod.subList(0, end);
        if (kept.isEmpty() || kept.getLast().row() < 2) {
            return List.of();
        }
        return List.copyOf(kept);
    }

    /**
     * A branch forking off {@code from}: its first half in the rod's cell, its second in the cell beside, then up as a
     * thinner spire, leaning the same way, for a third to a half of the rows above. It forks out of the cluster: the
     * spire's own way, or a side. It keeps off the cells already {@code claimed} by its spire's rod and other branches,
     * face to face, but where it springs from the rod.
     */
    private static Optional<List<Step>> branch(
            GiantLayout.Builder cells,
            Set<Cell> claimed,
            Step from,
            Spike spike,
            int above,
            int owner,
            IntUnaryOperator random) {
        List<Direction> ways = new ArrayList<>(
                List.of(spike.way(), spike.way().getClockWise(), spike.way().getCounterClockWise()));
        while (!ways.isEmpty()) {
            Direction way = ways.remove(random.applyAsInt(ways.size()));
            Cell beside = from.cell().offset(way.getStepX(), 0, way.getStepZ());
            int height = Math.max(3, (int) Math.round(above * (0.33 + 0.17 * ParasolShape.unit(random))));
            List<Step> branch = new ArrayList<>();
            branch.add(new Step(beside, RodPiece.S1_0HI, way, 0));
            int lean = random.applyAsInt(2) == 0 ? STEEPEST_LEAN : 0;
            for (Step step : path(beside.offset(0, 1, 0), height - 1, lean, 1, lean == 0 ? 0 : 1, way)) {
                branch.add(new Step(step.cell(), step.piece(), step.way(), step.row() + 1));
            }
            boolean clear = true;
            for (Step step : branch) {
                if (claimed.contains(step.cell())
                        || !cells.freeFor(step.cell(), owner)
                        || touchesRod(step.cell(), claimed, beside)) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                return Optional.of(branch);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether a branch cell other than its first touches its own spire's cells face to face: a branch that grew back
     * against its rod or another branch would join the two, so a break of either would bring both down.
     */
    private static boolean touchesRod(Cell cell, Set<Cell> rod, Cell first) {
        if (cell.equals(first)) {
            return false;
        }
        for (Direction side : Direction.values()) {
            if (rod.contains(cell.offset(side.getStepX(), side.getStepY(), side.getStepZ()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Puts a rod's cells: its girth thinning a cell at a time from {@code girth} at its foot to 1 under its glowing
     * rows, the last of which is its needle, lit if {@code lit}. A row in {@code forked} is a fork.
     */
    private static void rod(
            GiantLayout.Builder cells,
            List<Step> rod,
            int girth,
            int glowRows,
            boolean lit,
            List<Integer> forked,
            int owner) {
        int rows = rod.getLast().row() + 1;
        int glowFrom = Math.max(1, rows - glowRows);
        int current = girth;
        for (int i = 0; i < rod.size(); i++) {
            Step step = rod.get(i);
            boolean last = i == rod.size() - 1;
            if (last || step.row() >= glowFrom) {
                cells.put(step.cell(), new GiantPiece.Tip(step.piece(), step.way(), last, last && lit), owner);
                continue;
            }
            // The girth this row should reach by its top: 1 at the glowing rows, falling evenly from the foot.
            int target = girth - (int) Math.floor((double) (girth - 1) * (step.row() + 1) / glowFrom);
            boolean taper = target < current && current > 1 && step.piece().fullRow();
            RodPiece piece =
                    forked.contains(step.row()) && step.piece() == RodPiece.STRAIGHT ? RodPiece.FORK : step.piece();
            cells.put(step.cell(), new GiantPiece.Spire(current, piece, step.way(), taper), owner);
            if (taper) {
                current--;
            }
        }
    }

    /**
     * The root mound: a {@code spire_base} under the foot of each spire and along the line from the middle to it, the
     * rest of it optional, with a tall flare round its edge.
     */
    private static void mound(GiantLayout.Builder cells, Params params) {
        Set<Cell> mound = new HashSet<>();
        Set<Cell> feet = new HashSet<>();
        for (Spike spike : params.spikes()) {
            feet.add(new Cell(spike.x(), 0, spike.z()));
            int steps = Math.max(Math.abs(spike.x()), Math.abs(spike.z()));
            for (int s = 0; s <= steps; s++) {
                double t = steps == 0 ? 0 : (double) s / steps;
                mound.add(new Cell((int) Math.round(spike.x() * t), 0, (int) Math.round(spike.z() * t)));
            }
        }
        for (Cell cell : mound) {
            if (feet.contains(cell)) {
                cells.put(cell, new GiantPiece.SpireBase(), 0);
            }
        }
        for (Cell cell : mound) {
            cells.putOptional(cell, new GiantPiece.SpireBase());
        }
        for (Cell cell : mound) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                Cell next = cell.offset(side.getStepX(), 0, side.getStepZ());
                if (!mound.contains(next)) {
                    cells.putOptional(next, new GiantPiece.Flare(side.getOpposite(), false, true));
                }
            }
        }
        for (Cell cell : mound) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                Direction next = side.getClockWise();
                Cell corner = cell.offset(side.getStepX() + next.getStepX(), 0, side.getStepZ() + next.getStepZ());
                if (!mound.contains(corner)) {
                    cells.putOptional(corner, new GiantPiece.Flare(side.getOpposite(), true, true));
                }
            }
        }
    }
}
