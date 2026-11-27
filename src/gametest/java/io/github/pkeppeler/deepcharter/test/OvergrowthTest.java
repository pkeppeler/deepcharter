package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.SPRINT;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.Overgrowth;
import io.github.pkeppeler.deepcharter.layer.OvergrowthTuning;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodFuel;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.test.support.Arrival;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.Hollows;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.test.support.UnloadedChunk;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server GameTests for overgrowth (#974): an unvisited bore grows over from its walls inward after the delay, a visited
 * one does not, a placed block is never grown over or replaced, a lining block holds a stretch open, an entity's cell
 * is skipped, nothing grows outside the biome tag, the drill pays extra fuel through growth and marks what it bores, a
 * hand cuts growth, the bores survive a chunk reload, and the pacing on the real tick path. A test has a column of its
 * own, a stone box in layer 1 with a 3 x 3 bore cut in it, in the test biome {@code deepcharter-test:hollows} (the only
 * member of the tag in the test data). Most tests pass the clock to {@link Overgrowth#grow} instead of waiting for it,
 * as the production tick does; one waits.
 */
public class OvergrowthTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(OvergrowthTest.class);
    private static final OvergrowthTuning TUNING = OvergrowthTuning.DEFAULT;
    private static final int Z = 6600;
    private static final int FLOOR = TestLayers.worldY(1, 60);
    /** Half the width of the stone box: x is 8 in its chunk, so the box (and the bore in it) lies in one chunk. */
    private static final int ROCK = 7;

    private static final int BORE_LENGTH = 8;
    private static final int BORE_CELLS = 9 * BORE_LENGTH;
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 1200;
    /**
     * A bore closes in at most this many passes more than its cells divided by the pass's share: a pass may weigh a
     * cell that cannot grow yet.
     */
    private static final int SLACK_PASSES = 8;

    private static final Identifier HOLLOWS = Hollows.BIOME;

    /** The test column {@code n}: 80 blocks apart, so no visit or bore of one test reaches another. */
    private static int column(int n) {
        return 5000 + 80 * n;
    }

    @FunctionalInterface
    private interface Body {
        void run(ServerLevel level, int x);
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void anUnvisitedBoreGrowsOverAfterTheDelayFromTheWallsInward(GameTestHelper helper) {
        onceReady(helper, column(0), (level, x) -> {
            LevelChunk chunk = chunkOf(level, x);
            cutBore(level, x);
            long start = lastVisit(chunk);
            Overgrowth.grow(level, chunk, start + TUNING.delayTicks() - 1);
            if (grown(level, x) != 0) {
                throw failure(helper, "a cell grew a tick before the delay of %d ticks", TUNING.delayTicks());
            }
            long now = start + TUNING.delayTicks();
            Overgrowth.grow(level, chunk, now);
            if (grown(level, x) != TUNING.cellsPerPass()) {
                throw failure(
                        helper,
                        "the first pass after the delay should fill %d cells, it filled %d",
                        TUNING.cellsPerPass(),
                        grown(level, x));
            }
            BlockPos floating = unanchored(level, x);
            if (floating != null) {
                throw failure(
                        helper,
                        "the cell at y %d grew with no chain of growth to a wall cell (it grows inward)",
                        floating.getY());
            }
            int passes = 1;
            while (grown(level, x) < BORE_CELLS && passes < BORE_CELLS) {
                int before = grown(level, x);
                now += TUNING.passTicks();
                Overgrowth.grow(level, chunk, now);
                passes++;
                BlockPos loose = unanchored(level, x);
                if (loose != null) {
                    throw failure(
                            helper,
                            "the cell at y %d grew with no chain of growth to a wall cell (it grows inward)",
                            loose.getY());
                }
                if (grown(level, x) - before > TUNING.cellsPerPass()) {
                    throw failure(
                            helper,
                            "a pass filled %d cells, more than %d",
                            grown(level, x) - before,
                            TUNING.cellsPerPass());
                }
            }
            int fewest = Math.ceilDiv(BORE_CELLS, TUNING.cellsPerPass());
            if (grown(level, x) != BORE_CELLS || passes < fewest || passes > fewest + SLACK_PASSES) {
                throw failure(
                        helper,
                        "the bore of %d cells should close in %d to %d passes, it was %d cells grown after %d",
                        BORE_CELLS,
                        fewest,
                        fewest + SLACK_PASSES,
                        grown(level, x),
                        passes);
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aVisitedBoreDoesNotGrowAndTheClockStartsOver(GameTestHelper helper) {
        MockPlayer visitor = MockPlayers.join(helper, "overgrowth-visitor");
        onceReady(helper, column(1), (level, x) -> {
            LevelChunk chunk = chunkOf(level, x);
            cutBore(level, x);
            long start = lastVisit(chunk);
            long later = start + 2L * TUNING.delayTicks();
            Overgrowth.stampVisit(level, chunk, later);
            if (lastVisit(chunk) != start) {
                throw failure(helper, "a visit was stamped with no player near");
            }
            visitor.teleportTo(level, new Vec3(x + 0.5, FLOOR, Z + 0.5), 0f, 0f);
            visitor.player().setGameMode(GameType.SPECTATOR);
            Overgrowth.stampVisit(level, chunk, later);
            if (lastVisit(chunk) != start) {
                throw failure(helper, "a spectator in the bore counted as a visit");
            }
            visitor.player().setGameMode(GameType.SURVIVAL);
            Overgrowth.stampVisit(level, chunk, start + TUNING.visitStampTicks() - 1);
            if (lastVisit(chunk) != start) {
                throw failure(
                        helper, "a visit was stamped less than %d ticks after the last", TUNING.visitStampTicks());
            }
            Overgrowth.stampVisit(level, chunk, later);
            if (lastVisit(chunk) != later) {
                throw failure(
                        helper,
                        "a player in the bore did not visit it: last visit %d, wanted %d",
                        lastVisit(chunk),
                        later);
            }
            Overgrowth.grow(level, chunk, later + TUNING.delayTicks() - 1);
            if (grown(level, x) != 0) {
                throw failure(helper, "a visited bore grew before the delay ran from the visit");
            }
            Overgrowth.grow(level, chunk, later + TUNING.delayTicks());
            if (grown(level, x) != TUNING.cellsPerPass()) {
                throw failure(
                        helper,
                        "the bore should grow once the delay has run from the visit, %d cells grew",
                        grown(level, x));
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aPlacedBlockIsNeverGrownOverOrReplaced(GameTestHelper helper) {
        onceReady(helper, column(2), (level, x) -> {
            LevelChunk chunk = chunkOf(level, x);
            cutBore(level, x);
            BlockPos cobble = new BlockPos(x + 1, FLOOR, Z - 1);
            BlockPos planks = new BlockPos(x - 1, FLOOR, Z + 1);
            BlockPos torch = cobble.above();
            Map<BlockPos, BlockState> placed = Map.of(
                    cobble, Blocks.COBBLESTONE.defaultBlockState(),
                    planks, Blocks.OAK_PLANKS.defaultBlockState(),
                    torch, Blocks.TORCH.defaultBlockState());
            placed.forEach((pos, state) -> level.setBlock(pos, state, Block.UPDATE_ALL));
            long now = lastVisit(chunk) + TUNING.delayTicks();
            for (int pass = 0; pass < 2 * BORE_CELLS; pass++) {
                Overgrowth.grow(level, chunk, now + (long) pass * TUNING.passTicks());
            }
            placed.forEach((pos, state) -> {
                if (!level.getBlockState(pos).equals(state)) {
                    throw failure(
                            helper,
                            "the placed block at %s is now %s, it was %s",
                            pos,
                            level.getBlockState(pos),
                            state);
                }
            });
            if (grown(level, x) != BORE_CELLS - placed.size()) {
                throw failure(
                        helper,
                        "every free cell of the bore should have grown over: %d of %d",
                        grown(level, x),
                        BORE_CELLS - placed.size());
            }
            Set<Long> marked = bores(chunk).cells();
            for (BlockPos pos : placed.keySet()) {
                if (marked.contains(pos.asLong())) {
                    throw failure(helper, "the cell of the placed block at %s is still a bore", pos);
                }
            }
            if (!marked.isEmpty()) {
                throw failure(
                        helper,
                        "a bore that is all grown or built over has no pending cell, %d are marked",
                        marked.size());
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aLiningBlockHoldsTheStretchRoundItOpenForGood(GameTestHelper helper) {
        onceReady(helper, column(3), (level, x) -> {
            LevelChunk chunk = chunkOf(level, x);
            cutBore(level, x);
            int heldY = FLOOR + 3;
            // The lining sits in the walls on each side of the shaft.
            BlockPos east = new BlockPos(x + 2, heldY, Z);
            BlockPos west = new BlockPos(x - 2, heldY, Z);
            level.setBlock(east, SlagBrick.BLOCK.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(west, SlagBrick.BLOCK.defaultBlockState(), Block.UPDATE_ALL);
            long now = lastVisit(chunk) + TUNING.delayTicks();
            for (int pass = 0; pass < 4 * BORE_CELLS; pass++) {
                Overgrowth.grow(level, chunk, now + (long) pass * TUNING.passTicks());
            }
            int reach = TUNING.holdReach();
            int held = 0;
            for (int y = FLOOR; y < FLOOR + BORE_LENGTH; y++) {
                boolean heldRow = Math.abs(y - heldY) <= reach;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockState state = level.getBlockState(new BlockPos(x + dx, y, Z + dz));
                        if (heldRow != state.isAir()) {
                            throw failure(
                                    helper,
                                    "the cell at dx %d y %d dz %d is %s: a stretch within %d of the lining stays open, the rest grows over",
                                    dx,
                                    y,
                                    dz,
                                    state,
                                    reach);
                        }
                        held += heldRow ? 1 : 0;
                    }
                }
            }
            if (held != 9 * (2 * reach + 1)
                    || !level.getBlockState(east).is(SlagBrick.BLOCK)
                    || !level.getBlockState(west).is(SlagBrick.BLOCK)) {
                throw failure(
                        helper, "the lining must stay and hold %d cells open, it held %d", 9 * (2 * reach + 1), held);
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void growthNeverFillsACellAnEntityOccupies(GameTestHelper helper) {
        onceReady(helper, column(4), (level, x) -> {
            LevelChunk chunk = chunkOf(level, x);
            cutBore(level, x);
            PodEntity pod = TestEntities.create(PodRegistry.POD, level);
            pod.setPos(x + 0.5, FLOOR, Z + 0.5);
            level.addFreshEntity(pod);
            Entity stand = TestEntities.create(EntityTypes.ARMOR_STAND, level);
            stand.setNoGravity(true);
            stand.setPos(x - 0.5, FLOOR + BORE_LENGTH - 2, Z - 0.5);
            level.addFreshEntity(stand);
            long now = lastVisit(chunk) + TUNING.delayTicks();
            for (int pass = 0; pass < 2 * BORE_CELLS; pass++) {
                Overgrowth.grow(level, chunk, now + (long) pass * TUNING.passTicks());
            }
            int kept = 0;
            for (BlockPos cell : boreCells(x)) {
                boolean occupied = pod.getBoundingBox().intersects(new AABB(cell))
                        || stand.getBoundingBox().intersects(new AABB(cell));
                boolean growth = level.getBlockState(cell).is(LayerBlocks.OVERGROWTH);
                if (occupied == growth) {
                    throw failure(
                            helper,
                            "the cell %s is %s: growth fills a free cell and skips one an entity is in",
                            cell,
                            level.getBlockState(cell));
                }
                kept += occupied ? 1 : 0;
            }
            if (kept == 0) {
                throw failure(helper, "the test put no entity in the bore");
            }
            pod.discard();
            stand.discard();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void nothingGrowsOutsideTheBiomeTag(GameTestHelper helper) {
        // Read before the room is carved, which puts the column in the hollows.
        Identifier layerBiome = TestLayers.level(helper, 1)
                .getNoiseBiome(column(5) >> 2, FLOOR >> 2, Z >> 2)
                .unwrapKey()
                .orElseThrow()
                .identifier();
        onceReady(helper, column(5), (level, x) -> {
            LevelChunk chunk = chunkOf(level, x);
            fillBiome(level, x, layerBiome);
            cutBore(level, x);
            if (chunk.hasAttached(Overgrowth.BORES)) {
                throw failure(
                        helper,
                        "a bore cut outside the hollows was recorded: the world must pay nothing where the tag is empty");
            }
            Overgrowth.grow(level, chunk, level.getGameTime() + 100L * TUNING.delayTicks());
            if (grown(level, x) != 0) {
                throw failure(helper, "a bore outside the tag grew over");
            }
            // A bore cut in the hollows, whose biome then changes: the growth checks the biome as it grows.
            fillBiome(level, x, HOLLOWS);
            cutBore(level, x);
            fillBiome(level, x, layerBiome);
            Overgrowth.grow(level, chunk, level.getGameTime() + 100L * TUNING.delayTicks());
            if (grown(level, x) != 0) {
                throw failure(helper, "a bore whose biome left the tag grew over");
            }
            fillBiome(level, x, HOLLOWS);
            Overgrowth.grow(level, chunk, level.getGameTime() + 100L * TUNING.delayTicks());
            if (grown(level, x) == 0) {
                throw failure(helper, "the control failed: the same bore does not grow in the tag");
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aHandCutsGrowthQuicklyAndItDropsNothing(GameTestHelper helper) {
        MockPlayer hand = MockPlayers.join(helper, "overgrowth-hand");
        onceReady(helper, column(6), (level, x) -> {
            LevelChunk chunk = chunkOf(level, x);
            hand.teleportTo(level, new Vec3(x + 0.5, FLOOR + BORE_LENGTH + 2, Z + 0.5), 0f, 0f);
            hand.player().setGameMode(GameType.SURVIVAL);
            // A player in the air digs at a fifth of the speed.
            hand.player().setOnGround(true);
            BlockPos growth = new BlockPos(x, FLOOR, Z);
            BlockPos rock = new BlockPos(x + 1, FLOOR, Z);
            level.setBlock(growth, LayerBlocks.OVERGROWTH.defaultBlockState(), Block.UPDATE_ALL);
            BlockState state = level.getBlockState(growth);
            float bare = state.getDestroyProgress(hand.player(), level, growth);
            int ticks = (int) Math.ceil(1.0 / bare);
            if (ticks > 40) {
                throw failure(helper, "a hand takes %d ticks to cut growth, over 40 (2 s)", ticks);
            }
            hand.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
            float axe = state.getDestroyProgress(hand.player(), level, growth);
            if (!(axe > 3 * bare)) {
                throw failure(
                        helper,
                        "an iron axe should cut growth over 3 times faster than a hand: %.3f against %.3f a tick",
                        axe,
                        bare);
            }
            hand.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            if (!hand.player().gameMode.destroyBlock(growth)
                    || !level.getBlockState(growth).isAir()) {
                throw failure(helper, "an empty hand could not cut the growth");
            }
            // Layer 1's rock is within reach of hands, and a cut of it is a bore too.
            level.setBlock(rock, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            if (!hand.player().gameMode.destroyBlock(rock)) {
                throw failure(helper, "a hand could not break the stone");
            }
            Set<Long> cells = bores(chunk).cells();
            if (!cells.contains(growth.asLong()) || !cells.contains(rock.asLong())) {
                throw failure(helper, "a hand cut should leave a bore: the chunk holds %d cells", cells.size());
            }
            List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(growth).inflate(4));
            if (drops.stream().anyMatch(drop -> drop.getItem().is(LayerBlocks.OVERGROWTH.asItem()))) {
                throw failure(helper, "cut growth dropped %s, it drops nothing", drops);
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theDrillPaysExtraFuelThroughGrowthAndMarksWhatItBores(GameTestHelper helper) {
        int x = column(7);
        ServerLevel level = TestLayers.level(helper, 1);
        RoomCarver.carve(level, x - ROCK, x + ROCK, FLOOR - 8, FLOOR - 1, Z - ROCK, Z + ROCK, Blocks.STONE);
        RoomCarver.carve(level, x - ROCK, x + ROCK, FLOOR, FLOOR + 10, Z - ROCK, Z + ROCK, Blocks.AIR);
        fillBiome(level, x, HOLLOWS);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                level.setBlock(
                        new BlockPos(x + dx, FLOOR - 1, Z + dz),
                        LayerBlocks.OVERGROWTH.defaultBlockState(),
                        Block.UPDATE_ALL);
            }
        }
        MockPlayer pilot = MockPlayers.join(helper, "overgrowth-pilot");
        Vec3 at = new Vec3(x + 0.3, FLOOR, Z + 0.3);
        pilot.teleportTo(level, at, 0f, 0f);
        Arrival<PodEntity> pod = new Arrival<>("pod", "the far chunk ticks");
        FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(at), () -> {
            PodEntity made = TestEntities.create(PodRegistry.POD, level);
            made.setPos(at);
            level.addFreshEntity(made);
            if (!pilot.player().startRiding(made)) {
                throw failure(helper, "the pilot could not mount the pod");
            }
            pilot.setInput(SPRINT);
            pod.set(made);
        });
        double[] growthBurn = {0, 0};
        double[] stoneBurn = {0, 0};
        float[] last = {-1f};
        TestTicks.each(helper, () -> {
            if (!pod.isPresent()) {
                return;
            }
            PodEntity drill = pod.get();
            float fuel = drill.fuel();
            if (last[0] >= 0f && drill.drilling()) {
                int row = (int) Math.ceil(drill.getY() - 1e-3) - 1;
                double[] into = row == FLOOR - 1 ? growthBurn : row == FLOOR - 2 ? stoneBurn : null;
                if (into != null) {
                    into[0] += last[0] - fuel;
                    into[1]++;
                }
            }
            last[0] = fuel;
            if (count(level, x - 2, x + 2, FLOOR - 2, FLOOR - 2, Z - 2, Z + 2, Blocks.AIR) < 4) {
                return;
            }
            pilot.releaseInput();
            if (growthBurn[1] < 5 || stoneBurn[1] < 5) {
                throw failure(
                        helper,
                        "the pod drilled %.0f ticks of growth and %.0f of stone, too few to compare",
                        growthBurn[1],
                        stoneBurn[1]);
            }
            double rate = PodFuel.drainPercentPerTick(PodStats.of(drill), PodFuel.Activity.DRILLING);
            double throughGrowth = growthBurn[0] / growthBurn[1];
            double throughStone = stoneBurn[0] / stoneBurn[1];
            if (Math.abs(throughStone - rate) > 0.05 * rate) {
                throw failure(
                        helper,
                        "a tick of stone should burn the drilling rate %.5f, it burned %.5f",
                        rate,
                        throughStone);
            }
            if (throughGrowth < 2.0 * rate || throughGrowth > TUNING.drillFuelMultiplier() * rate * 1.05) {
                throw failure(
                        helper,
                        "a tick of growth should burn about %.1f times the drilling rate %.5f, it burned %.5f (%.2f times)",
                        TUNING.drillFuelMultiplier(),
                        rate,
                        throughGrowth,
                        throughGrowth / rate);
            }
            // A slab of growth is softer than stone (hardness 1.0 against 1.5), so it takes fewer ticks at the dearer
            // rate: about 2 slabs of stone.
            double slabRatio = growthBurn[0] / stoneBurn[0];
            if (slabRatio < 1.4 || slabRatio > 2.6) {
                throw failure(
                        helper,
                        "a slab of growth should cost about twice a slab of stone in fuel, it cost %.2f times",
                        slabRatio);
            }
            // sync-load: onceReady waited for the chunk to tick before every body and helper here
            Set<Long> bored = bores(level.getChunkAt(new BlockPos(x, FLOOR, Z))).cells();
            if (bored.stream()
                                    .filter(cell -> BlockPos.of(cell).getY() == FLOOR - 1)
                                    .count()
                            < 4
                    || bored.stream()
                                    .filter(cell -> BlockPos.of(cell).getY() == FLOOR - 2)
                                    .count()
                            < 4) {
                throw failure(helper, "the drill should mark every cell it bores, the chunk holds %d", bored.size());
            }
            drill.discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void unreadableBoresAreLeftAsTheyAreAndNothingThrows(GameTestHelper helper) {
        onceReady(helper, column(8), (level, x) -> {
            LevelChunk chunk = chunkOf(level, x);
            cutBore(level, x);
            UnreadableChecks.makeUnreadable(chunk, Overgrowth.BORES);
            UnreadableChecks.assertNoThrow(
                    helper,
                    "overgrowth",
                    Map.of(
                            "mark a bore", () -> Overgrowth.markBore(level, new BlockPos(x, FLOOR, Z)),
                            "stamp a visit", () -> Overgrowth.stampVisit(level, chunk, level.getGameTime() + 100_000L),
                            "grow", () -> Overgrowth.grow(level, chunk, level.getGameTime() + 100_000L)));
            if (!(chunk.getAttached(Overgrowth.BORES) instanceof Versioned.Unreadable<?>)) {
                throw failure(helper, "unreadable saved bores must be left as they are");
            }
            if (grown(level, x) != 0) {
                throw failure(helper, "a bore that cannot be read grew over");
            }
        });
    }

    @GameTest(maxTicks = 2 * FarChunks.AWAIT_BUDGET_TICKS + 1200)
    public void theBoresSurviveAChunkReload(GameTestHelper helper) {
        int x = column(9);
        ServerLevel level = TestLayers.level(helper, 1);
        BlockPos far = new BlockPos(x, FLOOR, Z);
        AtomicReference<Set<Long>> cells = new AtomicReference<>(Set.of());
        long[] start = {0};
        int[] grownBefore = {0};
        UnloadedChunk.cycle(
                helper,
                level,
                far,
                new UnloadedChunk.Stages(
                        () -> {
                            carveRoom(level, x);
                            cutBore(level, x);
                            LevelChunk chunk = chunkOf(level, x);
                            start[0] = lastVisit(chunk);
                            Overgrowth.grow(level, chunk, start[0] + TUNING.delayTicks());
                            Overgrowth.grow(level, chunk, start[0] + TUNING.delayTicks() + TUNING.passTicks());
                            grownBefore[0] = grown(level, x);
                            cells.set(bores(chunk).cells());
                        },
                        () -> {},
                        () -> level.isPositionEntityTicking(far),
                        () -> "the chunk is not entity-ticking",
                        () -> {
                            LevelChunk chunk = chunkOf(level, x);
                            Overgrowth.Bores after = bores(chunk);
                            if (after.lastVisit() != start[0] || !after.cells().equals(cells.get())) {
                                throw failure(
                                        helper,
                                        "the bores did not come back as they were saved: %d cells, last visit %d, wanted %d and %d",
                                        after.cells().size(),
                                        after.lastVisit(),
                                        cells.get().size(),
                                        start[0]);
                            }
                            if (grown(level, x) != grownBefore[0] || grownBefore[0] != 2 * TUNING.cellsPerPass()) {
                                throw failure(
                                        helper,
                                        "the growth should be back as it was: %d cells, wanted %d",
                                        grown(level, x),
                                        grownBefore[0]);
                            }
                            Overgrowth.grow(level, chunk, start[0] + TUNING.delayTicks() + 2L * TUNING.passTicks());
                            if (grown(level, x) <= grownBefore[0]) {
                                throw failure(helper, "the bore did not go on growing after the reload");
                            }
                        }));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aPlayerWhoJoinsInsideGrowthIsFreedAndALeaveCountsAsAVisit(GameTestHelper helper) {
        MockPlayer player = MockPlayers.join(helper, "overgrowth-joiner");
        onceReady(helper, column(11), (level, x) -> {
            LevelChunk chunk = chunkOf(level, x);
            cutBore(level, x);
            BlockPos feet = new BlockPos(x, FLOOR + 2, Z);
            player.teleportTo(level, Vec3.atBottomCenterOf(feet), 0f, 0f);
            // The place a player logged out in has closed round them while they were away.
            level.setBlock(feet, LayerBlocks.OVERGROWTH.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(feet.above(), LayerBlocks.OVERGROWTH.defaultBlockState(), Block.UPDATE_ALL);
            Overgrowth.freePlayer(player.player());
            if (!level.getBlockState(feet).isAir()
                    || !level.getBlockState(feet.above()).isAir()) {
                throw failure(
                        helper,
                        "a player who joins in growth must be freed: feet %s, head %s",
                        level.getBlockState(feet),
                        level.getBlockState(feet.above()));
            }
            Set<Long> pending = bores(chunk).cells();
            if (!pending.contains(feet.asLong())
                    || !pending.contains(feet.above().asLong())) {
                throw failure(helper, "the freed cells must stay pending so they can grow over again");
            }
            chunk.setAttached(Overgrowth.BORES, Versioned.of(new Overgrowth.Bores(0L, pending)));
            Overgrowth.leave(player.player());
            if (lastVisit(chunk) != level.getGameTime()) {
                throw failure(
                        helper,
                        "a player who leaves next to a bore counts as a visit: last visit %d, now %d",
                        lastVisit(chunk),
                        level.getGameTime());
            }
        });
    }

    /**
     * Waits the real clock out on the real tick path: no player near, nothing passed in. Prints the line that
     * mechanics.md quotes.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 12_000)
    public void theBoreGrowsAtTheMeasuredPace(GameTestHelper helper) {
        int x = column(10);
        ServerLevel level = room(helper, x);
        Arrival<Boolean> ready = ready(helper, level, x);
        long[] marked = {-1};
        long[] first = {-1};
        TestTicks.each(helper, () -> {
            if (!ready.isPresent()) {
                return;
            }
            long now = level.getGameTime();
            if (marked[0] < 0) {
                cutBore(level, x);
                marked[0] = now;
                return;
            }
            int grown = grown(level, x);
            if (first[0] < 0 && grown > 0) {
                first[0] = now;
                long waited = now - marked[0];
                if (waited < TUNING.delayTicks() || waited > TUNING.delayTicks() + TUNING.passTicks()) {
                    throw failure(
                            helper,
                            "the first cell grew %d ticks after the bore was cut, the delay is %d",
                            waited,
                            TUNING.delayTicks());
                }
            }
            if (grown < BORE_CELLS) {
                return;
            }
            long toClose = now - first[0];
            long fewest = (long) (Math.ceilDiv(BORE_CELLS, TUNING.cellsPerPass()) - 1) * TUNING.passTicks();
            if (toClose < fewest || toClose > fewest + (long) SLACK_PASSES * TUNING.passTicks()) {
                throw failure(
                        helper,
                        "a bore of %d cells should close %d to %d ticks after the first cell, it took %d",
                        BORE_CELLS,
                        fewest,
                        fewest + (long) SLACK_PASSES * TUNING.passTicks(),
                        toClose);
            }
            LOGGER.info(
                    "[overgrowth] pacing: first growth {} ticks ({} s) after the bore was cut; closed ({} cells) {} ticks ({} s) after the first",
                    first[0] - marked[0],
                    (first[0] - marked[0]) / 20.0,
                    BORE_CELLS,
                    toClose,
                    toClose / 20.0);
            helper.succeed();
        });
    }

    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The column's stone box with the bore cut in it, once its chunk ticks entities; {@code body} runs once, then the
     * test succeeds.
     */
    private static void onceReady(GameTestHelper helper, int x, Body body) {
        ServerLevel level = room(helper, x);
        Arrival<Boolean> ready = ready(helper, level, x);
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

    private static ServerLevel room(GameTestHelper helper, int x) {
        ServerLevel level = TestLayers.level(helper, 1);
        carveRoom(level, x);
        return level;
    }

    private static void carveRoom(ServerLevel level, int x) {
        RoomCarver.carve(
                level, x - ROCK, x + ROCK, FLOOR - 2, FLOOR + BORE_LENGTH + 1, Z - ROCK, Z + ROCK, Blocks.STONE);
        fillBiome(level, x, HOLLOWS);
    }

    private static Arrival<Boolean> ready(GameTestHelper helper, ServerLevel level, int x) {
        Arrival<Boolean> ready = new Arrival<>("the room", "the far chunk ticks");
        FarChunks.awaitEntityTicking(helper, level, new BlockPos(x, FLOOR, Z), () -> ready.set(true));
        return ready;
    }

    /** Puts the column's whole chunk in {@code biome}. */
    private static void fillBiome(ServerLevel level, int x, Identifier biome) {
        Hollows.fill(
                level,
                new BlockPos(x - 8, FLOOR - 4, Z - 8),
                new BlockPos(x + 7, FLOOR + BORE_LENGTH - 1, Z + 7),
                biome);
    }

    /** Cuts the 3 x 3 bore the way the drill does: the cell goes, then it is marked. */
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

    private static int grown(ServerLevel level, int x) {
        return (int) boreCells(x).stream()
                .filter(cell -> level.getBlockState(cell).is(LayerBlocks.OVERGROWTH))
                .count();
    }

    /**
     * A grown bore cell that no chain of grown cells joins to a wall cell (one that touches the rock round the bore),
     * or null. A pass fills several cells in turn, so a middle cell may grow in the pass after a wall cell it touches
     * did; it may not grow with no growth to hold to.
     */
    private static @Nullable BlockPos unanchored(ServerLevel level, int x) {
        Set<BlockPos> bore = new HashSet<>(boreCells(x));
        Set<BlockPos> anchored = new HashSet<>();
        Deque<BlockPos> frontier = new ArrayDeque<>();
        for (BlockPos cell : bore) {
            if (level.getBlockState(cell).is(LayerBlocks.OVERGROWTH)
                    && Arrays.stream(Direction.values()).anyMatch(side -> !bore.contains(cell.relative(side)))
                    && anchored.add(cell)) {
                frontier.add(cell);
            }
        }
        while (!frontier.isEmpty()) {
            BlockPos cell = frontier.poll();
            for (Direction side : Direction.values()) {
                BlockPos next = cell.relative(side);
                if (bore.contains(next) && level.getBlockState(next).is(LayerBlocks.OVERGROWTH) && anchored.add(next)) {
                    frontier.add(next);
                }
            }
        }
        return bore.stream()
                .filter(cell -> level.getBlockState(cell).is(LayerBlocks.OVERGROWTH) && !anchored.contains(cell))
                .findFirst()
                .orElse(null);
    }

    private static LevelChunk chunkOf(ServerLevel level, int x) {
        // sync-load: onceReady waited for the chunk to tick before every body and helper here
        return level.getChunkAt(new BlockPos(x, FLOOR, Z));
    }

    private static Overgrowth.Bores bores(LevelChunk chunk) {
        return Versioned.readable(chunk, Overgrowth.BORES).orElseThrow();
    }

    private static long lastVisit(LevelChunk chunk) {
        return bores(chunk).lastVisit();
    }

    private static int count(ServerLevel level, int x1, int x2, int y1, int y2, int z1, int z2, Block block) {
        int n = 0;
        for (BlockPos pos : BlockPos.betweenClosed(x1, y1, z1, x2, y2, z2)) {
            if (level.getBlockState(pos).is(block)) {
                n++;
            }
        }
        return n;
    }
}
