package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.surface.GreatStalk;
import io.github.pkeppeler.deepcharter.surface.GreatStalkBraid;
import io.github.pkeppeler.deepcharter.surface.GreatStalkLayout;
import io.github.pkeppeler.deepcharter.surface.GreatStalkLayout.Cell;
import io.github.pkeppeler.deepcharter.surface.GreatStalkLayout.Part;
import io.github.pkeppeler.deepcharter.surface.SurfaceTuning;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.ItemSpawns;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Server GameTests for #855: breaking a segment of a fungal stalk brings everything above it down in a spore puff (a single column and a
 * great stalk), the drops are exactly what breaking each of those blocks by hand gives, nothing below the break is touched, the player
 * takes no effect, and a great stalk grows in the shape its placer says. The sound and the particles are sent to clients and are not read
 * here: a server GameTest has no client behind it.
 */
public class StalkCollapseTest {
    /** The row of the test area that holds its stone floor (a row of the test structure, not a world height). */
    private static final int FLOOR_ROW = 1;
    /** The first block a stalk stands in. */
    private static final int BASE_ROW = 2;
    /** The test area's x and z range, in blocks of the test. */
    private static final int AREA_MIN = -2;

    private static final int AREA_MAX = 10;
    /** How many rows above {@link #BASE_ROW} the area counts: more than the tallest great stalk and its cap reach. */
    private static final int AREA_TOP = 30;
    /** Longer than a collapse takes: the sag delay plus one step for each layer of the tallest great stalk and its cap. */
    private static final int COLLAPSE_WAIT = 200;

    /**
     * Where the far player stands, at x = 0: off the stalk (the braid's cells reach z = 17 and the single column stands at z = 2), so
     * that it takes no part in the collapse.
     */
    private static final int FAR_Z = 20;

    private static final String SINGLE_ALONE = "deepcharter-test:stalk_count_single";
    /** How far above the test a build in the air stands, off every neighbour. */
    private static final int SKY = 40;

    private static final int COLUMN_HEIGHT = 6;
    private static final int BROKEN_SEGMENT = 2;
    private static final int TEST_MAX_TICKS = COLLAPSE_WAIT + 100;
    private static final int COUNT_MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + TEST_MAX_TICKS;
    private static final int RATE_ATTEMPTS = 600;
    private static final long RATE_SEED = 855;

