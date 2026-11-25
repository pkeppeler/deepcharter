package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.surface.GiantFungus;
import io.github.pkeppeler.deepcharter.surface.GiantFungusFeature;
import io.github.pkeppeler.deepcharter.surface.GiantFungusFeature.Form;
import io.github.pkeppeler.deepcharter.surface.GiantLayout;
import io.github.pkeppeler.deepcharter.surface.GiantLayout.Fit;
import io.github.pkeppeler.deepcharter.surface.GiantPiece;
import io.github.pkeppeler.deepcharter.surface.GreatStalkLayout.Cell;
import io.github.pkeppeler.deepcharter.surface.ParasolShape;
import io.github.pkeppeler.deepcharter.surface.RodPiece;
import io.github.pkeppeler.deepcharter.surface.SpireShape;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Server GameTests for the giant fungi of layer 3 (#1202), as pure data and in a world made of nothing but a floor: the
 * parasol's and the spires' rolls stay in their ranges and differ from seed to seed, every layout holds together and
 * keeps its parts apart, a layout is fitted whole into its chunk by moving or mirroring it and never cut, a site
 * re-rolls a form its west or north neighbour picked, and each zone's mix is its weights. {@link FungalHollowsTest}
 * checks the giants the campaign world grows.
 */
public class GiantFungusTest {
    private static final int SEEDS = 400;
    /** Room for the tallest of everything: a 26-cell stem and its cap. */
    private static final int ROOM = 40;

    /** The top of the stone of {@link #floorWorld}, a world of no level's: no world Y. */
    private static final int GROUND = 64;

    private static final int CHUNKS = 4000;

    private static IntUnaryOperator random(long seed) {
        return RandomSource.create(seed)::nextInt;
    }

    private static ParasolShape.Params parasol(long seed) {
        return ParasolShape.roll(random(seed), ROOM, ParasolShape.Style.DEFAULT).orElseThrow();
    }

    private static GiantLayout parasolLayout(long seed) {
        return ParasolShape.layout(parasol(seed), random(seed + 1));
    }

    private static SpireShape.Params spires(long seed) {
        return SpireShape.roll(random(seed), ROOM).orElseThrow();
    }

    private static GiantLayout spireLayout(long seed) {
        return SpireShape.layout(spires(seed), random(seed + 1));
    }

    @GameTest
    public void aParasolsRollStaysInItsRanges(GameTestHelper helper) {
        Map<ParasolShape.Stage, Integer> stages = new EnumMap<>(ParasolShape.Stage.class);
        int[] radii = new int[ParasolShape.MOST_RADIUS + 1];
        int twins = 0;
        for (long seed = 0; seed < SEEDS; seed++) {
            ParasolShape.Params p = parasol(seed);
            stages.merge(p.stage(), 1, Integer::sum);
            radii[p.radius()]++;
            twins += p.twin() ? 1 : 0;
            expect(helper, p.stemHeight() >= 12 && p.stemHeight() <= 26, "seed %s: stem %s", seed, p.stemHeight());
            expect(helper, p.radius() >= 4 && p.radius() <= 7, "seed %s: radius %s", seed, p.radius());
            expect(helper, p.drift().size() <= 2, "seed %s: drift %s", seed, p.drift());
            expect(helper, p.tilt() >= 0 && p.tilt() <= 12, "seed %s: tilt %s", seed, p.tilt());
            expect(helper, p.lobes() >= 3 && p.lobes() <= 6, "seed %s: lobes %s", seed, p.lobes());
            expect(helper, p.lobeDepth() >= 0 && p.lobeDepth() <= 1, "seed %s: lobe depth %s", seed, p.lobeDepth());
            expect(
                    helper,
                    p.tears().size() <= 2,
                    "seed %s: %s tears",
                    seed,
                    p.tears().size());
            expect(helper, p.lamps() >= 0 && p.lamps() <= 2, "seed %s: %s lamps", seed, p.lamps());
            expect(helper, p.glowcaps() >= 3 && p.glowcaps() <= 5, "seed %s: %s glowcaps", seed, p.glowcaps());
            if (p.ringRow() >= 0) {
                expect(
                        helper,
                        p.ringRow() >= Math.ceil(p.stemHeight() * 0.55) && p.ringRow() <= p.stemHeight() * 0.75,
                        "seed %s: ring at %s of a %s stem",
                        seed,
                        p.ringRow(),
                        p.stemHeight());
            }
        }
        // Bell 20%, open 60%, old 20%; a ring 80%; a twin 15%.
        expect(helper, near(stages.getOrDefault(ParasolShape.Stage.OPEN, 0), 0.6), "open stages %s", stages);
        expect(helper, near(stages.getOrDefault(ParasolShape.Stage.BELL, 0), 0.2), "bells %s", stages);
        expect(helper, near(twins, 0.15), "%s twins", twins);
        expect(
                helper,
                radii[6] + radii[7] > radii[4] + radii[5],
                "radius 6 to 7 is not the norm: %s",
                Arrays.toString(radii));
        helper.succeed();
    }

    /** Whether {@code count} of {@link #SEEDS} is within 0.07 of {@code share}. */
    private static boolean near(int count, double share) {
        return Math.abs((double) count / SEEDS - share) < 0.07;
    }

    @GameTest
    public void aParasolsStemShrinksToItsRoomAndNoneGrowsUnderTwelve(GameTestHelper helper) {
        for (long seed = 0; seed < SEEDS; seed++) {
            Optional<ParasolShape.Params> squat = ParasolShape.roll(random(seed), 14, ParasolShape.Style.DEFAULT);
            expect(helper, squat.isPresent() && squat.get().stemHeight() <= 14, "seed %s: %s", seed, squat);
            expect(
                    helper,
                    ParasolShape.roll(random(seed), ParasolShape.LEAST_STEM - 1, ParasolShape.Style.DEFAULT)
                            .isEmpty(),
                    "seed %s grew a parasol under %s rows",
                    seed,
                    ParasolShape.LEAST_STEM);
        }
        helper.succeed();
    }

    @GameTest
    public void aSpireClustersRollStaysInItsRanges(GameTestHelper helper) {
        for (long seed = 0; seed < SEEDS; seed++) {
            SpireShape.Params p = spires(seed);
            int count = p.spikes().size();
            expect(helper, count >= 2 && count <= 7, "seed %s: %s spires", seed, count);
            SpireShape.Spike tallest = p.spikes().getFirst();
            expect(
                    helper,
                    tallest.height() >= 10 && tallest.height() <= 26,
                    "seed %s: tallest %s",
                    seed,
                    tallest.height());
            expect(
                    helper,
                    tallest.girth() >= 3 && tallest.girth() <= 4,
                    "seed %s: main girth %s",
                    seed,
                    tallest.girth());
            expect(helper, p.glowRows() >= 1 && p.glowRows() <= 3, "seed %s: %s glow rows", seed, p.glowRows());
            expect(helper, p.glowcaps() >= 1 && p.glowcaps() <= 3, "seed %s: %s glowcaps", seed, p.glowcaps());
            for (SpireShape.Spike spike : p.spikes()) {
                expect(
                        helper,
                        spike.height() >= 3 && spike.height() <= tallest.height(),
                        "seed %s: a spire of %s",
                        seed,
                        spike.height());
                if (spike != tallest) {
                    // 35 to 90% of the tallest, to the nearest row, and never under 3.
                    expect(
                            helper,
                            spike.height() <= Math.round(tallest.height() * 0.9)
                                    && spike.height() >= Math.min(3, Math.round(tallest.height() * 0.35)),
                            "seed %s: a spire of %s by a tallest of %s",
                            seed,
                            spike.height(),
                            tallest.height());
                    expect(helper, spike.girth() <= 3, "seed %s: a side spire of girth %s", seed, spike.girth());
                }
                expect(
                        helper,
                        Math.abs(spike.x()) <= SpireShape.MOST_CLUSTER_RADIUS
                                && Math.abs(spike.z()) <= SpireShape.MOST_CLUSTER_RADIUS,
                        "seed %s: a foot at %s, %s",
                        seed,
                        spike.x(),
                        spike.z());
                expect(
                        helper,
                        spike.forks().size() <= 2,
                        "seed %s: %s forks",
                        seed,
                        spike.forks().size());
                for (int fork : spike.forks()) {
                    double at = (double) fork / spike.height();
                    expect(helper, at >= 0.35 && at <= 0.8, "seed %s: a fork at %s of its spire", seed, at);
                }
                expect(
                        helper,
                        spike.lean() == 0 || spike.lean() == 3 || spike.lean() == 5,
                        "seed %s: a lean of %s",
                        seed,
                        spike.lean());
            }
        }
        expect(
                helper,
                SpireShape.roll(random(1), SpireShape.LEAST_ROOM + 1).isEmpty(),
                "a spire cluster grew under its least room");
        helper.succeed();
    }

    /** Six seeds of each form make six different giants, and a hundred make a hundred. */
    @GameTest
    public void everySiteVaries(GameTestHelper helper) {
        Set<Map<Cell, GiantPiece>> parasols = new HashSet<>();
        Set<Map<Cell, GiantPiece>> spireSets = new HashSet<>();
        Set<ParasolShape.Stage> stages = new HashSet<>();
        Set<Integer> counts = new HashSet<>();
        for (long seed = 0; seed < 100; seed++) {
            parasols.add(parasolLayout(seed).pieces());
            spireSets.add(spireLayout(seed).pieces());
            if (seed < 6) {
                stages.add(parasol(seed).stage());
                counts.add(spires(seed).spikes().size());
            }
        }
        expect(helper, parasols.size() == 100, "100 seeds grew %s different parasols", parasols.size());
        expect(helper, spireSets.size() == 100, "100 seeds grew %s different spire clusters", spireSets.size());
        expect(helper, stages.size() >= 2, "six parasols are all %s", stages);
        expect(helper, counts.size() >= 2, "six spire clusters all have %s spires", counts);
        helper.succeed();
    }

    /**
     * Every cell a giant needs hangs together, face to face, from its feet on the floor, so nothing floats; and no two
     * parts (stems, spires) touch above the floor, so breaking one never brings another down.
     */
    @GameTest
    public void everyLayoutHoldsTogetherAndKeepsItsPartsApart(GameTestHelper helper) {
        for (long seed = 0; seed < SEEDS; seed++) {
            for (GiantLayout layout : List.of(parasolLayout(seed), spireLayout(seed))) {
                Set<Cell> needed = new HashSet<>(layout.pieces().keySet());
                needed.removeAll(layout.optional());
                Deque<Cell> queue = new ArrayDeque<>();
                Set<Cell> reached = new HashSet<>();
                needed.stream().filter(cell -> cell.y() == 0).forEach(cell -> {
                    reached.add(cell);
                    queue.add(cell);
                });
                while (!queue.isEmpty()) {
                    Cell at = queue.poll();
                    for (Direction side : Direction.values()) {
                        Cell next =
                                new Cell(at.x() + side.getStepX(), at.y() + side.getStepY(), at.z() + side.getStepZ());
                        if (needed.contains(next) && reached.add(next)) {
                            queue.add(next);
                        }
                    }
                }
                needed.removeAll(reached);
                expect(helper, needed.isEmpty(), "seed %s: %s cells float, as %s", seed, needed.size(), needed);
                for (Cell cell : layout.pieces().keySet()) {
                    int owner = layout.owners().getOrDefault(cell, 0);
                    if (owner == 0 || layout.optional().contains(cell)) {
                        continue;
                    }
                    for (Direction side : Direction.values()) {
                        Cell next = new Cell(
                                cell.x() + side.getStepX(), cell.y() + side.getStepY(), cell.z() + side.getStepZ());
                        int other = layout.owners().getOrDefault(next, 0);
                        expect(
                                helper,
                                !layout.pieces().containsKey(next) || other == 0 || other == owner,
                                "seed %s: parts %s and %s touch at %s",
                                seed,
                                owner,
                                other,
                                cell);
                    }
                }
            }
        }
        helper.succeed();
    }

    /**
     * A spire ends in a glowing needle, and the tallest spire's needle is lit: one real light at the top of a cluster.
     */
    @GameTest
    public void everySpireEndsInANeedleAndTheTallestIsLit(GameTestHelper helper) {
        for (long seed = 0; seed < SEEDS; seed++) {
            GiantLayout layout = spireLayout(seed);
            Map<Integer, Integer> needles = new HashMap<>();
            int lit = 0;
            for (Map.Entry<Cell, GiantPiece> entry : layout.pieces().entrySet()) {
                if (entry.getValue() instanceof GiantPiece.Tip tip && tip.needle()) {
                    needles.merge(layout.owners().get(entry.getKey()), 1, Integer::sum);
                    lit += tip.lit() ? 1 : 0;
                }
            }
            Set<Integer> owners = new HashSet<>(layout.owners().values());
            owners.remove(0);
            expect(
                    helper,
                    needles.keySet().equals(owners),
                    "seed %s: spires %s have needles %s",
                    seed,
                    owners,
                    needles);
            expect(helper, lit == 1, "seed %s: %s lit needles", seed, lit);
        }
        helper.succeed();
    }

    /**
     * A parasol's cap slopes: its top cells rise and fall across them (in every cap, a slope of 4 pixels or more
     * somewhere, and in most a dome that falls both ways), its edge is rim, and gills are under it. An old cap's rim is
     * turned up.
     */
    @GameTest
    public void aParasolsCapSlopesAndIsRimmed(GameTestHelper helper) {
        int domes = 0;
        for (long seed = 0; seed < SEEDS; seed++) {
            GiantLayout layout = parasolLayout(seed);
            ParasolShape.Stage stage = parasol(seed).stage();
            Set<Integer> rises = new HashSet<>();
            int rims = 0;
            int gills = 0;
            for (GiantPiece piece : layout.pieces().values()) {
                if (piece instanceof GiantPiece.Cap cap) {
                    rises.add(cap.riseX());
                    rises.add(cap.riseZ());
                }
                if (piece instanceof GiantPiece.Rim rim) {
                    rims++;
                    expect(
                            helper,
                            (rim.lip() == GiantPiece.Lip.UP) == (stage == ParasolShape.Stage.OLD),
                            "seed %s: a %s cap with a %s lip",
                            seed,
                            stage,
                            rim.lip());
                }
                gills += piece instanceof GiantPiece.Gills ? 1 : 0;
            }
            expect(helper, rises.size() >= 2, "seed %s: the %s cap's top is flat", seed, stage);
            domes += rises.size() >= 3 ? 1 : 0;
            expect(helper, rims >= 12, "seed %s: %s rim cells", seed, rims);
            expect(helper, gills >= 9, "seed %s: %s gill cells", seed, gills);
        }
        expect(helper, domes > SEEDS * 0.95, "only %s of %s caps fall both ways", domes, SEEDS);
        helper.succeed();
    }

    /**
     * Every column under a parasol's cap is gills from below (#1218): its lowest cell is a gill block, or a rim, which
     * draws gills under its lip. The gills glow in their model, so the whole underside reads in a dark cave.
     */
    @GameTest
    public void aParasolsWholeUndersideIsGills(GameTestHelper helper) {
        for (long seed = 0; seed < SEEDS; seed++) {
            ParasolShape.Params p = parasol(seed);
            GiantLayout layout = ParasolShape.layout(p, random(seed + 1));
            Map<Long, Cell> lowest = new HashMap<>();
            layout.pieces().forEach((cell, piece) -> {
                boolean capPart = piece instanceof GiantPiece.Gills
                        || piece instanceof GiantPiece.Cap
                        || piece instanceof GiantPiece.Rim;
                if (capPart && layout.owners().getOrDefault(cell, 0) == 1) {
                    lowest.merge(column(cell), cell, (a, b) -> a.y() <= b.y() ? a : b);
                }
            });
            int gills = 0;
            for (Cell cell : lowest.values()) {
                GiantPiece piece = layout.pieces().get(cell);
                gills += piece instanceof GiantPiece.Gills || piece instanceof GiantPiece.Rim ? 1 : 0;
            }
            expect(
                    helper,
                    gills == lowest.size() && !lowest.isEmpty(),
                    "seed %s: %s of the cap's %s columns are gills from below",
                    seed,
                    gills,
                    lowest.size());
        }
        helper.succeed();
    }

    private static long column(Cell cell) {
        return ((long) cell.x() << 32) ^ (cell.z() & 0xFFFFFFFFL);
    }

    /**
     * A parasol's stem is thick (#1218), not a stick: every row of it over the foot is at least three cells across
     * both ways, a core with an arm on each side. Its foot flares: every cell within two of the foot, on the floor, is
     * stem or flare. Two of the largest parasols and a great stalk on them (its yield, 90) still fall in one collapse,
     * which lowers at most 2048 blocks ({@code StalkCollapse.MOST_BLOCKS}).
     */
    @GameTest
    public void aParasolsStemIsThickAndFlaresAtItsFoot(GameTestHelper helper) {
        int largest = 0;
        for (String zone : List.of("garden", "spore_fields", "quiet")) {
            ParasolShape.Style style = zoneFeature(helper, zone).parasol();
            for (long seed = 0; seed < SEEDS; seed++) {
                ParasolShape.Params p =
                        ParasolShape.roll(random(seed), ROOM, style).orElseThrow();
                GiantLayout layout = ParasolShape.layout(p, random(seed + 1));
                largest = Math.max(largest, layout.pieces().size());
                Map<Integer, List<Cell>> rows = new HashMap<>();
                layout.pieces().forEach((cell, piece) -> {
                    boolean stem = piece instanceof GiantPiece.Stem || piece instanceof GiantPiece.Ring;
                    if (stem && layout.owners().getOrDefault(cell, 0) == 1) {
                        rows.computeIfAbsent(cell.y(), y -> new ArrayList<>()).add(cell);
                    }
                });
                for (int y = 1; y < p.stemHeight(); y++) {
                    List<Cell> row = rows.getOrDefault(y, List.of());
                    int acrossX = row.stream().mapToInt(Cell::x).max().orElse(0)
                            - row.stream().mapToInt(Cell::x).min().orElse(0)
                            + 1;
                    int acrossZ = row.stream().mapToInt(Cell::z).max().orElse(0)
                            - row.stream().mapToInt(Cell::z).min().orElse(0)
                            + 1;
                    expect(
                            helper,
                            !row.isEmpty() && acrossX >= 3 && acrossZ >= 3,
                            "%s seed %s: stem row %s is %s by %s cells",
                            zone,
                            seed,
                            y,
                            acrossX,
                            acrossZ);
                }
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        GiantPiece piece = layout.pieces().get(new Cell(dx, 0, dz));
                        expect(
                                helper,
                                piece instanceof GiantPiece.Stem || piece instanceof GiantPiece.Flare,
                                "%s seed %s: the foot has %s at %s, %s",
                                zone,
                                seed,
                                piece,
                                dx,
                                dz);
                    }
                }
            }
        }
        expect(helper, 2 * largest + 90 < 2048, "the largest parasol is %s blocks", largest);
        helper.succeed();
    }

    /**
     * A parasol's cap is flat and wide (#1218), a parasol and not a round blob: its height over the bottom of its gills
     * is at most a third of its width, a fifth on average, and its radius is at least 5.5 cells on average.
     */
    @GameTest
    public void aParasolsCapIsFlatAndWide(GameTestHelper helper) {
        int radii = 0;
        double ratios = 0;
        for (long seed = 0; seed < SEEDS; seed++) {
            ParasolShape.Params p = parasol(seed);
            radii += p.radius();
            GiantLayout layout = ParasolShape.layout(p, random(seed + 1));
            int gillRow = p.stemHeight();
            int height = 0;
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (Map.Entry<Cell, GiantPiece> entry : layout.pieces().entrySet()) {
                Cell cell = entry.getKey();
                if (layout.owners().getOrDefault(cell, 0) != 1 || cell.y() < gillRow) {
                    continue;
                }
                switch (entry.getValue()) {
                    case GiantPiece.Cap cap -> height = Math.max(height, (cell.y() - gillRow) * 16 + cap.level());
                    case GiantPiece.Rim rim -> height = Math.max(height, rim.level());
                    default -> {}
                }
                if (cell.y() == gillRow) {
                    minX = Math.min(minX, cell.x());
                    maxX = Math.max(maxX, cell.x());
                    minZ = Math.min(minZ, cell.z());
                    maxZ = Math.max(maxZ, cell.z());
                }
            }
            int across = Math.max(maxX - minX + 1, maxZ - minZ + 1);
            double ratio = height / (16.0 * across);
            expect(
                    helper,
                    ratio <= 1 / 3.0,
                    "seed %s: a %s cap %s pixels high and %s cells across, %s",
                    seed,
                    p.stage(),
                    height,
                    across,
                    ratio);
            ratios += ratio;
        }
        // Before #1218 the caps rose to 0.54 of their width, 0.27 on average, at 5.3 cells of radius.
        expect(helper, ratios / SEEDS <= 0.2, "caps rise %s of their width on average", ratios / SEEDS);
        expect(helper, (double) radii / SEEDS >= 5.5, "the mean radius is %s", (double) radii / SEEDS);
        helper.succeed();
    }

    /**
     * Every layout fits whole into its chunk wherever its site wants it, even against a side or a corner: moved or
     * mirrored, every cell in the chunk, never one cut (ADR 0088). A parasol whose stem curves toward the side it
     * stands at curves back toward the middle.
     */
    @GameTest
    public void everyGiantFitsWholeInItsChunkEvenAtItsEdge(GameTestHelper helper) {
        int mirrored = 0;
        for (long seed = 0; seed < SEEDS; seed++) {
            for (GiantLayout layout : List.of(parasolLayout(seed), spireLayout(seed))) {
                for (int want : new int[] {0, 1, 7, 14, 15}) {
                    Optional<Fit> fit = layout.fitInChunk(want, 15 - want);
                    expect(
                            helper,
                            fit.isPresent(),
                            "seed %s did not fit a chunk: x %s..%s",
                            seed,
                            layout.minX(),
                            layout.maxX());
                    GiantLayout fitted = fit.get().layout();
                    expect(
                            helper,
                            fitted.pieces().size() == layout.pieces().size(),
                            "seed %s: %s cells fitted of %s",
                            seed,
                            fitted.pieces().size(),
                            layout.pieces().size());
                    for (Cell cell : fitted.pieces().keySet()) {
                        int x = fit.get().localX(cell);
                        int z = fit.get().localZ(cell);
                        expect(helper, x >= 0 && x <= 15 && z >= 0 && z <= 15, "seed %s: a cell at %s, %s", seed, x, z);
                    }
                    if (!fitted.equals(layout)) {
                        mirrored++;
                    }
                }
            }
        }
        // A parasol curving east, wanted at the east side: mirrored to curve west, its foot where it was wanted or
        // nearer.
        for (long seed = 0; seed < SEEDS; seed++) {
            ParasolShape.Params p = parasol(seed);
            if (p.drift().size() == 2 && p.drift().stream().allMatch(way -> way == Direction.EAST) && !p.twin()) {
                GiantLayout layout = ParasolShape.layout(p, random(seed + 1));
                Fit fit = layout.fitInChunk(15 - p.radius() + 1, 8).orElseThrow();
                long east = fit.layout().pieces().values().stream()
                        .filter(piece -> piece instanceof GiantPiece.Stem stem && stem.piece() != RodPiece.STRAIGHT)
                        .filter(piece -> ((GiantPiece.Stem) piece).facing() == Direction.WEST)
                        .count();
                expect(helper, east > 0, "seed %s: a parasol curving into the chunk's east side was not turned", seed);
                break;
            }
        }
        expect(helper, mirrored > 0, "no layout was ever mirrored to fit");
        helper.succeed();
    }

    /** The shipped giant fungus feature of the zone named {@code zone}. */
    private static GiantFungusFeature zoneFeature(GameTestHelper helper, String zone) {
        return (GiantFungusFeature) helper.getLevel()
                .registryAccess()
                .lookupOrThrow(Registries.FEATURE)
                .getValueOrThrow(ResourceKey.create(
                        Registries.FEATURE,
                        Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "giant_fungus_" + zone)));
    }

    private static void expectAtMostAcross(GameTestHelper helper, GiantLayout layout, String what) {
        int acrossX = layout.maxX() - layout.minX() + 1;
        int acrossZ = layout.maxZ() - layout.minZ() + 1;
        expect(
                helper,
                acrossX <= GiantLayout.MOST_ACROSS && acrossZ <= GiantLayout.MOST_ACROSS,
                "%s is %s by %s cells across",
                what,
                acrossX,
                acrossZ);
    }

    /**
     * A giant is at most 15 cells across in x and in z: every layout either form rolls, in every zone's style, is no
     * wider, and a layout one cell wider does not fit a chunk.
     */
    @GameTest
    public void everyGiantIsAtMostFifteenAcross(GameTestHelper helper) {
        for (String zone : List.of("garden", "spore_fields", "quiet")) {
            ParasolShape.Style style = zoneFeature(helper, zone).parasol();
            for (long seed = 0; seed < SEEDS; seed++) {
                ParasolShape.Params params =
                        ParasolShape.roll(random(seed), ROOM, style).orElseThrow();
                expectAtMostAcross(
                        helper, ParasolShape.layout(params, random(seed + 1)), zone + " parasol seed " + seed);
            }
        }
        for (long seed = 0; seed < SEEDS; seed++) {
            expectAtMostAcross(helper, spireLayout(seed), "spire cluster seed " + seed);
        }
        for (int across : new int[] {GiantLayout.MOST_ACROSS, GiantLayout.MOST_ACROSS + 1}) {
            Cell far = new Cell(across - 1, 0, 0);
            GiantLayout wide = new GiantLayout(
                    Map.of(new Cell(0, 0, 0), new GiantPiece.SpireBase(), far, new GiantPiece.SpireBase()),
                    Map.of(new Cell(0, 0, 0), 0, far, 0),
                    Set.of());
            boolean fits = wide.fitInChunk(8, 8).isPresent();
            expect(
                    helper,
                    fits == (across <= GiantLayout.MOST_ACROSS),
                    "a layout %s cells across %s a chunk",
                    across,
                    fits ? "fits" : "does not fit");
        }
        helper.succeed();
    }

    /** A mirror turns each piece that faces a way, and twice over is the piece itself. */
    @GameTest
    public void aMirrorTurnsEachPieceAndTwiceIsItself(GameTestHelper helper) {
        for (long seed = 0; seed < 40; seed++) {
            for (GiantLayout layout : List.of(parasolLayout(seed), spireLayout(seed))) {
                for (Direction.Axis axis : List.of(Direction.Axis.X, Direction.Axis.Z)) {
                    expect(
                            helper,
                            layout.mirrored(axis).mirrored(axis).equals(layout),
                            "seed %s: mirrored twice",
                            seed);
                }
            }
        }
        GiantPiece.Rim corner = new GiantPiece.Rim(Direction.EAST, true, 16, GiantPiece.Lip.DOWN);
        // The corner between east and south, mirrored across x, is the one between south and west.
        expect(
                helper,
                corner.mirrored(Direction.Axis.X)
                        .equals(new GiantPiece.Rim(Direction.SOUTH, true, 16, GiantPiece.Lip.DOWN)),
                "the east-south corner mirrored across x is %s",
                corner.mirrored(Direction.Axis.X));
        expect(
                helper,
                new GiantPiece.Cap(8, 4, -8, false)
                        .mirrored(Direction.Axis.X)
                        .equals(new GiantPiece.Cap(8, -4, -8, false)),
                "a cap's rise across x does not turn");
        helper.succeed();
    }

    /**
     * A world that is nothing but stone up to {@link #GROUND} and air over it, which records every block set in it and
     * keeps none: a giant placed in it reads the same world whatever it wrote.
     */
    private static LevelAccessor floorWorld(Map<BlockPos, BlockState> written) {
        return (LevelAccessor) Proxy.newProxyInstance(
                LevelAccessor.class.getClassLoader(),
                new Class<?>[] {LevelAccessor.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBlockState" ->
                        ((BlockPos) args[0]).getY() < GROUND
                                ? Blocks.STONE.defaultBlockState()
                                : written.getOrDefault((BlockPos) args[0], Blocks.AIR.defaultBlockState());
                    case "setBlock" -> {
                        written.put(((BlockPos) args[0]).immutable(), (BlockState) args[1]);
                        yield true;
                    }
                    case "isStateAtPosition" -> false;
                    default -> throw new UnsupportedOperationException("the floor world has no " + method.getName());
                });
    }

    /**
     * A giant grown at a chunk's side or corner, on a flat floor, writes every cell it needs and nothing outside its
     * chunk: the whole of it, fitted, not the part that was in the chunk.
     */
    @GameTest
    public void aGiantGrownAtAChunksEdgeIsWholeAndInItsChunk(GameTestHelper helper) {
        ChunkPos chunk = new ChunkPos(3, -2);
        for (long seed = 0; seed < 60; seed++) {
            for (GiantLayout layout : List.of(parasolLayout(seed), spireLayout(seed))) {
                for (int want : new int[] {0, 15}) {
                    Map<BlockPos, BlockState> written = new LinkedHashMap<>();
                    Fit fit = layout.fitInChunk(want, want).orElseThrow();
                    expect(
                            helper,
                            GiantFungus.place(floorWorld(written), chunk, fit, GROUND),
                            "seed %s did not grow on a flat floor",
                            seed);
                    for (BlockPos pos : written.keySet()) {
                        expect(
                                helper,
                                ChunkPos.containing(pos).equals(chunk),
                                "seed %s wrote %s, outside chunk %s",
                                seed,
                                pos.toShortString(),
                                chunk);
                    }
                    long needed =
                            (long) layout.pieces().size() - layout.optional().size();
                    long grown = fit.layout().pieces().keySet().stream()
                            .filter(cell -> !fit.layout().optional().contains(cell))
                            .filter(cell -> written.containsKey(GiantFungus.at(chunk, fit, GROUND, cell)))
                            .count();
                    expect(helper, grown == needed, "seed %s grew %s of its %s cells", seed, grown, needed);
                }
            }
        }
        helper.succeed();
    }

    /** A giant grows over nothing it may not: a block of stone in its way, and it does not grow at all. */
    @GameTest
    public void aGiantNeedsRoomForEveryCell(GameTestHelper helper) {
        ChunkPos chunk = new ChunkPos(0, 0);
        GiantLayout layout = parasolLayout(7);
        Fit fit = layout.fitInChunk(8, 8).orElseThrow();
        Cell high = fit.layout().pieces().keySet().stream()
                .filter(cell -> fit.layout().pieces().get(cell) instanceof GiantPiece.Rim)
                .findFirst()
                .orElseThrow();
        Map<BlockPos, BlockState> written = new LinkedHashMap<>();
        written.put(GiantFungus.at(chunk, fit, GROUND, high), Blocks.STONE.defaultBlockState());
        int before = written.size();
        expect(helper, !GiantFungus.place(floorWorld(written), chunk, fit, GROUND), "a parasol grew through stone");
        expect(
                helper,
                written.size() == before,
                "a parasol that could not grow wrote %s blocks",
                written.size() - before);
        helper.succeed();
    }

    /**
     * How far above the test a giant is grown for a collapse, off every neighbour, and how long a collapse may take.
     */
    private static final int SKY = 60;

    private static final int COLLAPSE_TICKS = 300;

    /** Grows {@code layout} with its base at {@code base} on a stone pad, every cell set as the placer sets it. */
    private static void grow(ServerLevel level, GiantLayout layout, BlockPos base) {
        for (int x = layout.minX() - 1; x <= layout.maxX() + 1; x++) {
            for (int z = layout.minZ() - 1; z <= layout.maxZ() + 1; z++) {
                level.setBlock(base.offset(x, -1, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        layout.pieces()
                .forEach((cell, piece) -> level.setBlock(
                        base.offset(cell.x(), cell.y(), cell.z()), GiantFungus.state(piece), Block.UPDATE_CLIENTS));
    }

    private static void clear(ServerLevel level, GiantLayout layout, BlockPos base) {
        for (int x = layout.minX() - 1; x <= layout.maxX() + 1; x++) {
            for (int z = layout.minZ() - 1; z <= layout.maxZ() + 1; z++) {
                for (int y = -1; y <= layout.maxY() + 1; y++) {
                    level.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /**
     * A giant falls as a great stalk does ({@code StalkCollapse}): break the core of a parasol's thick stem low down
     * and the stem above it comes down with its cap, while the arms beside the break, the flare and the glowcaps
     * stand; break one spire of a cluster and it falls, and the others stand.
     *
     * <p>The collapse steps on scheduled ticks, which run only in a ticking chunk, and the spires stand 40 blocks off
     * the test, so both giants are grown once every chunk they span is entity-ticking.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + COLLAPSE_TICKS + 40)
    public void aGiantFallsAsAStalkDoes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos parasolBase = helper.absolutePos(new BlockPos(0, 2, 0)).above(SKY);
        BlockPos spireBase = parasolBase.offset(40, 0, 0);
        GiantLayout parasol = parasolLayout(11);
        GiantLayout spires = spireLayout(11);
        Cell stem = new Cell(0, 2, 0);
        expect(helper, parasol.pieces().get(stem) instanceof GiantPiece.Stem, "the parasol's stem is not at %s", stem);
        Cell foot = spires.pieces().keySet().stream()
                .filter(cell -> cell.y() == 1 && spires.owners().getOrDefault(cell, 0) == 2)
                .findFirst()
                .orElseThrow();
        List<BlockPos> chunks = new ArrayList<>(chunksUnder(parasol, parasolBase));
        chunks.addAll(chunksUnder(spires, spireBase));
        boolean[] grown = {false};
        FarChunks.awaitAllEntityTicking(helper, level, chunks, () -> {
            grow(level, parasol, parasolBase);
            grow(level, spires, spireBase);
            level.destroyBlock(parasolBase.offset(stem.x(), stem.y(), stem.z()), false);
            level.destroyBlock(spireBase.offset(foot.x(), foot.y(), foot.z()), false);
            grown[0] = true;
        });
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!grown[0]) {
                        throw helper.assertionException("the giants' chunks are not entity-ticking yet");
                    }
                })
                .thenIdle(COLLAPSE_TICKS)
                .thenExecute(() -> {
                    try {
                        parasol.pieces().forEach((cell, piece) -> {
                            boolean standing = !level.getBlockState(parasolBase.offset(cell.x(), cell.y(), cell.z()))
                                    .isAir();
                            // A twin (part 2) stands on its own stem, and the arms beside the break on theirs.
                            boolean broken = parasol.owners().getOrDefault(cell, 0) == 1
                                    && (cell.y() > stem.y() || cell.equals(stem));
                            expect(
                                    helper,
                                    standing != broken,
                                    "parasol cell %s, a %s, standing %s",
                                    cell,
                                    piece,
                                    standing);
                        });
                        spires.pieces().forEach((cell, piece) -> {
                            boolean standing = !level.getBlockState(spireBase.offset(cell.x(), cell.y(), cell.z()))
                                    .isAir();
                            boolean broken = spires.owners().getOrDefault(cell, 0) == 2 && cell.y() >= foot.y();
                            expect(
                                    helper,
                                    standing != broken,
                                    "spire cell %s of part %s, standing %s",
                                    cell,
                                    spires.owners().get(cell),
                                    standing);
                        });
                    } finally {
                        clear(level, parasol, parasolBase);
                        clear(level, spires, spireBase);
                        level.getEntitiesOfClass(ItemEntity.class, new AABB(parasolBase).inflate(80))
                                .forEach(Entity::discard);
                    }
                    helper.succeed();
                });
    }

    /** A block of each chunk the pad under {@code layout} at {@code base} spans, at the pad's row. */
    private static List<BlockPos> chunksUnder(GiantLayout layout, BlockPos base) {
        List<BlockPos> chunks = new ArrayList<>();
        BlockPos low = base.offset(layout.minX() - 1, -1, layout.minZ() - 1);
        BlockPos high = base.offset(layout.maxX() + 1, -1, layout.maxZ() + 1);
        for (int chunkX = low.getX() >> 4; chunkX <= high.getX() >> 4; chunkX++) {
            for (int chunkZ = low.getZ() >> 4; chunkZ <= high.getZ() >> 4; chunkZ++) {
                chunks.add(new BlockPos(chunkX << 4, low.getY(), chunkZ << 4));
            }
        }
        return chunks;
    }

    private static GiantFungusFeature feature(double great, double parasol, double spires, int rarity) {
        return new GiantFungusFeature(
                3, 0, rarity, new GiantFungusFeature.Weights(great, parasol, spires), ParasolShape.Style.DEFAULT);
    }

    /**
     * A site whose west or north neighbour picked its form rolls once more, so like forms side by side are rarer than
     * the weights alone would make them; with one form only, every site is it.
     */
    @GameTest
    public void aSiteReRollsTheFormItsNeighbourPicked(GameTestHelper helper) {
        GiantFungusFeature mix = feature(0.3, 0.4, 0.3, 1);
        long seed = 1202;
        int pairs = 0;
        int same = 0;
        for (int x = 0; x < 100; x++) {
            for (int z = 0; z < 100; z++) {
                pairs++;
                if (mix.form(seed, x, z) == mix.form(seed, x - 1, z)) {
                    same++;
                }
            }
        }
        // Without the re-roll, two sites side by side match 0.3^2 + 0.4^2 + 0.3^2 = 0.34 of the time.
        double share = (double) same / pairs;
        expect(helper, share < 0.30, "neighbours match %s of the time, as often as with no re-roll", share);
        GiantFungusFeature alone = feature(0, 1, 0, 1);
        for (int x = 0; x < 20; x++) {
            expect(
                    helper,
                    alone.form(seed, x, 0) == Form.PARASOL,
                    "a parasol-only zone grew %s",
                    alone.form(seed, x, 0));
        }
        helper.succeed();
    }

    /**
     * Each zone's sites are one chunk in its rarity, and its mix is its weights, within the re-roll's pull toward an
     * even mix.
     */
    @GameTest
    public void eachZonesMixIsItsWeights(GameTestHelper helper) {
        record Zone(String name, int rarity, double great, double parasol, double spires) {}
        List<Zone> zones = List.of(
                new Zone("garden", 3, 0.3, 0.4, 0.3),
                new Zone("spore_fields", 5, 0.25, 0.15, 0.6),
                new Zone("quiet", 10, 0, 0.7, 0.3));
        for (Zone zone : zones) {
            GiantFungusFeature feature = zoneFeature(helper, zone.name());
            expect(helper, feature.rarity() == zone.rarity(), "%s has rarity %s", zone.name(), feature.rarity());
            Map<Form, Integer> forms = new EnumMap<>(Form.class);
            int sites = 0;
            for (int i = 0; i < CHUNKS; i++) {
                int x = i % 64 - 32;
                int z = i / 64 - 32;
                if (feature.site(99, x, z)) {
                    sites++;
                    forms.merge(feature.form(99, x, z), 1, Integer::sum);
                }
            }
            double share = (double) sites / CHUNKS;
            expect(
                    helper,
                    Math.abs(share - 1.0 / zone.rarity()) < 0.03,
                    "%s has a site in %s of its chunks",
                    zone.name(),
                    share);
            double[] weights = {zone.great(), zone.parasol(), zone.spires()};
            for (Form form : Form.values()) {
                double got = (double) forms.getOrDefault(form, 0) / sites;
                double want = weights[form.ordinal()];
                expect(
                        helper,
                        want == 0 ? got == 0 : Math.abs(got - want) < 0.08,
                        "%s grows %s at %s of its sites, its weight is %s",
                        zone.name(),
                        form,
                        got,
                        want);
            }
        }
        helper.succeed();
    }
}
