package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.Overgrowth;
import io.github.pkeppeler.deepcharter.layer.OvergrowthTuning;
import io.github.pkeppeler.deepcharter.test.support.Arrival;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.Hollows;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Server GameTests for the overgrowth veneer (#974): the thin moss film that creeps ahead of the fill along a bore's walls, spills round a
 * closed mouth in ragged patches, hangs from ceilings, never blocks movement or the drill, and goes when the growth it belongs to is cut or
 * the bore is visited. A test has a column of its own: a stone box in layer 1 with a 3 x 3 bore cut in it and a one-block-high cave over
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
            // The film never holds the fill back: the cells it is on still close, and no veneer is left in a closed
            // bore.
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
            LevelChunk chunk = level.getChunkAt(new BlockPos(x, FLOOR, Z));
            cutBore(level, x);
            Overgrowth.grow(level, chunk, lastVisit(level, x) + TUNING.delayTicks());
            List<BlockPos> film = boreCells(x).stream()
                    .filter(cell -> level.getBlockState(cell).is(LayerBlocks.OVERGROWTH_VENEER))
                    .toList();
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

    // ---------------------------------------------------------------------------------------------------------------

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
        ServerLevel level = TestLayers.level(helper, 1);
        RoomCarver.carve(
                level, x - ROCK, x + ROCK, FLOOR - 2, FLOOR + BORE_LENGTH + 1, Z - ROCK, Z + ROCK, Blocks.STONE);
        Hollows.fill(
                level,
                new BlockPos(x - 8, FLOOR - 4, Z - 8),
                new BlockPos(x + 7, FLOOR + BORE_LENGTH + 1, Z + 7),
                Hollows.BIOME);
        for (BlockPos cell : caveCells(x)) {
            level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
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
        return Versioned.readable(level.getChunkAt(new BlockPos(x, FLOOR, Z)), Overgrowth.BORES)
                .orElseThrow()
                .lastVisit();
    }
}
