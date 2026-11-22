package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.surface.GreatStalk;
import io.github.pkeppeler.deepcharter.surface.SurfaceTuning;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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
    /** Longer than a collapse takes: the sag delay plus one step for each layer of the tallest great stalk and its cap. */
    private static final int COLLAPSE_WAIT = 200;

    /**
     * Where the far player stands, past the item box. A drop flies out at {@link SurfaceTuning#scatterSpeed()} and comes to rest within
     * about 3 blocks of the block that burst it, so within about 9 blocks of a great stalk's trunk.
     */
    private static final int FAR_Z = 20;

    /**
     * Items are counted this far from the test's origin on each side and above and below it, past where drops come to rest (the great
     * stalk's trunk stands at 3 to 4, its rim at 6, and the drops rest within about 3 blocks of that). An item in an entity section
     * that is not yet accessible is invisible to the count and to {@link #clearArea}, and shows up when the section loads (#952), so
     * the two tests that count items wait for every chunk of this box ({@link #countable}) before they clear it, and count only
     * items {@link #MAX_ITEM_AGE} ticks old or less, which no leftover of an earlier test that never ticked here can be.
     */
    private static final int ITEM_REACH = 12;

    /** The oldest an item of this test can be when it is counted: it was dropped after the break, {@link #COLLAPSE_WAIT} ticks before. */
    private static final int MAX_ITEM_AGE = COLLAPSE_WAIT + 20;

    private static final int ITEM_DEPTH = 20;
    private static final int ITEM_HEIGHT = 60;
    private static final String SINGLE_ALONE = "deepcharter-test:stalk_count_single";
    private static final String GREAT_ALONE = "deepcharter-test:stalk_count_great";
    /** How far above the test a build in the air stands, off every neighbour. */
    private static final int SKY = 40;

    private static final int COLUMN_HEIGHT = 6;
    private static final int BROKEN_SEGMENT = 2;
    private static final int TEST_MAX_TICKS = COLLAPSE_WAIT + 100;
    private static final int COUNT_MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + TEST_MAX_TICKS;
    /** The rim of a great stalk's cap reaches this far from the 2x2 trunk, and its top dome is one layer above the rim. */
    private static final int CAP_REACH = 1;
    /** Seeds for the placer's shape test and the generation rate test. */
    private static final int SHAPE_SEEDS = 24;

    private static final long SHAPE_SEED = 18;

    private static final int RATE_ATTEMPTS = 600;
    private static final long RATE_SEED = 855;
    private static final long GREAT_SEED = 7;

    private static Block ours(String path) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
    }

    /** A flat stone floor under the test's whole area, and nothing above it. */
    private static void clearArea(GameTestHelper helper, int height) {
        ServerLevel level = helper.getLevel();
        for (int x = -2; x <= 10; x++) {
            for (int z = -2; z <= 10; z++) {
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
        for (ItemEntity item : items(helper)) {
            item.discard();
        }
    }

    /**
     * Starts the wait for every chunk of the item box to be entity-ticking, and returns the check a sequence's {@code thenWaitUntil}
     * polls: it throws until they all are. Call it from the test method.
     */
    private static Runnable countable(GameTestHelper helper) {
        BlockPos low = helper.absolutePos(new BlockPos(-ITEM_REACH, 0, -ITEM_REACH));
        BlockPos high = helper.absolutePos(new BlockPos(ITEM_REACH, 0, ITEM_REACH));
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

    private static List<ItemEntity> items(GameTestHelper helper) {
        BlockPos low = helper.absolutePos(new BlockPos(-ITEM_REACH, -ITEM_DEPTH, -ITEM_REACH));
        BlockPos high = helper.absolutePos(new BlockPos(ITEM_REACH, ITEM_HEIGHT, ITEM_REACH));
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(low).minmax(new AABB(high)));
    }

    /** The items lying about that this test can have dropped, by item id. */
    private static Map<String, Integer> dropped(GameTestHelper helper) {
        Map<String, Integer> total = new TreeMap<>();
        for (ItemEntity item : items(helper)) {
            if (item.tickCount > MAX_ITEM_AGE) {
                continue;
            }
            total.merge(
                    BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString(),
                    item.getItem().getCount(),
                    Integer::sum);
        }
        return total;
    }

    /** The failure of a count that is not the by-hand count, listing every item in the box, counted or not. */
    private static RuntimeException countMismatch(
            GameTestHelper helper, Map<String, Integer> collapsed, Map<String, Integer> byHand) {
        StringBuilder items = new StringBuilder();
        for (ItemEntity item : items(helper)) {
            items.append(String.format(
                    "%n  %s x%s at %s, age %s, %s the test's bounds, %s",
                    BuiltInRegistries.ITEM.getKey(item.getItem().getItem()),
                    item.getItem().getCount(),
                    item.blockPosition().toShortString(),
                    item.tickCount,
                    helper.getBounds().contains(item.position()) ? "inside" : "outside",
                    item.tickCount > MAX_ITEM_AGE ? "too old to count" : "counted"));
        }
        return failure(
                helper,
                "the collapse dropped %s, breaking the same blocks by hand dropped %s; the items in the box:%s",
                collapsed,
                byHand,
                items);
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

    /** Places the great stalk of {@link #GREAT_SEED} with its trunk's low corner at the given block of the test area. */
    private static void greatStalk(GameTestHelper helper, int x, int z) {
        if (!GreatStalk.place(
                helper.getLevel(), RandomSource.create(GREAT_SEED), helper.absolutePos(new BlockPos(x, BASE_ROW, z)))) {
            throw failure(helper, "the placer would not grow a great stalk on a flat stone floor");
        }
    }

    private static boolean isStalkBlock(BlockState state) {
        return state.is(ours("fungal_stalk")) || state.is(ours("great_stalk")) || state.is(ours("great_stalk_cap"));
    }

    /** Every stalk block of the area, absolute. */
    private static Set<BlockPos> stalkBlocks(GameTestHelper helper) {
        Set<BlockPos> found = new HashSet<>();
        for (int x = -2; x <= 10; x++) {
            for (int z = -2; z <= 10; z++) {
                for (int y = BASE_ROW; y <= BASE_ROW + 30; y++) {
                    BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
                    if (isStalkBlock(helper.getLevel().getBlockState(pos))) {
                        found.add(pos);
                    }
                }
            }
        }
        return found;
    }

    /** How many great stalk blocks stand in the column of a block of the test area. */
    private static int trunkHeight(GameTestHelper helper, int x, int z) {
        int height = 0;
        for (int y = BASE_ROW; y <= BASE_ROW + 40; y++) {
            if (helper.getLevel()
                    .getBlockState(helper.absolutePos(new BlockPos(x, y, z)))
                    .is(ours("great_stalk"))) {
                height++;
            }
        }
        return height;
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
        helper.startSequence()
                .thenWaitUntil(countable)
                .thenExecute(() -> {
                    clearArea(helper, COLUMN_HEIGHT);
                    // The reference: every block from the break up, broken by hand from the top down, so nothing
                    // collapses.
                    column(helper, 2, 2, COLUMN_HEIGHT);
                    for (int index = COLUMN_HEIGHT - 1; index >= BROKEN_SEGMENT; index--) {
                        breakByHand(helper, player, helper.absolutePos(new BlockPos(2, BASE_ROW + index, 2)));
                    }
                    byHand.set(dropped(helper));
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
                    Map<String, Integer> collapsed = dropped(helper);
                    if (!collapsed.equals(byHand.get())) {
                        throw countMismatch(helper, collapsed, byHand.get());
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

    /** The great stalk: a break above the base brings down all four columns above it and the cap, and the drops are the by-hand drops. */
    @GameTest(maxTicks = COUNT_MAX_TICKS, environment = GREAT_ALONE)
    public void breakingAGreatStalkBringsTheWholeTrunkAboveItDown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int reach = SurfaceTuning.DEFAULT.greatMaxHeight();
        Runnable countable = countable(helper);
        MockPlayer player = farPlayer(helper, "great-collapse");
        Plan plan = new Plan();
        helper.startSequence()
                .thenWaitUntil(countable)
                .thenExecute(() -> {
                    clearArea(helper, reach + 4);
                    greatStalk(helper, 3, 3);
                    int height = trunkHeight(helper, 3, 3);
                    int breakIndex = Math.max(2, height / 2);
                    plan.broken = helper.absolutePos(new BlockPos(3, BASE_ROW + breakIndex, 3));
                    plan.before = stalkBlocks(helper);
                    for (BlockPos pos : plan.before) {
                        if (pos.getY() > plan.broken.getY() || pos.equals(plan.broken)) {
                            plan.falls.add(pos);
                        }
                    }
                    // The reference: the same blocks, broken by hand from the top down.
                    List<BlockPos> order = new ArrayList<>(plan.falls);
                    order.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed());
                    for (BlockPos pos : order) {
                        breakByHand(helper, player, pos);
                    }
                    plan.byHand = dropped(helper);
                    if (plan.byHand.getOrDefault("deepcharter:fungal_stalk", 0) != plan.falls.size()) {
                        throw failure(
                                helper,
                                "breaking %s great stalk blocks by hand dropped %s",
                                plan.falls.size(),
                                plan.byHand);
                    }
                    clearArea(helper, reach + 4);
                    greatStalk(helper, 3, 3);
                    breakByHand(helper, player, plan.broken);
                })
                .thenIdle(COLLAPSE_WAIT)
                .thenExecute(() -> {
                    Set<BlockPos> after = stalkBlocks(helper);
                    Set<BlockPos> expected = new HashSet<>(plan.before);
                    expected.removeAll(plan.falls);
                    if (!after.equals(expected)) {
                        Set<BlockPos> missing = new HashSet<>(expected);
                        missing.removeAll(after);
                        Set<BlockPos> extra = new HashSet<>(after);
                        extra.removeAll(expected);
                        throw failure(
                                helper,
                                "after the collapse %s blocks should stand, %s do; %s are gone that should stand, %s stand that should be gone",
                                expected.size(),
                                after.size(),
                                missing.size(),
                                extra.size());
                    }
                    for (BlockPos pos : after) {
                        if (pos.getY() > plan.broken.getY()) {
                            throw failure(helper, "a block above the break stands at %s", pos.toShortString());
                        }
                    }
                    Map<String, Integer> collapsed = dropped(helper);
                    if (!collapsed.equals(plan.byHand)) {
                        throw countMismatch(helper, collapsed, plan.byHand);
                    }
                    if (level.getBlockState(plan.broken).isAir()
                            && after.size() != plan.before.size() - plan.falls.size()) {
                        throw failure(helper, "the count of standing blocks is off");
                    }
                })
                .thenSucceed();
    }

    /** What the great stalk test works out in its first step, for its last. */
    private static final class Plan {
        BlockPos broken = BlockPos.ZERO;
        Set<BlockPos> before = Set.of();
        final Set<BlockPos> falls = new HashSet<>();
        Map<String, Integer> byHand = Map.of();
    }

    /**
     * The cap's rim overhangs the trunk by a block and slides down beside it as the stalk sags: a player standing at the foot must never
     * have a block land in their cells (that would smother them, a hidden effect). A block whose way down is occupied bursts instead.
     */
    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void aSagGreatStalkNeverLandsOnAPlayerAtItsFoot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clearArea(helper, SurfaceTuning.DEFAULT.greatMaxHeight() + 4);
        greatStalk(helper, 3, 3);
        BlockPos origin = helper.absolutePos(new BlockPos(3, BASE_ROW, 3));
        MockPlayer player = MockPlayers.join(helper, "great-foot");
        player.player().setGameMode(GameType.SURVIVAL);
        player.teleportTo(level, Vec3.atBottomCenterOf(origin.offset(-1, 0, 0)), 0, 0);
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
        breakByHand(helper, player, origin);
        helper.runAfterDelay(COLLAPSE_WAIT, () -> {
            if (stalkBlocks(helper).stream().anyMatch(pos -> pos.getY() > origin.getY())) {
                throw failure(helper, "the great stalk did not finish collapsing");
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

    /** A great stalk is a 2x2 trunk, the height the tuning gives, and a cap: the rim of 12 and the dome of 4 above it. */
    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void aGreatStalkGrowsATwoByTwoTrunkWithACap(GameTestHelper helper) {
        SurfaceTuning tuning = SurfaceTuning.DEFAULT;
        Set<Integer> heights = new HashSet<>();
        RandomSource shapes = RandomSource.create(SHAPE_SEED);
        for (long seed = 1; seed <= SHAPE_SEEDS; seed++) {
            clearArea(helper, tuning.greatMaxHeight() + 4);
            if (!GreatStalk.place(helper.getLevel(), shapes, helper.absolutePos(new BlockPos(3, BASE_ROW, 3)))) {
                throw failure(helper, "seed %s: the placer would not grow a great stalk on a flat stone floor", seed);
            }
            int height = trunkHeight(helper, 3, 3);
            heights.add(height);
            if (height < tuning.greatMinHeight() || height > tuning.greatMaxHeight()) {
                throw failure(
                        helper,
                        "seed %s: the trunk is %s tall, outside %s to %s",
                        seed,
                        height,
                        tuning.greatMinHeight(),
                        tuning.greatMaxHeight());
            }
            BlockPos origin = helper.absolutePos(new BlockPos(3, BASE_ROW, 3));
            Set<BlockPos> trunk = new HashSet<>();
            Set<BlockPos> rim = new HashSet<>();
            Set<BlockPos> dome = new HashSet<>();
            for (BlockPos pos : stalkBlocks(helper)) {
                BlockPos rel = pos.subtract(origin);
                BlockState state = helper.getLevel().getBlockState(pos);
                if (state.is(ours("great_stalk"))) {
                    trunk.add(rel);
                } else if (state.is(ours("great_stalk_cap")) && rel.getY() == height) {
                    rim.add(rel);
                } else if (state.is(ours("great_stalk_cap")) && rel.getY() == height + 1) {
                    dome.add(rel);
                } else {
                    throw failure(helper, "seed %s: a stray %s at %s", seed, state, rel.toShortString());
                }
            }
            if (trunk.size() != 4 * height) {
                throw failure(
                        helper,
                        "seed %s: %s trunk blocks, expected %s (2x2 by %s)",
                        seed,
                        trunk.size(),
                        4 * height,
                        height);
            }
            for (BlockPos pos : trunk) {
                if (pos.getX() < 0 || pos.getX() > 1 || pos.getZ() < 0 || pos.getZ() > 1) {
                    throw failure(helper, "seed %s: the trunk leaves its 2x2 at %s", seed, pos.toShortString());
                }
            }
            int rimCells = (2 + 2 * CAP_REACH) * (2 + 2 * CAP_REACH) - 4;
            if (rim.size() != rimCells || dome.size() != 4) {
                throw failure(
                        helper,
                        "seed %s: a cap of %s rim and %s dome blocks, expected %s and 4",
                        seed,
                        rim.size(),
                        dome.size(),
                        rimCells);
            }
        }
        if (heights.size() < 2) {
            throw failure(helper, "every seed grew a trunk of the same height %s", heights);
        }
        helper.succeed();
    }

    /**
     * The placer writes by block position through its level, never by chunk, so a great stalk whose trunk straddles a chunk border (its
     * west column in one chunk and its east column in the next) is as whole as any. Built in the air above the test, off any neighbour.
     */
    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void aGreatStalkOnAChunkBorderIsWhole(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos here = helper.absolutePos(new BlockPos(0, BASE_ROW, 0)).above(SKY);
        BlockPos origin = new BlockPos(((here.getX() >> 4) << 4) + 15, here.getY(), here.getZ());
        int height = SurfaceTuning.DEFAULT.greatMaxHeight();
        for (int x = -3; x <= 4; x++) {
            for (int z = -3; z <= 4; z++) {
                level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = 0; y <= height + 3; y++) {
                    level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        if (!GreatStalk.place(level, RandomSource.create(GREAT_SEED), origin)) {
            throw failure(helper, "the placer would not grow a great stalk on a chunk border");
        }
        int trunk = 0;
        int cap = 0;
        for (int x = -3; x <= 4; x++) {
            for (int z = -3; z <= 4; z++) {
                for (int y = 0; y <= height + 3; y++) {
                    BlockState state = level.getBlockState(origin.offset(x, y, z));
                    trunk += state.is(ours("great_stalk")) ? 1 : 0;
                    cap += state.is(ours("great_stalk_cap")) ? 1 : 0;
                }
            }
        }
        int tall = 0;
        while (level.getBlockState(origin.above(tall)).is(ours("great_stalk"))) {
            tall++;
        }
        boolean straddles = (origin.getX() >> 4) != (origin.east().getX() >> 4);
        if (!straddles || trunk != 4 * tall || cap != 16 || tall < SurfaceTuning.DEFAULT.greatMinHeight()) {
            throw failure(
                    helper,
                    "a great stalk on a chunk border: straddles %s, %s trunk blocks of %s tall, %s cap blocks",
                    straddles,
                    trunk,
                    tall,
                    cap);
        }
        helper.succeed();
    }

    /** No room, no great stalk: a stone block in the way of the footprint, or too little headroom, makes the placer give up and place nothing. */
    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void aGreatStalkNeedsFlatGroundAndHeadroom(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clearArea(helper, SurfaceTuning.DEFAULT.greatMaxHeight() + 4);
        level.setBlock(
                helper.absolutePos(new BlockPos(4, BASE_ROW, 4)),
                Blocks.STONE.defaultBlockState(),
                Block.UPDATE_CLIENTS);
        if (GreatStalk.place(level, RandomSource.create(1), helper.absolutePos(new BlockPos(3, BASE_ROW, 3)))
                || !stalkBlocks(helper).isEmpty()) {
            throw failure(helper, "a great stalk grew over a block in its footprint");
        }
        clearArea(helper, SurfaceTuning.DEFAULT.greatMaxHeight() + 4);
        level.setBlock(
                helper.absolutePos(new BlockPos(3, BASE_ROW + SurfaceTuning.DEFAULT.greatMinHeight() / 2, 3)),
                Blocks.STONE.defaultBlockState(),
                Block.UPDATE_CLIENTS);
        if (GreatStalk.place(level, RandomSource.create(1), helper.absolutePos(new BlockPos(3, BASE_ROW, 3)))
                || !stalkBlocks(helper).isEmpty()) {
            throw failure(helper, "a great stalk grew under a roof lower than its shortest trunk");
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
            clearArea(helper, SurfaceTuning.DEFAULT.greatMaxHeight() + 4);
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
}
