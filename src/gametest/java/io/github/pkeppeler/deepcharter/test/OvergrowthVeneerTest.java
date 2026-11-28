package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.layer.CaveIns;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.Overgrowth;
import io.github.pkeppeler.deepcharter.layer.OvergrowthTuning;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.test.support.Arrival;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.Hollows;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for the overgrowth veneer (#974): the thin moss film that creeps ahead of the fill along a bore's
 * walls, spills round a closed mouth in ragged patches, hangs from ceilings, never blocks movement or the drill, and
 * goes when the growth it belongs to is cut or the bore is visited. A test has a column of its own: a stone box in
 * layer 1 with a 3 x 3 bore cut in it and a one-block-high cave over
 * the bore's end, in the test biome {@code deepcharter-test:hollows}.
 */
public class OvergrowthVeneerTest {
    private static final OvergrowthTuning TUNING = OvergrowthTuning.DEFAULT;
    private static final int Z = 6900;
    private static final int FLOOR = TestLayers.worldY(1, 60);
    private static final int ROCK = 7;
    private static final int BORE_LENGTH = 8;
    /** The cave's half width: wider than the veneer's reach round the bore, so its rim is out of reach. */
    private static final int CAVE = 4;

    private static final int CAVE_Y = FLOOR + BORE_LENGTH;
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 1200;
    /** Passes enough to close a 3 x 3 x 8 bore at the tuned rate, with the slack the existing tests allow. */
    private static final int PASSES = 40;

    @FunctionalInterface
    private interface Body {
        void run(ServerLevel level, int x);
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theFilmCreepsAheadOfTheFill(GameTestHelper helper) {
        onceReady(helper, 5000, (level, x) -> {
            // sync-load: onceReady waited for the chunk to tick before every body and helper here
            LevelChunk chunk = level.getChunkAt(new BlockPos(x, FLOOR, Z));
            cutBore(level, x);
            Overgrowth.grow(level, chunk, lastVisit(level, x) + TUNING.delayTicks() - 1);
            if (veneers(level, boreCells(x)) != 0) {
                throw failure(helper, "veneer grew before the delay");
            }
            Overgrowth.grow(level, chunk, lastVisit(level, x) + TUNING.delayTicks());
            long filled = boreCells(x).stream()
                    .filter(cell -> level.getBlockState(cell).is(LayerBlocks.OVERGROWTH))
                    .count();
            if (filled != TUNING.cellsPerPass()) {
                throw failure(helper, "the first pass filled %d cells, wanted %d", filled, TUNING.cellsPerPass());
            }
            List<BlockPos> film = boreCells(x).stream()
                    .filter(cell -> level.getBlockState(cell).is(LayerBlocks.OVERGROWTH_VENEER))
                    .toList();
            if (film.isEmpty()) {
                throw failure(helper, "no veneer crept ahead of the fill in the first pass");
            }
            for (BlockPos cell : film) {
                BlockState state = level.getBlockState(cell);
                boolean onRock = false;
                for (Direction side : Direction.values()) {
                    if (MultifaceBlock.hasFace(state, side)) {
                        if (!level.getBlockState(cell.relative(side)).is(Blocks.STONE)) {
                            throw failure(helper, "veneer at %s clings to %s, which is not rock", cell, side);
                        }
                        onRock = true;
                    }
                }
                if (!onRock) {
                    throw failure(helper, "veneer at %s has no face", cell);
                }
            }
            // The film never holds the fill back: the cells it is on
            // still close, and no veneer is left in a closed bore.
            long now = lastVisit(level, x) + TUNING.delayTicks();
            for (int pass = 0; pass < PASSES; pass++) {
                now += TUNING.passTicks();
                Overgrowth.grow(level, chunk, now);
            }
            if (veneers(level, boreCells(x)) != 0) {
                throw failure(helper, "a closed bore still holds a veneer cell");
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theFilmSpillsRoundAClosedMouthInPatchesAndHangsFromTheRoof(GameTestHelper helper) {
        onceReady(helper, 5080, (level, x) -> {
            // sync-load: onceReady waited for the chunk to tick before every body and helper here
            LevelChunk chunk = level.getChunkAt(new BlockPos(x, FLOOR, Z));
            cutBore(level, x);
            long now = lastVisit(level, x) + TUNING.delayTicks();
            for (int pass = 0; pass < PASSES; pass++) {
                Overgrowth.grow(level, chunk, now + (long) pass * TUNING.passTicks());
            }
            List<BlockPos> cave = caveCells(x);
            List<BlockPos> film = cave.stream()
                    .filter(cell -> level.getBlockState(cell).is(LayerBlocks.OVERGROWTH_VENEER))
                    .toList();
            if (film.isEmpty()) {
                throw failure(helper, "no veneer spilled out of the closed mouth");
            }
            // Within reach of the plug (the cells up to 2 from a grown cell) the cover is partial: some cells, not all.
            List<BlockPos> inReach =
                    cave.stream().filter(cell -> Math.abs(cell.getX() - x) <= 3).toList();
            long reached = inReach.stream().filter(film::contains).count();
            if (reached == 0 || reached == inReach.size()) {
                throw failure(
                        helper,
                        "the spill covered %d of %d cells in reach: it should be partial",
                        reached,
                        inReach.size());
            }
            for (BlockPos cell : film) {
                if (Math.abs(cell.getX() - x) > 3 || Math.abs(cell.getZ() - Z) > 3) {
                    throw failure(helper, "veneer at %s spilled past the reach of the plug", cell);
                }
            }
            boolean hangs =
                    film.stream().anyMatch(cell -> MultifaceBlock.hasFace(level.getBlockState(cell), Direction.UP));
            if (!hangs) {
                throw failure(helper, "no veneer clings to the cave roof, so nothing hangs");
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theFilmGoesWhenTheGrowthIsCutAndStaysWhileItStands(GameTestHelper helper) {
        onceReady(helper, 5160, (level, x) -> {
            // sync-load: onceReady waited for the chunk to tick before every body and helper here
            LevelChunk chunk = level.getChunkAt(new BlockPos(x, FLOOR, Z));
            cutBore(level, x);
            long now = lastVisit(level, x) + TUNING.delayTicks();
            for (int pass = 0; pass < PASSES; pass++) {
                Overgrowth.grow(level, chunk, now + (long) pass * TUNING.passTicks());
            }
            List<BlockPos> film = caveCells(x).stream()
                    .filter(cell -> level.getBlockState(cell).is(LayerBlocks.OVERGROWTH_VENEER))
                    .toList();
            if (film.isEmpty()) {
                throw failure(helper, "no veneer to test the retreat of");
            }
            tickAll(level, film);
            if (veneers(level, film) != film.size()) {
                throw failure(helper, "veneer went while the growth it belongs to still stands");
            }
            for (BlockPos cell : boreCells(x)) {
                level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            tickAll(level, film);
            long left = veneers(level, film);
            if (left != 0) {
                throw failure(helper, "%d veneer cells are left after the growth was cut", left);
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theFilmGoesWhenTheBoreIsVisitedAndNeverBlocksAnything(GameTestHelper helper) {
        onceReady(helper, 5240, (level, x) -> {
            // sync-load: onceReady waited for the chunk to tick before every body and helper here
            LevelChunk chunk = level.getChunkAt(new BlockPos(x, FLOOR, Z));
            List<BlockPos> film = filmWithSomeOutOfReach(level, chunk, x);
            if (film.isEmpty()) {
                throw failure(helper, "no veneer ahead of the fill");
            }
            BlockPos at = film.get(0);
            BlockState state = level.getBlockState(at);
            if (!state.getCollisionShape(level, at).isEmpty()) {
                throw failure(helper, "veneer has a collision shape: it would stop a pod or a player");
            }
            if (state.getDestroySpeed(level, at) != 0f) {
                throw failure(helper, "veneer takes time to break: %s", state.getDestroySpeed(level, at));
            }
            if (state.canOcclude() || state.isSolidRender()) {
                throw failure(helper, "veneer blocks light or occludes its neighbours");
            }
            // Visiting the chunk starts the clock again: the film ahead of the fill goes, except what growth still
            // standing holds.
            List<BlockPos> far =
                    film.stream().filter(cell -> !nearGrowth(level, cell)).toList();
            if (far.isEmpty()) {
                throw failure(helper, "every veneer cell is beside growth, so a visit has nothing to retreat");
            }
            Overgrowth.markBore(level, boreCells(x).get(0));
            tickAll(level, film);
            long left = veneers(level, far);
            if (left != 0) {
                throw failure(helper, "%d veneer cells are left in a bore that was visited", left);
            }
            if (veneers(level, film) == 0) {
                throw failure(helper, "a visit took the film that standing growth still holds");
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theScheduledCheckHoldsTheFilmWhileUnvisitedAndLetsGoAfterAVisit(GameTestHelper helper) {
        int x = 5320;
        ServerLevel level = TestLayers.level(helper, 1);
        int[] stage = {0};
        long[] mark = {0};
        AtomicReference<List<BlockPos>> far = new AtomicReference<>(List.of());
        Arrival<Boolean> ready = new Arrival<>("the room", "the far chunk ticks");
        buildRoom(level, x, (l, c) -> {});
        FarChunks.awaitEntityTicking(helper, level, new BlockPos(x, FLOOR, Z), () -> ready.set(true));
        TestTicks.each(helper, () -> {
            if (!ready.isPresent()) {
                return;
            }
            long now = level.getGameTime();
            // sync-load: onceReady waited for the chunk to tick before every body and helper here
            LevelChunk chunk = level.getChunkAt(new BlockPos(x, FLOOR, Z));
            // The chunk's own growth pass runs on the real tick path every passTicks, and would grow cells in the
            // middle of the two waits: start the tick after it, so the 90 ticks of the test fall between two passes.
            if (stage[0] == 0 && Math.floorMod(now - chunk.getPos().hashCode(), TUNING.passTicks()) != 1) {
                return;
            }
            switch (stage[0]) {
                case 0 -> {
                    cutBore(level, x);
                    // The real clock: the bore was last visited longer ago than the delay, so the film is placed and
                    // held by it.
                    setLastVisit(level, x, now - TUNING.delayTicks() - 1);
                    Overgrowth.grow(level, chunk, now);
                    // With the growth taken away again, only the pending cell holds the film.
                    for (BlockPos cell : boreCells(x)) {
                        if (level.getBlockState(cell).is(LayerBlocks.OVERGROWTH)) {
                            level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        }
                    }
                    setLastVisit(level, x, now - TUNING.delayTicks() - 1);
                    far.set(boreCells(x).stream()
                            .filter(cell -> level.getBlockState(cell).is(LayerBlocks.OVERGROWTH_VENEER))
                            .toList());
                    if (far.get().isEmpty()) {
                        throw failure(helper, "no film ahead of the fill");
                    }
                    mark[0] = now;
                    stage[0] = 1;
                }
                case 1 -> {
                    if (now - mark[0] >= TUNING.filmCheckTicks() + 5) {
                        if (far.get().stream()
                                .noneMatch(cell -> level.getBlockState(cell).is(LayerBlocks.OVERGROWTH_VENEER)
                                        && !nearGrowth(level, cell))) {
                            throw failure(helper, "the film ahead of the fill went while the bore was still unvisited");
                        }
                        // A visit: the clock starts over.
                        setLastVisit(level, x, now);
                        mark[0] = now;
                        stage[0] = 2;
                    }
                }
                default -> {
                    if (now - mark[0] >= TUNING.filmCheckTicks() + 5) {
                        long left = far.get().stream()
                                .filter(cell -> level.getBlockState(cell).is(LayerBlocks.OVERGROWTH_VENEER)
                                        && !nearGrowth(level, cell))
                                .count();
                        if (left != 0) {
                            throw failure(helper, "%d film cells ahead of the fill outlived a visit", left);
                        }
                        helper.succeed();
                        stage[0] = 3;
                    }
                }
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theFilmSpillsOnlyInTheHollowsAndOnlyIntoAir(GameTestHelper helper) {
        onceReady(
                helper,
                5400,
                (level, x) -> {
                    plane(level, x, 3, 2);
                    Hollows.fill(
                            level,
                            new BlockPos(x - 8, FLOOR - 4, Z - 8),
                            new BlockPos(x - 1, FLOOR + BORE_LENGTH + 1, Z + 7),
                            Identifier.withDefaultNamespace("plains"));
                },
                (level, x) -> {
                    // The water goes in with the body, not the setup: the far chunk can take hundreds of ticks to
                    // become entity-ticking, and the water spreads over the one-block cave the whole time (#1682).
                    for (BlockPos cell : occupiedCells(x)) {
                        level.setBlock(cell, occupant(cell).defaultBlockState(), Block.UPDATE_ALL);
                    }
                    growPlugs(level, x, List.of(new int[] {0, 0}));
                    List<BlockPos> film = planeCells(x, 3, 2).stream()
                            .filter(cell -> level.getBlockState(cell).is(LayerBlocks.OVERGROWTH_VENEER))
                            .toList();
                    if (film.stream().noneMatch(cell -> cell.getX() >= x)) {
                        throw failure(helper, "no film spilled on the hollows side");
                    }
                    for (BlockPos cell : film) {
                        if (cell.getX() < x) {
                            throw failure(helper, "film at %s, which is outside the hollows biome", cell);
                        }
                    }
                    for (BlockPos cell : occupiedCells(x)) {
                        if (!level.getBlockState(cell).is(occupant(cell))) {
                            throw failure(helper, "the film replaced %s at %s", occupant(cell), cell);
                        }
                    }
                });
    }

    /** Cells beside the plug that hold water or a placed block, so a film that took them would show. */
    private static List<BlockPos> occupiedCells(int x) {
        return planeCells(x, 3, 2).stream()
                .filter(cell -> cell.getX() > x && cell.getX() <= x + 2 && cell.getZ() != Z)
                .toList();
    }

    private static Block occupant(BlockPos cell) {
        return cell.getZ() % 2 == 0 ? Blocks.WATER : Blocks.OAK_PLANKS;
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void noFilmSpillsWithinReachOfALiningBlock(GameTestHelper helper) {
        onceReady(
                helper,
                5480,
                (level, x) -> {
                    plane(level, x, 3, 6);
                    for (int dz = -6; dz <= 6; dz++) {
                        level.setBlock(
                                new BlockPos(x + 4, FLOOR, Z + dz),
                                SlagBrick.BLOCK.defaultBlockState(),
                                Block.UPDATE_ALL);
                    }
                },
                (level, x) -> {
                    growPlugs(level, x, List.of(new int[] {0, -3}, new int[] {0, 0}, new int[] {0, 3}));
                    int reach = TUNING.holdReach();
                    long held = 0;
                    long free = 0;
                    for (BlockPos cell : planeCells(x, 3, 4)) {
                        if (level.getBlockState(cell).is(LayerBlocks.OVERGROWTH_VENEER)) {
                            if (4 - (cell.getX() - x) <= reach) {
                                held++;
                            } else {
                                free++;
                            }
                        }
                    }
                    if (free == 0) {
                        throw failure(helper, "no film away from the lining, so the test proves nothing");
                    }
                    if (held != 0) {
                        throw failure(helper, "%d film cells within %d blocks of a lining block", held, reach);
                    }
                });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theSpillIsDenserBesideAGrownCellThanTwoOut(GameTestHelper helper) {
        onceReady(helper, 5560, (level, x) -> plane(level, x, 6, 2), (level, x) -> {
            growPlugs(level, x, List.of(new int[] {-5, 0}, new int[] {0, 0}, new int[] {5, 0}));
            long[] cells = new long[3];
            long[] film = new long[3];
            for (BlockPos cell : planeCells(x, 6, 2)) {
                int distance = 9;
                for (int plug : new int[] {-5, 0, 5}) {
                    distance =
                            Math.min(distance, Math.max(Math.abs(cell.getX() - x - plug), Math.abs(cell.getZ() - Z)));
                }
                if (distance == 1 || distance == 2) {
                    cells[distance]++;
                    film[distance] += level.getBlockState(cell).is(LayerBlocks.OVERGROWTH_VENEER) ? 1 : 0;
                }
            }
            double near = (double) film[1] / cells[1];
            double far = (double) film[2] / cells[2];
            if (!(near > 1.3 * far) || far == 0) {
                throw failure(
                        helper,
                        "the film covers %.2f of the cells beside a grown cell and %.2f of those two out: it should fall off",
                        near,
                        far);
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theFilmIsNoRoofAndNoBoltRefusal(GameTestHelper helper) {
        onceReady(
                helper,
                5640,
                (level, x) -> {
                    for (int y = FLOOR; y <= FLOOR + 3; y++) {
                        level.setBlock(new BlockPos(x, y, Z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                },
                (level, x) -> {
                    BlockPos feet = new BlockPos(x, FLOOR, Z);
                    BlockPos roof = new BlockPos(x, FLOOR + 4, Z);
                    if (!CaveIns.looseRoof(level, feet).contains(roof)) {
                        throw failure(helper, "the bare roof is not loose, so the test proves nothing");
                    }
                    BlockState film = LayerBlocks.OVERGROWTH_VENEER
                            .defaultBlockState()
                            .setValue(MultifaceBlock.getFaceProperty(Direction.UP), true);
                    level.setBlock(roof.below(), film, Block.UPDATE_ALL);
                    if (!CaveIns.looseRoof(level, feet).contains(roof)) {
                        throw failure(helper, "a film of moss under the roof hides it from a cave-in");
                    }
                    if (CaveIns.boltRefusal(level, roof, 3.0).isPresent()) {
                        throw failure(
                                helper,
                                "a film of moss under the roof refuses a bolt: %s",
                                CaveIns.boltRefusal(level, roof, 3.0));
                    }
                });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void thePlacedBlockGoesIntoTheFilmCell(GameTestHelper helper) {
        onceReady(
                helper,
                5720,
                (level, x) -> level.setBlock(
                        new BlockPos(x, FLOOR, Z),
                        LayerBlocks.OVERGROWTH_VENEER
                                .defaultBlockState()
                                .setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true),
                        Block.UPDATE_ALL),
                (level, x) -> {
                    BlockPos cell = new BlockPos(x, FLOOR, Z);
                    if (!level.getBlockState(cell).canBeReplaced()) {
                        throw failure(helper, "the film cell is not replaceable, so a block is not placed into it");
                    }
                    BlockPos floor = cell.below();
                    BlockPlaceContext context = new BlockPlaceContext(
                            level,
                            null,
                            InteractionHand.MAIN_HAND,
                            new ItemStack(Items.STONE),
                            new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false));
                    if (!context.canPlace() || !context.getClickedPos().equals(cell)) {
                        throw failure(helper, "a block placed on the filmed floor does not go into the film's cell");
                    }
                });
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static void buildRoom(ServerLevel level, int x, Body setup) {
        RoomCarver.carve(
                level, x - ROCK, x + ROCK, FLOOR - 2, FLOOR + BORE_LENGTH + 1, Z - ROCK, Z + ROCK, Blocks.STONE);
        Hollows.fill(
                level,
                new BlockPos(x - 8, FLOOR - 4, Z - 8),
                new BlockPos(x + 7, FLOOR + BORE_LENGTH + 1, Z + 7),
                Hollows.BIOME);
        setup.run(level, x);
    }

    private static void setLastVisit(ServerLevel level, int x, long time) {
        // sync-load: onceReady waited for the chunk to tick before every body and helper here
        LevelChunk chunk = level.getChunkAt(new BlockPos(x, FLOOR, Z));
        Overgrowth.Bores bores = Versioned.readable(chunk, Overgrowth.BORES).orElseThrow();
        chunk.setAttached(Overgrowth.BORES, Versioned.of(new Overgrowth.Bores(time, bores.cells())));
    }

    /**
     * A one-block-high cave at the bore's floor, {@code halfX} and {@code halfZ} either side of the column's middle.
     */
    private static void plane(ServerLevel level, int x, int halfX, int halfZ) {
        for (BlockPos cell : planeCells(x, halfX, halfZ)) {
            level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static List<BlockPos> planeCells(int x, int halfX, int halfZ) {
        return BlockPos.betweenClosedStream(x - halfX, FLOOR, Z - halfZ, x + halfX, FLOOR, Z + halfZ)
                .map(BlockPos::immutable)
                .toList();
    }

    /**
     * Cuts a one-cell bore at each offset {@code {dx, dz}} from the middle, lets the real clock say they are long
     * unvisited, and runs one growth pass.
     */
    private static void growPlugs(ServerLevel level, int x, List<int[]> offsets) {
        for (int[] offset : offsets) {
            Hollows.cut(level, new BlockPos(x + offset[0], FLOOR, Z + offset[1]));
        }
        long now = level.getGameTime();
        setLastVisit(level, x, now - TUNING.delayTicks() - 1);
        // sync-load: onceReady waited for the chunk to tick before every body and helper here
        Overgrowth.grow(level, level.getChunkAt(new BlockPos(x, FLOOR, Z)), now);
        for (int[] offset : offsets) {
            if (!level.getBlockState(new BlockPos(x + offset[0], FLOOR, Z + offset[1]))
                    .is(LayerBlocks.OVERGROWTH)) {
                throw new IllegalStateException("the plug at " + offset[0] + "," + offset[1] + " did not grow");
            }
        }
    }

    private static void tickAll(ServerLevel level, List<BlockPos> cells) {
        for (BlockPos cell : cells) {
            BlockState state = level.getBlockState(cell);
            if (state.is(LayerBlocks.OVERGROWTH_VENEER)) {
                state.tick(level, cell, level.getRandom());
            }
        }
    }

    private static boolean nearGrowth(ServerLevel level, BlockPos cell) {
        return BlockPos.betweenClosedStream(cell.offset(-2, -2, -2), cell.offset(2, 2, 2))
                .anyMatch(near -> level.getBlockState(near).is(LayerBlocks.OVERGROWTH));
    }

    private static long veneers(ServerLevel level, List<BlockPos> cells) {
        return cells.stream()
                .filter(cell -> level.getBlockState(cell).is(LayerBlocks.OVERGROWTH_VENEER))
                .count();
    }

    private static void onceReady(GameTestHelper helper, int x, Body body) {
        onceReady(
                helper,
                x,
                (level, column) -> {
                    for (BlockPos cell : caveCells(column)) {
                        level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                },
                body);
    }

    /**
     * The column's stone box, all in the hollows biome, then {@code setup} carves what the test needs; {@code body}
     * runs once the chunk ticks. {@code setup} places static blocks only: anything that moves or flows goes in
     * {@code body}.
     */
    private static void onceReady(GameTestHelper helper, int x, Body setup, Body body) {
        ServerLevel level = TestLayers.level(helper, 1);
        RoomCarver.carve(
                level, x - ROCK, x + ROCK, FLOOR - 2, FLOOR + BORE_LENGTH + 1, Z - ROCK, Z + ROCK, Blocks.STONE);
        Hollows.fill(
                level,
                new BlockPos(x - 8, FLOOR - 4, Z - 8),
                new BlockPos(x + 7, FLOOR + BORE_LENGTH + 1, Z + 7),
                Hollows.BIOME);
        setup.run(level, x);
        Arrival<Boolean> ready = new Arrival<>("the room", "the far chunk ticks");
        FarChunks.awaitEntityTicking(helper, level, new BlockPos(x, FLOOR, Z), () -> ready.set(true));
        boolean[] done = {false};
        TestTicks.each(helper, () -> {
            if (!ready.isPresent() || done[0]) {
                return;
            }
            done[0] = true;
            body.run(level, x);
            helper.succeed();
        });
    }

    private static void cutBore(ServerLevel level, int x) {
        for (BlockPos cell : boreCells(x)) {
            Hollows.cut(level, cell);
        }
    }

    /**
     * Cuts the bore and grows one pass, and returns its film. Which cells a pass weighs follows the clock, and the
     * clock of a full suite is not that of one class: on a pass that puts all the film beside growth there is nothing
     * to retreat, so it tries the next pass on a fresh bore.
     */
    private static List<BlockPos> filmWithSomeOutOfReach(ServerLevel level, LevelChunk chunk, int x) {
        cutBore(level, x);
        long start = lastVisit(level, x) + TUNING.delayTicks();
        Overgrowth.grow(level, chunk, start);
        List<BlockPos> film = filmOf(level, x);
        for (int again = 1; again <= 8 && film.stream().allMatch(cell -> nearGrowth(level, cell)); again++) {
            for (BlockPos cell : boreCells(x)) {
                level.setBlock(cell, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
            cutBore(level, x);
            Overgrowth.grow(level, chunk, start + (long) again * TUNING.passTicks());
            film = filmOf(level, x);
        }
        return film;
    }

    private static List<BlockPos> filmOf(ServerLevel level, int x) {
        return boreCells(x).stream()
                .filter(cell -> level.getBlockState(cell).is(LayerBlocks.OVERGROWTH_VENEER))
                .toList();
    }

    private static List<BlockPos> boreCells(int x) {
        return BlockPos.betweenClosedStream(x - 1, FLOOR, Z - 1, x + 1, FLOOR + BORE_LENGTH - 1, Z + 1)
                .map(BlockPos::immutable)
                .toList();
    }

    /** The cave over the bore's end: one block high, carved from the stone box, with the box's roof over it. */
    private static List<BlockPos> caveCells(int x) {
        return BlockPos.betweenClosedStream(x - CAVE, CAVE_Y, Z - CAVE, x + CAVE, CAVE_Y, Z + CAVE)
                .map(BlockPos::immutable)
                .toList();
    }

    private static long lastVisit(ServerLevel level, int x) {
        // sync-load: onceReady waited for the chunk to tick before every body and helper here
        return Versioned.readable(level.getChunkAt(new BlockPos(x, FLOOR, Z)), Overgrowth.BORES)
                .orElseThrow()
                .lastVisit();
    }
}