    private static Block ours(String path) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
    }

    /** A flat stone floor under the test's whole area, and nothing above it. */
    private static void clearArea(GameTestHelper helper, int height) {
        ServerLevel level = helper.getLevel();
        for (int x = AREA_MIN; x <= AREA_MAX; x++) {
            for (int z = AREA_MIN; z <= AREA_MAX; z++) {
                level.setBlock(
                        helper.absolutePos(new BlockPos(x, FLOOR_ROW, z)),
                        Blocks.STONE.defaultBlockState(),
                        Block.UPDATE_CLIENTS);
                for (int y = BASE_ROW; y <= BASE_ROW + height; y++) {
                    level.setBlock(
                            helper.absolutePos(new BlockPos(x, y, z)),
                            Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /**
     * Starts the wait for every chunk of the item box to be entity-ticking, and returns the check a sequence's {@code thenWaitUntil}
     * polls: it throws until they all are. Call it from the test method.
     */
    private static Runnable countable(GameTestHelper helper) {
        BlockPos low = helper.absolutePos(new BlockPos(-AWAIT_REACH, 0, -AWAIT_REACH));
        BlockPos high = helper.absolutePos(new BlockPos(AWAIT_REACH, 0, AWAIT_REACH));
        List<BlockPos> chunks = new ArrayList<>();
        for (int chunkX = low.getX() >> 4; chunkX <= high.getX() >> 4; chunkX++) {
            for (int chunkZ = low.getZ() >> 4; chunkZ <= high.getZ() >> 4; chunkZ++) {
                chunks.add(new BlockPos(chunkX << 4, low.getY(), chunkZ << 4));
            }
        }
        ServerLevel level = helper.getLevel();
        FarChunks.awaitEntityTicking(helper, level, chunks, index -> {});
        return () -> {
            for (BlockPos chunk : chunks) {
                if (!level.isPositionEntityTicking(chunk)) {
                    throw failure(helper, "the chunk at %s is not entity-ticking yet", chunk.toShortString());
                }
            }
        };
    }

    /** The failure of a count that is not the by-hand count, listing every spawn of the collapse. */
    private static RuntimeException countMismatch(
            GameTestHelper helper, ItemSpawns collapse, Map<String, Integer> collapsed, Map<String, Integer> byHand) {
        return failure(
                helper,
                "the collapse dropped %s, breaking the same blocks by hand dropped %s; the collapse's spawns:%n  %s",
                collapsed,
                byHand,
                String.join("%n  ".formatted(), collapse.spawns()));
    }

    private static MockPlayer farPlayer(GameTestHelper helper, String name) {
        MockPlayer mock = MockPlayers.join(helper, name);
        mock.player().setGameMode(GameType.SURVIVAL);
        mock.teleportTo(
                helper.getLevel(), Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(0, BASE_ROW, FAR_Z))), 0, 0);
        return mock;
    }

    private static void column(GameTestHelper helper, int x, int z, int height) {
        BlockState stalk = ours("fungal_stalk")
                .defaultBlockState()
                .setValue(RotatedPillarBlock.AXIS, net.minecraft.core.Direction.Axis.Y);
        for (int index = 0; index < height; index++) {
            helper.getLevel()
                    .setBlock(helper.absolutePos(new BlockPos(x, BASE_ROW + index, z)), stalk, Block.UPDATE_CLIENTS);
        }
    }

    private static boolean isStalkBlock(BlockState state) {
        return state.is(ours("fungal_stalk")) || state.is(ours("great_stalk")) || state.is(ours("great_stalk_cap"));
    }

    /** The area above the floor, absolute: where the stalk and its burst cells are. */
    private static AABB stalkRegion(GameTestHelper helper) {
        BlockPos low = helper.absolutePos(new BlockPos(AREA_MIN, BASE_ROW, AREA_MIN));
        BlockPos high = helper.absolutePos(new BlockPos(AREA_MAX, BASE_ROW + AREA_TOP, AREA_MAX));
        return new AABB(low).minmax(new AABB(high));
    }

    /** Every stalk block of the area, absolute. */
    private static Set<BlockPos> stalkBlocks(GameTestHelper helper) {
        Set<BlockPos> found = new HashSet<>();
        for (int x = AREA_MIN; x <= AREA_MAX; x++) {
            for (int z = AREA_MIN; z <= AREA_MAX; z++) {
                for (int y = BASE_ROW; y <= BASE_ROW + AREA_TOP; y++) {
                    BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
                    if (isStalkBlock(helper.getLevel().getBlockState(pos))) {
                        found.add(pos);
                    }
                }
            }
        }
        return found;
    }

    private static void breakByHand(GameTestHelper helper, MockPlayer player, BlockPos pos) {
        if (!player.player().gameMode.destroyBlock(pos)) {
            throw failure(
                    helper, "the player could not break %s", helper.getLevel().getBlockState(pos));
        }
    }

    /** Breaking a segment drops everything above it after a sag; the segments below stay, and the drops are the by-hand drops. */
    @GameTest(maxTicks = COUNT_MAX_TICKS, environment = SINGLE_ALONE)
    public void breakingASegmentCollapsesEverythingAboveIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Runnable countable = countable(helper);
        MockPlayer player = farPlayer(helper, "stalk-collapse");
        AtomicReference<Map<String, Integer>> byHand = new AtomicReference<>();
        AtomicReference<ItemSpawns> collapse = new AtomicReference<>();
        helper.startSequence()
                .thenWaitUntil(countable)
                .thenExecute(() -> {
                    clearArea(helper, COLUMN_HEIGHT);
                    // The reference: every block from the break up, broken by hand from the top down, so nothing
                    // collapses.
                    column(helper, 2, 2, COLUMN_HEIGHT);
                    ItemSpawns reference = ItemSpawns.start(helper, stalkRegion(helper));
                    for (int index = COLUMN_HEIGHT - 1; index >= BROKEN_SEGMENT; index--) {
                        breakByHand(helper, player, helper.absolutePos(new BlockPos(2, BASE_ROW + index, 2)));
                    }
                    byHand.set(reference.stop());
                    if (byHand.get().getOrDefault("deepcharter:fungal_stalk", 0) != COLUMN_HEIGHT - BROKEN_SEGMENT) {
                        throw failure(
                                helper,
                                "breaking %s segments by hand dropped %s",
                                COLUMN_HEIGHT - BROKEN_SEGMENT,
                                byHand.get());
                    }
                    clearArea(helper, COLUMN_HEIGHT);
                    // The collapse: one break.
                    column(helper, 2, 2, COLUMN_HEIGHT);
                    collapse.set(ItemSpawns.start(helper, stalkRegion(helper)));
                    breakByHand(helper, player, helper.absolutePos(new BlockPos(2, BASE_ROW + BROKEN_SEGMENT, 2)));
                })
                .thenIdle(COLLAPSE_WAIT)
                .thenExecute(() -> {
                    for (int index = 0; index < COLUMN_HEIGHT; index++) {
                        boolean stands = isStalkBlock(
                                level.getBlockState(helper.absolutePos(new BlockPos(2, BASE_ROW + index, 2))));
                        if (stands != (index < BROKEN_SEGMENT)) {
                            throw failure(
                                    helper,
                                    "segment %s of the column %s after the break",
                                    index,
                                    stands ? "still stands" : "is gone");
                        }
                    }
                    if (!level.getBlockState(helper.absolutePos(new BlockPos(2, FLOOR_ROW, 2)))
                            .is(Blocks.STONE)) {
                        throw failure(helper, "the floor under the column was touched");
                    }
                    Map<String, Integer> collapsed = collapse.get().stop();
                    if (!collapsed.equals(byHand.get())) {
                        throw countMismatch(helper, collapse.get(), collapsed, byHand.get());
                    }
                })
                .thenSucceed();
    }

    /** The top of the column is the whole of what falls when the break is the second block from the top, and the sag is not instant. */
    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void theColumnSagsBeforeItBursts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clearArea(helper, COLUMN_HEIGHT);
        MockPlayer player = farPlayer(helper, "stalk-sag");
        column(helper, 2, 2, COLUMN_HEIGHT);
        breakByHand(helper, player, helper.absolutePos(new BlockPos(2, BASE_ROW + BROKEN_SEGMENT, 2)));
        BlockPos top = helper.absolutePos(new BlockPos(2, BASE_ROW + COLUMN_HEIGHT - 1, 2));
        if (!isStalkBlock(level.getBlockState(top))) {
            throw failure(helper, "the top of the column fell in the tick of the break, with no sag");
        }
        helper.runAfterDelay(COLLAPSE_WAIT, () -> {
            if (isStalkBlock(level.getBlockState(top))) {
                throw failure(helper, "the top of the column still stands after the collapse");
            }
            helper.succeed();
        });
    }

    /** SPEC: the rules are never a mystery. The puff is atmosphere, so a player beside the stalk takes no damage and no status effect. */
    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void theCollapseDoesNothingToAPlayerBeside(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clearArea(helper, COLUMN_HEIGHT);
        MockPlayer player = MockPlayers.join(helper, "stalk-beside");
        player.player().setGameMode(GameType.SURVIVAL);
        player.teleportTo(level, Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, BASE_ROW, 2))), 0, 0);
        column(helper, 2, 2, COLUMN_HEIGHT);
        float health = player.player().getHealth();
        breakByHand(helper, player, helper.absolutePos(new BlockPos(2, BASE_ROW + BROKEN_SEGMENT, 2)));
        helper.runAfterDelay(COLLAPSE_WAIT, () -> {
            if (isStalkBlock(
                    level.getBlockState(helper.absolutePos(new BlockPos(2, BASE_ROW + COLUMN_HEIGHT - 1, 2))))) {
                throw failure(helper, "the column did not collapse");
            }
            if (player.player().getHealth() != health || !player.player().isAlive()) {
                throw failure(
                        helper,
                        "the collapse hurt the player: health %s of %s",
                        player.player().getHealth(),
                        health);
            }
            if (!player.player().getActiveEffects().isEmpty()) {
                throw failure(
                        helper,
                        "the collapse gave the player %s",
                        player.player().getActiveEffects());
            }
            helper.succeed();
        });
    }

    /** Breaking the top block, or a block with nothing above it, starts no collapse; neither does a stalk lying on its side. */
    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void breakingTheTopOfAColumnLeavesTheRestStanding(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clearArea(helper, COLUMN_HEIGHT);
        MockPlayer player = farPlayer(helper, "stalk-top");
        column(helper, 2, 2, COLUMN_HEIGHT);
        breakByHand(helper, player, helper.absolutePos(new BlockPos(2, BASE_ROW + COLUMN_HEIGHT - 1, 2)));
        helper.runAfterDelay(COLLAPSE_WAIT, () -> {
            for (int index = 0; index < COLUMN_HEIGHT - 1; index++) {
                if (!isStalkBlock(level.getBlockState(helper.absolutePos(new BlockPos(2, BASE_ROW + index, 2))))) {
                    throw failure(helper, "segment %s fell when only the top was broken", index);
                }
            }
            helper.succeed();
        });
    }

    /** The chunk-local x or z of the middle of a chunk, give or take the 3 x 3 footprint. */
    private static final int MID_CHUNK = 6;

    /** The tallest a great stalk grows, with air above it, for the tests that clear an area for one. */
    private static final int GREAT_CLEAR_HEIGHT = 22;

    /**
     * The placer writes by block position through its level, never by chunk, so a great stalk whose trunks straddle a chunk border is
     * as whole as any. Built in the air above the test, off any neighbour.
     */
    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void aGreatStalkOnAChunkBorderIsWhole(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos here = helper.absolutePos(new BlockPos(0, BASE_ROW, 0)).above(SKY);
        BlockPos origin = new BlockPos(((here.getX() >> 4) << 4) + 15, here.getY(), here.getZ());
        GreatStalkLayout layout = layoutOf(BRAID_SEED);
        clearGreatArea(level, origin);
        if (!GreatStalk.place(level, RandomSource.create(BRAID_SEED), origin, false)) {
            throw failure(helper, "the placer would not grow a great stalk on a chunk border");
        }
        Map<Cell, Part> grown = new HashMap<>();
        for (int x = -BRAID_MAX_REACH; x <= BRAID_MAX_REACH; x++) {
            for (int z = -BRAID_MAX_REACH; z <= BRAID_MAX_REACH; z++) {
                for (int y = 0; y <= GREAT_CLEAR_HEIGHT; y++) {
                    Part part = partOf(level.getBlockState(origin.offset(x, y, z)));
                    if (part != null) {
                        grown.put(new Cell(x, y, z), part);
                    }
                }
            }
        }
        boolean straddles = layout.parts().keySet().stream()
                .anyMatch(cell -> ((origin.getX() + cell.x()) >> 4) != (origin.getX() >> 4));
        if (!straddles || !grown.equals(layout.parts())) {
            throw failure(
                    helper,
                    "a great stalk on a chunk border: straddles %s, %s blocks grew of the %s laid out",
                    straddles,
                    grown.size(),
                    layout.parts().size());
        }
        helper.succeed();
    }

    /**
     * Grown to stay in its chunk (#972), a great stalk puts no block outside the chunk of its origin, trunk or cap: from origins stepped
     * towards the chunk's east side, it grows whole where it fits, leaves out the caps that would reach over, and refuses where a trunk
     * would. Built in the air above the test, as {@link #aGreatStalkOnAChunkBorderIsWhole} is.
     */
    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void aGreatStalkGrownInItsChunkStaysInIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos here = helper.absolutePos(new BlockPos(0, BASE_ROW, 0)).above(SKY);
        int chunkX = here.getX() >> 4;
        // Mid-chunk along z, so only the east side is reached for.
        int z = ((here.getZ() >> 4) << 4) + MID_CHUNK;
        GreatStalkLayout layout = layoutOf(BRAID_SEED);
        int grew = 0;
        int reachedOver = 0;
        for (int local = 15 - BRAID_MAX_REACH; local <= 15; local++) {
            BlockPos origin = new BlockPos((chunkX << 4) + local, here.getY(), z);
            clearGreatArea(level, origin);
            boolean overSide = layout.parts().keySet().stream()
                    .anyMatch(cell -> ((origin.getX() + cell.x()) >> 4) != chunkX
                            || ((origin.getZ() + cell.z()) >> 4) != (origin.getZ() >> 4));
            if (overSide) {
                reachedOver++;
            }
            if (GreatStalk.place(level, RandomSource.create(BRAID_SEED), origin, true)) {
                grew++;
            }
            for (int dx = -BRAID_MAX_REACH; dx <= BRAID_MAX_REACH; dx++) {
                for (int dz = -BRAID_MAX_REACH; dz <= BRAID_MAX_REACH; dz++) {
                    for (int y = 0; y <= GREAT_CLEAR_HEIGHT; y++) {
                        BlockPos pos = origin.offset(dx, y, dz);
                        if (partOf(level.getBlockState(pos)) != null
                                && !ChunkPos.containing(pos).equals(ChunkPos.containing(origin))) {
                            throw failure(
                                    helper,
                                    "a great stalk grown in its chunk from %s put %s at %s, in chunk %s",
                                    origin.toShortString(),
                                    level.getBlockState(pos),
                                    pos.toShortString(),
                                    ChunkPos.containing(pos));
                        }
                    }
                }
            }
        }
        clearGreatArea(level, new BlockPos((chunkX << 4) + 15, here.getY(), z));
        if (grew == 0 || reachedOver == 0) {
            throw failure(
                    helper,
                    "of %s origins the stalk grew from %s and its layout reached over a side from %s: both must be some",
                    BRAID_MAX_REACH + 1,
                    grew,
                    reachedOver);
        }
        helper.succeed();
    }

    /** Stone under, and air over, the reach of a great stalk at {@code origin}, so each try starts from bare ground. */
    private static void clearGreatArea(ServerLevel level, BlockPos origin) {
        for (int x = -BRAID_MAX_REACH; x <= BRAID_MAX_REACH; x++) {
            for (int z = -BRAID_MAX_REACH; z <= BRAID_MAX_REACH; z++) {
                level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = 0; y <= GREAT_CLEAR_HEIGHT; y++) {
                    level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /** No room above the ground for a trunk, and no great stalk: a block of stone where one of its trunks would stand. */
    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void aGreatStalkNeedsHeadroomForItsTrunks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GreatStalkLayout layout = layoutOf(1);
        Cell high = layout.parts().keySet().stream()
                .filter(cell -> cell.y() == GreatStalkBraid.LEAST_HEIGHT - 1
                        && layout.parts().get(cell) == Part.TRUNK)
                .findFirst()
                .orElseThrow();
        clearBraidArea(helper);
        level.setBlock(at(braidOrigin(helper), high), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        if (GreatStalk.place(level, RandomSource.create(1), braidOrigin(helper), false)
                || !standing(helper).isEmpty()) {
            throw failure(helper, "a great stalk grew under a roof lower than its trunks");
        }
        helper.succeed();
    }

    /** The feature that grows the stalks makes a great one rarely: more than never, and well under one in ten. */
    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void greatStalksAreRare(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Feature feature = level.registryAccess()
                .lookupOrThrow(Registries.FEATURE)
                .getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "fungal_stalk_column"));
        if (feature == null) {
            throw failure(helper, "the feature deepcharter:fungal_stalk_column is not registered");
        }
        int great = 0;
        RandomSource random = RandomSource.create(RATE_SEED);
        for (int attempt = 0; attempt < RATE_ATTEMPTS; attempt++) {
            clearArea(helper, GREAT_CLEAR_HEIGHT);
            if (!feature.place(
                    level,
                    level.getChunkSource().getGenerator(),
                    random,
                    helper.absolutePos(new BlockPos(3, BASE_ROW, 3)))) {
                throw failure(helper, "attempt %s: the feature placed nothing on a flat stone floor", attempt);
            }
            boolean isGreat = stalkBlocks(helper).stream()
                    .anyMatch(pos -> level.getBlockState(pos).is(ours("great_stalk")));
            if (isGreat) {
                great++;
            } else if (stalkBlocks(helper).size() > SurfaceTuning.DEFAULT.ordinaryMaxHeight()) {
                throw failure(
                        helper,
                        "attempt %s: an ordinary column is taller than %s",
                        attempt,
                        SurfaceTuning.DEFAULT.ordinaryMaxHeight());
            }
        }
        if (great == 0 || great > RATE_ATTEMPTS / 10) {
            throw failure(
                    helper,
                    "%s of %s stalks were great, expected at least 1 and at most %s",
                    great,
                    RATE_ATTEMPTS,
                    RATE_ATTEMPTS / 10);
        }
        helper.succeed();
    }

    // ---- The braid, the great stalk's shape (#910). ----

    /** Where the braid's origin stands in the test area: far enough from every edge that its widest reach (8 blocks) is cleared. */
    private static final int BRAID_AT = 9;

    /** Chunks east of the test, far enough that none of the test's own are near, whose neighbour is not loaded. */
    private static final int UNLOADED_CHUNKS_AWAY = 4000;

    private static final int BRAID_SEEDS = 40;
    private static final int BRAID_PLACED_SEEDS = 6;
    private static final int BRAID_CLEAR_HEIGHT = 22;
    /** The most any block of the braid may stand from its origin horizontally: the placer writes the 3x3 chunks round the origin's. */
    private static final int BRAID_MAX_REACH = 8;

    /**
     * The tests that count drops wait for every chunk this far from the test's origin to be entity-ticking before they start recording,
     * so that no item of an earlier test that never ticked here loads (and spawns, to the recording) during the count (#952). It is the
     * far edge of the braid area (the braid's origin and the {@value #BRAID_AT} blocks past it that {@link #clearBraidArea} clears), so
     * it holds every spawn of the braid and the single column.
     */
    private static final int AWAIT_REACH = 2 * BRAID_AT;

    private static final int YIELD_LEAST = 50;
    private static final int YIELD_MOST = 90;
    private static final long BRAID_SEED = 3;
    private static final String BRAID_TRUNK_ALONE = "deepcharter-test:stalk_count_braid_trunk";

    /** The trunk a cell of the layout belongs to. */
    private static int ownerOf(GreatStalkLayout layout, Cell cell) {
        return Objects.requireNonNull(layout.owners().get(cell), "every cell of a layout has an owner");
    }

    private static GreatStalkLayout layoutOf(long seed) {
        return GreatStalkBraid.layout(RandomSource.create(seed)::nextInt);
    }

    private static BlockPos braidOrigin(GameTestHelper helper) {
        return helper.absolutePos(new BlockPos(BRAID_AT, BASE_ROW, BRAID_AT));
    }

    private static BlockPos at(BlockPos origin, Cell cell) {
        return origin.offset(cell.x(), cell.y(), cell.z());
    }

    /** A flat stone floor under the area the braid may grow in, and nothing above it. */
    private static void clearBraidArea(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = braidOrigin(helper);
        for (int dx = -BRAID_AT; dx <= BRAID_AT; dx++) {
            for (int dz = -BRAID_AT; dz <= BRAID_AT; dz++) {
                level.setBlock(origin.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = 0; y <= BRAID_CLEAR_HEIGHT; y++) {
                    level.setBlock(origin.offset(dx, y, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /** The braid area above its floor, absolute: where the braid and its burst cells are. */
    private static AABB braidRegion(GameTestHelper helper) {
        BlockPos origin = braidOrigin(helper);
        return new AABB(origin.offset(-BRAID_AT, 0, -BRAID_AT))
                .minmax(new AABB(origin.offset(BRAID_AT, BRAID_CLEAR_HEIGHT, BRAID_AT)));
    }

    private static @Nullable Part partOf(BlockState state) {
        if (state.is(ours("great_stalk"))) {
            return Part.TRUNK;
        }
        if (state.is(ours("great_stalk_cap"))) {
            return Part.CAP;
        }
        return null;
    }

    /** The stalk blocks standing in the braid area, by their cell relative to the braid's origin. */
    private static Map<Cell, Part> standing(GameTestHelper helper) {
        BlockPos origin = braidOrigin(helper);
        Map<Cell, Part> found = new HashMap<>();
        for (int dx = -BRAID_AT; dx <= BRAID_AT; dx++) {
            for (int dz = -BRAID_AT; dz <= BRAID_AT; dz++) {
                for (int y = 0; y <= BRAID_CLEAR_HEIGHT; y++) {
                    Part part = partOf(helper.getLevel().getBlockState(origin.offset(dx, y, dz)));
                    if (part != null) {
                        found.put(new Cell(dx, y, dz), part);
                    }
                }
            }
        }
        return found;
    }

    /**
     * Holds the chunks the braid area covers loaded: a collapse never loads a chunk beside the one it is in, so a stalk that reaches
     * past the test's own chunks needs them held.
     */
    private static void holdBraidArea(GameTestHelper helper) {
        BlockPos origin = braidOrigin(helper);
        for (int dx : new int[] {-BRAID_AT, BRAID_AT}) {
            for (int dz : new int[] {-BRAID_AT, BRAID_AT}) {
                FarChunks.force(helper, helper.getLevel(), (origin.getX() + dx) >> 4, (origin.getZ() + dz) >> 4);
            }
        }
    }

    private static boolean growBraid(GameTestHelper helper, long seed) {
        clearBraidArea(helper);
        return GreatStalk.place(helper.getLevel(), RandomSource.create(seed), braidOrigin(helper), false);
    }

    private static boolean rotationallySymmetric(Set<Cell> cells) {
        Set<Cell> flat = normalised(cells, 0);
        for (int turns = 1; turns < 4; turns++) {
            if (normalised(cells, turns).equals(flat)) {
                return true;
            }
        }
        return false;
    }

    /** The cells turned a quarter turn {@code turns} times about the vertical axis, moved so that the lowest x and z are 0. */
    private static Set<Cell> normalised(Set<Cell> cells, int turns) {
        List<Cell> turned = new ArrayList<>();
        for (Cell cell : cells) {
            int x = cell.x();
            int z = cell.z();
            for (int turn = 0; turn < turns; turn++) {
                int swap = x;
                x = -z;
                z = swap;
            }
            turned.add(new Cell(x, cell.y(), z));
        }
        int lowX = turned.stream().mapToInt(Cell::x).min().orElse(0);
        int lowZ = turned.stream().mapToInt(Cell::z).min().orElse(0);
        Set<Cell> moved = new HashSet<>();
        for (Cell cell : turned) {
            moved.add(new Cell(cell.x() - lowX, cell.y(), cell.z() - lowZ));
        }
        return moved;
    }

    /**
     * What the braid generator promises: every block is joined to the ground, stands within {@value #BRAID_MAX_REACH} blocks of the
     * origin, the whole is in the yield of a great stalk, the stalk is not rotationally symmetric, and two independent trunks never
     * touch. It grows in the world as the generator says.
     */
    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void aBraidKeepsItsPromises(GameTestHelper helper) {
        Set<Integer> yields = new HashSet<>();
        for (long seed = 1; seed <= BRAID_SEEDS; seed++) {
            GreatStalkLayout layout = layoutOf(seed);
            Set<Cell> cells = layout.parts().keySet();
            yields.add(cells.size());
            if (cells.size() < YIELD_LEAST || cells.size() > YIELD_MOST) {
                throw failure(
                        helper, "seed %s: %s blocks, outside %s to %s", seed, cells.size(), YIELD_LEAST, YIELD_MOST);
            }
            if (layout.without(Set.of()).parts().size() != cells.size()) {
                throw failure(helper, "seed %s: a block is not joined to the ground", seed);
            }
            for (Cell cell : cells) {
                if (Math.abs(cell.x()) > BRAID_MAX_REACH || Math.abs(cell.z()) > BRAID_MAX_REACH || cell.y() < 0) {
                    throw failure(helper, "seed %s: a block at %s is out of bounds", seed, cell);
                }
                if (cell.y() == 0 && layout.parts().get(cell) != Part.TRUNK) {
                    throw failure(
                            helper,
                            "seed %s: a %s at the base, %s",
                            seed,
                            layout.parts().get(cell),
                            cell);
                }
                int owner = ownerOf(layout, cell);
                for (Cell next : List.of(
                        new Cell(cell.x() + 1, cell.y(), cell.z()),
                        new Cell(cell.x(), cell.y() + 1, cell.z()),
                        new Cell(cell.x(), cell.y(), cell.z() + 1))) {
                    if (cells.contains(next) && ownerOf(layout, next) != owner) {
                        throw failure(
                                helper,
                                "seed %s: trunks %s and %s touch at %s",
                                seed,
                                owner,
                                ownerOf(layout, next),
                                cell);
                    }
                }
            }
            if (rotationallySymmetric(cells)) {
                throw failure(helper, "seed %s: the stalk is rotationally symmetric", seed);
            }
        }
        if (yields.size() < 3) {
            throw failure(helper, "every seed grew the same few shapes, block counts %s", yields);
        }
        for (long seed = 1; seed <= BRAID_PLACED_SEEDS; seed++) {
            GreatStalkLayout layout = layoutOf(seed);
            if (!growBraid(helper, seed)) {
                throw failure(helper, "seed %s: the placer would not grow it on a flat stone floor", seed);
            }
            if (!standing(helper).equals(layout.parts())) {
                throw failure(helper, "seed %s: what grew is not what the generator laid out", seed);
            }
        }
        helper.succeed();
    }

    /**
     * No ground under a trunk, or no room for one, and nothing grows; a cap or a root with no room is left out, with what then hangs
     * from nothing.
     */
    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void aBraidNeedsGroundAndRoomForItsTrunks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        {
            GreatStalkLayout layout = layoutOf(1);
            Cell trunk = layout.parts().keySet().stream()
                    .filter(cell -> cell.y() == 1 && layout.parts().get(cell) == Part.TRUNK)
                    .findFirst()
                    .orElseThrow();
            Cell base = new Cell(trunk.x(), 0, trunk.z());
            clearBraidArea(helper);
            level.setBlock(at(braidOrigin(helper), trunk), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            if (GreatStalk.place(level, RandomSource.create(1), braidOrigin(helper), false)
                    || !standing(helper).isEmpty()) {
                throw failure(helper, "the braid grew over a block in its trunk");
            }
            clearBraidArea(helper);
            level.setBlock(at(braidOrigin(helper), base).below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            if (layout.parts().containsKey(base)
                    && !layout.optional().contains(base)
                    && (GreatStalk.place(level, RandomSource.create(1), braidOrigin(helper), false)
                            || !standing(helper).isEmpty())) {
                throw failure(helper, "the braid grew a trunk over a hole in the ground");
            }
            Cell cap = layout.parts().entrySet().stream()
                    .filter(entry -> entry.getValue() == Part.CAP)
                    .map(Map.Entry::getKey)
                    .max(Comparator.comparingInt(Cell::y))
                    .orElseThrow();
            clearBraidArea(helper);
            level.setBlock(at(braidOrigin(helper), cap), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            if (!GreatStalk.place(level, RandomSource.create(1), braidOrigin(helper), false)) {
                throw failure(helper, "the braid would not grow with a block in the way of one cap");
            }
            Set<Cell> expected =
                    new HashSet<>(layout.without(Set.of(cap)).parts().keySet());
            if (!standing(helper).keySet().equals(expected)
                    || !level.getBlockState(at(braidOrigin(helper), cap)).is(Blocks.STONE)) {
                throw failure(helper, "the braid did not leave out exactly the capped cell in its way");
            }
        }
        helper.succeed();
    }

    /**
     * What falls when {@code broken} is broken, by the rule of {@link StalkCollapse}, stated on the layout: if a block stands above it,
     * every block above it that belongs to the same trunk (every block, for a cell of the stalk as a whole), and whatever then no longer
     * touches the ground. Not the block broken.
     */
    private static Set<Cell> fallenBy(GreatStalkLayout layout, Cell broken) {
        int owner = ownerOf(layout, broken);
        boolean hasAbove = layout.parts().containsKey(new Cell(broken.x(), broken.y() + 1, broken.z()));
        Map<Cell, Part> left = new LinkedHashMap<>(layout.parts());
        left.remove(broken);
        Set<Cell> fallen = new HashSet<>();
        if (hasAbove) {
            for (Cell cell : left.keySet()) {
                if (cell.y() > broken.y() && ownerOf(layout, cell) == owner) {
                    fallen.add(cell);
                }
            }
        }
        left.keySet().removeAll(fallen);
        Set<Cell> grounded = new GreatStalkLayout(left, layout.owners(), layout.optional())
                .without(Set.of())
                .parts()
                .keySet();
        for (Cell cell : left.keySet()) {
            if (!grounded.contains(cell)) {
                fallen.add(cell);
            }
        }
        return fallen;
    }

    private static List<Cell> trunkCellsWithAbove(GreatStalkLayout layout, int owner, int level) {
        return layout.parts().keySet().stream()
                .filter(cell -> cell.y() == level
                        && layout.parts().get(cell) == Part.TRUNK
                        && ownerOf(layout, cell) == owner
                        && layout.parts().containsKey(new Cell(cell.x(), cell.y() + 1, cell.z())))
                .sorted(Comparator.comparingInt(Cell::x).thenComparingInt(Cell::z))
                .toList();
    }

    /** The drops of breaking each of these blocks by hand, by item id. */
    private static Map<String, Integer> handDrops(GameTestHelper helper, Collection<Cell> cells) {
        Map<String, Integer> total = new TreeMap<>();
        for (Cell cell : cells) {
            BlockPos pos = at(braidOrigin(helper), cell);
            for (ItemStack drop : Block.getDrops(helper.getLevel().getBlockState(pos), helper.getLevel(), pos, null)) {
                total.merge(BuiltInRegistries.ITEM.getKey(drop.getItem()).toString(), drop.getCount(), Integer::sum);
            }
        }
        return total;
    }

    /**
     * Grows the braid, breaks one block by hand and checks the collapse against {@link #fallenBy}: exactly those blocks are gone, nothing
     * stands that is not joined to the ground, and the drops are what breaking each of them by hand drops. With {@code countDrops}, the
     * drops are counted where they spawn in {@link #braidRegion}, and the test runs alone in its environment.
     */
    private void braidCollapse(
            GameTestHelper helper, long seed, Function<GreatStalkLayout, Cell> pick, boolean countDrops) {
        GreatStalkLayout layout = layoutOf(seed);
        Cell broken = pick.apply(layout);
        Set<Cell> fallen = fallenBy(layout, broken);
        Set<Cell> everything = new HashSet<>(fallen);
        everything.add(broken);
        holdBraidArea(helper);
        Runnable countable = countDrops ? countable(helper) : () -> {};
        MockPlayer player = farPlayer(helper, "braid-collapse");
        AtomicReference<Map<String, Integer>> byHand = new AtomicReference<>();
        AtomicReference<ItemSpawns> collapse = new AtomicReference<>();
        helper.startSequence()
                .thenWaitUntil(countable)
                .thenExecute(() -> {
                    if (!growBraid(helper, seed)) {
                        throw failure(helper, "the braid would not grow on a flat stone floor");
                    }
                    byHand.set(handDrops(helper, everything));
                    if (byHand.get().getOrDefault("deepcharter:fungal_stalk", 0) != everything.size()
                            || byHand.get().size() != 1) {
                        throw failure(
                                helper, "breaking %s blocks by hand would drop %s", everything.size(), byHand.get());
                    }
                    if (countDrops) {
                        collapse.set(ItemSpawns.start(helper, braidRegion(helper)));
                    }
                    breakByHand(helper, player, at(braidOrigin(helper), broken));
                })
                .thenIdle(COLLAPSE_WAIT)
                .thenExecute(() -> {
                    Set<Cell> expected = new HashSet<>(layout.parts().keySet());
                    expected.removeAll(everything);
                    Map<Cell, Part> after = standing(helper);
                    if (!after.keySet().equals(expected)) {
                        Set<Cell> missing = new HashSet<>(expected);
                        missing.removeAll(after.keySet());
                        Set<Cell> extra = new HashSet<>(after.keySet());
                        extra.removeAll(expected);
                        throw failure(
                                helper,
                                "after breaking %s, %s blocks should fall and %s did not fall (%s), %s fell that should stand (%s)",
                                broken,
                                fallen.size(),
                                extra.size(),
                                extra,
                                missing.size(),
                                missing);
                    }
                    if (new GreatStalkLayout(after, Map.of(), Set.of())
                                    .without(Set.of())
                                    .parts()
                                    .size()
                            != after.size()) {
                        throw failure(helper, "a block floats, joined to nothing, after the collapse");
                    }
                    if (countDrops) {
                        Map<String, Integer> collapsed = collapse.get().stop();
                        if (!collapsed.equals(byHand.get())) {
                            throw countMismatch(helper, collapse.get(), collapsed, byHand.get());
                        }
                    }
                })
                .thenSucceed();
    }

    /** A braid's trunk breaks: its upper part, with the shelves on it, falls; the other two trunks and the lower part stand. */
    @GameTest(maxTicks = COUNT_MAX_TICKS, environment = BRAID_TRUNK_ALONE)
    public void breakingOneTrunkOfABraidLeavesTheOthersStanding(GameTestHelper helper) {
        braidCollapse(
                helper, BRAID_SEED, layout -> trunkCellsWithAbove(layout, 2, 7).getFirst(), true);
    }

    /**
     * The kink of a thin trunk: the cell under a sideways step has nothing above it, yet the trunk beyond the step hung from it. Breaking
     * it brings that trunk down; none of it is left floating.
     */
    private static Function<GreatStalkLayout, Cell> kinkOf(int owner) {
        return layout -> layout.parts().keySet().stream()
                .filter(cell -> ownerOf(layout, cell) == owner
                        && layout.parts().get(cell) == Part.TRUNK
                        && cell.y() > 3
                        && !layout.parts().containsKey(new Cell(cell.x(), cell.y() + 1, cell.z()))
                        && layout.parts().containsKey(new Cell(cell.x(), cell.y() - 1, cell.z()))
                        && sideStep(layout, cell))
                .min(Comparator.comparingInt(Cell::y).thenComparingInt(Cell::x).thenComparingInt(Cell::z))
                .orElseThrow();
    }

    /** Whether a trunk cell beside {@code cell}, at its level, has a cell above it: the trunk goes on from there. */
    private static boolean sideStep(GreatStalkLayout layout, Cell cell) {
        for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            Cell beside = new Cell(cell.x() + side[0], cell.y(), cell.z() + side[1]);
            if (layout.parts().get(beside) == Part.TRUNK
                    && layout.parts().containsKey(new Cell(beside.x(), beside.y() + 1, beside.z()))) {
                return true;
            }
        }
        return false;
    }

    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void breakingTheKinkOfABraidTrunkLeavesNothingFloating(GameTestHelper helper) {
        braidCollapse(helper, BRAID_SEED, kinkOf(1), false);
    }

    /** A shelf breaks off its trunk's side: its outer half, no longer joined to anything, falls, and the strand hanging from a cap with it. */
    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void breakingAShelfBringsDownWhatOnlyHungFromIt(GameTestHelper helper) {
        braidCollapse(
                helper,
                BRAID_SEED,
                layout -> layout.parts().keySet().stream()
                        .filter(cell -> layout.parts().get(cell) == Part.CAP && cell.y() == 7)
                        .min(Comparator.comparingInt(Cell::x).thenComparingInt(Cell::z))
                        .orElseThrow(),
                false);
    }

    /**
     * A player in the shade of a shelf or a cap, at the foot of the trunk it hangs from, takes nothing when that trunk comes down: a
     * block that would land in the player's cells bursts instead.
     */
    private void braidNeverLandsOnAPlayer(GameTestHelper helper, long seed) {
        ServerLevel level = helper.getLevel();
        GreatStalkLayout layout = layoutOf(seed);
        // The cap block that hangs lowest over free ground, and the trunk base it hangs from.
        Cell shade = layout.parts().keySet().stream()
                .filter(cell -> layout.parts().get(cell) == Part.CAP
                        && cell.y() >= 2
                        && !layout.parts().containsKey(new Cell(cell.x(), 0, cell.z()))
                        && !layout.parts().containsKey(new Cell(cell.x(), 1, cell.z())))
                .min(Comparator.comparingInt(Cell::y).thenComparingInt(Cell::x).thenComparingInt(Cell::z))
                .orElseThrow();
        int owner = ownerOf(layout, shade);
        Cell base = layout.parts().keySet().stream()
                .filter(cell -> cell.y() == 0 && !layout.optional().contains(cell) && ownerOf(layout, cell) == owner)
                .min(Comparator.comparingInt(Cell::x).thenComparingInt(Cell::z))
                .orElseThrow();
        holdBraidArea(helper);
        if (!growBraid(helper, seed)) {
            throw failure(helper, "the braid would not grow on a flat stone floor");
        }
        BlockPos origin = braidOrigin(helper);
        MockPlayer player = MockPlayers.join(helper, "braid-foot");
        player.player().setGameMode(GameType.SURVIVAL);
        player.teleportTo(level, Vec3.atBottomCenterOf(origin.offset(shade.x(), 0, shade.z())), 0, 0);
        BlockPos feet = player.player().blockPosition();
        float health = player.player().getHealth();
        TestTicks.each(helper, () -> {
            for (BlockPos cell : List.of(feet, feet.above())) {
                if (!level.getBlockState(cell).isAir()) {
                    throw failure(
                            helper,
                            "a block %s landed in the player's cell %s",
                            level.getBlockState(cell),
                            cell.toShortString());
                }
            }
        });
        breakByHand(helper, player, at(origin, base));
        helper.runAfterDelay(COLLAPSE_WAIT, () -> {
            Set<Cell> expected = new HashSet<>(layout.parts().keySet());
            expected.removeAll(fallenBy(layout, base));
            expected.remove(base);
            Set<Cell> after = standing(helper).keySet();
            if (!after.equals(expected)) {
                Set<Cell> extra = new HashSet<>(after);
                extra.removeAll(expected);
                Set<Cell> missing = new HashSet<>(expected);
                missing.removeAll(after);
                throw failure(
                        helper,
                        "the braid did not collapse as it should after breaking %s: %s stand that should not, %s are gone that should stand",
                        base,
                        extra,
                        missing);
            }
            if (player.player().getHealth() != health) {
                throw failure(
                        helper,
                        "the player lost health: %s of %s",
                        player.player().getHealth(),
                        health);
            }
            helper.succeed();
        });
    }

    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void aSagBraidNeverLandsOnAPlayerAtItsFoot(GameTestHelper helper) {
        braidNeverLandsOnAPlayer(helper, BRAID_SEED);
    }

    /** The great block's {@code falling} state, found by name: the property is the collapse's own. */
    private static BlockState falling(Block block) {
        BooleanProperty property = (BooleanProperty) block.getStateDefinition().getProperty("falling");
        return block.defaultBlockState()
                .setValue(Objects.requireNonNull(property, "great blocks have a falling state"), true);
    }

    /**
     * #876: a collapse never loads a chunk beside the one it is in. A great stalk on the east edge of a chunk whose east neighbour is not
     * loaded is broken (and a falling block of it broken by hand); the neighbour is still not loaded after.
     */
    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void aCollapseAtAChunkEdgeNeverLoadsTheChunkBeside(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos here = helper.absolutePos(new BlockPos(0, BASE_ROW, 0));
        int chunkX = (here.getX() >> 4) + UNLOADED_CHUNKS_AWAY;
        int chunkZ = here.getZ() >> 4;
        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        if (level.getChunkSource().hasChunk(chunkX + 1, chunkZ)) {
            throw failure(helper, "the chunk beside the test chunk is loaded before the test starts");
        }
        BlockPos edge = new BlockPos((chunkX << 4) + 15, here.getY(), chunkZ << 4);
        // A removal is announced to the block as the world does it, but without changing the world: vanilla's own light
        // and neighbour
        // updates across the edge load the chunk beside it, and are not the collapse's.
        for (Block block : List.of(ours("great_stalk"), ours("great_stalk_cap"))) {
            for (boolean fallingState : new boolean[] {false, true}) {
                chunk.setBlockState(edge, Blocks.AIR.defaultBlockState(), 0);
                BlockState removed = fallingState ? falling(block) : block.defaultBlockState();
                removed.affectNeighborsAfterRemoval(level, edge, false);
                if (level.getChunkSource().hasChunk(chunkX + 1, chunkZ)) {
                    throw failure(
                            helper,
                            "breaking %s (falling %s) at a chunk's edge loaded the chunk beside it",
                            block,
                            fallingState);
                }
            }
        }
        helper.succeed();
    }

    /** A falling great block that nothing is collapsing (a command placed it) comes down on its own, and does not stay in the air. */
    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void aStrayFallingBlockComesDown(GameTestHelper helper) {
        clearBraidArea(helper);
        BlockPos pos = braidOrigin(helper).above(3);
        helper.getLevel().setBlock(pos, falling(ours("great_stalk")), Block.UPDATE_ALL);
        helper.runAfterDelay(COLLAPSE_WAIT, () -> {
            if (!helper.getLevel().getBlockState(pos).isAir()) {
                throw failure(helper, "a stray falling block still hangs in the air");
            }
            helper.succeed();
        });
    }
}
