package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.surface.GreatStalk;
import io.github.pkeppeler.deepcharter.surface.GreatStalkLayout;
import io.github.pkeppeler.deepcharter.surface.GreatStalkLayout.Cell;
import io.github.pkeppeler.deepcharter.surface.GreatStalkLayout.Part;
import io.github.pkeppeler.deepcharter.surface.GreatStalkShape;
import io.github.pkeppeler.deepcharter.surface.SurfaceTuning;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
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
import java.util.function.Function;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
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
    /** Longer than a collapse takes: the sag delay plus one step for each layer of the tallest great stalk and its cap. */
    private static final int COLLAPSE_WAIT = 200;

    /** Where the far player stands, past where a collapse throws its drops (about 9 blocks from a great stalk). */
    private static final int FAR_Z = 20;

    /**
     * Items are counted this far from the test's origin on each side and above and below it, past where drops come to rest. The two
     * tests that count them run in a test environment of their own, so no other test's drops are in that area.
     */
    private static final int ITEM_REACH = 40;

    private static final int ITEM_DEPTH = 20;
    private static final int ITEM_HEIGHT = 60;
    private static final String SINGLE_ALONE = "deepcharter-test:stalk_count_single";
    private static final String GREAT_ALONE = "deepcharter-test:stalk_count_great";
    /** How far above the test a build in the air stands, off every neighbour. */
    private static final int SKY = 40;

    private static final int COLUMN_HEIGHT = 6;
    private static final int BROKEN_SEGMENT = 2;
    private static final int TEST_MAX_TICKS = COLLAPSE_WAIT + 100;
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

    @FormatMethod
    private static RuntimeException failure(
            GameTestHelper helper, @FormatString String format, @Nullable Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
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

    private static List<ItemEntity> items(GameTestHelper helper) {
        BlockPos low = helper.absolutePos(new BlockPos(-ITEM_REACH, -ITEM_DEPTH, -ITEM_REACH));
        BlockPos high = helper.absolutePos(new BlockPos(ITEM_REACH, ITEM_HEIGHT, ITEM_REACH));
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(low).minmax(new AABB(high)));
    }

    /** The items lying about, by item id. */
    private static Map<String, Integer> dropped(GameTestHelper helper) {
        Map<String, Integer> total = new TreeMap<>();
        for (ItemEntity item : items(helper)) {
            total.merge(
                    BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString(),
                    item.getItem().getCount(),
                    Integer::sum);
        }
        return total;
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
    @GameTest(maxTicks = TEST_MAX_TICKS, environment = SINGLE_ALONE)
    public void breakingASegmentCollapsesEverythingAboveIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clearArea(helper, COLUMN_HEIGHT);
        MockPlayer player = farPlayer(helper, "stalk-collapse");
        // The reference: every block from the break up, broken by hand from the top down, so nothing collapses.
        column(helper, 2, 2, COLUMN_HEIGHT);
        for (int index = COLUMN_HEIGHT - 1; index >= BROKEN_SEGMENT; index--) {
            breakByHand(helper, player, helper.absolutePos(new BlockPos(2, BASE_ROW + index, 2)));
        }
        Map<String, Integer> byHand = dropped(helper);
        if (byHand.getOrDefault("deepcharter:fungal_stalk", 0) != COLUMN_HEIGHT - BROKEN_SEGMENT) {
            throw failure(helper, "breaking %s segments by hand dropped %s", COLUMN_HEIGHT - BROKEN_SEGMENT, byHand);
        }
        clearArea(helper, COLUMN_HEIGHT);
        // The collapse: one break.
        column(helper, 2, 2, COLUMN_HEIGHT);
        breakByHand(helper, player, helper.absolutePos(new BlockPos(2, BASE_ROW + BROKEN_SEGMENT, 2)));
        helper.runAfterDelay(COLLAPSE_WAIT, () -> {
            for (int index = 0; index < COLUMN_HEIGHT; index++) {
                boolean stands =
                        isStalkBlock(level.getBlockState(helper.absolutePos(new BlockPos(2, BASE_ROW + index, 2))));
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
            if (!collapsed.equals(byHand)) {
                throw failure(
                        helper,
                        "the collapse dropped %s, breaking the same blocks by hand dropped %s",
                        collapsed,
                        byHand);
            }
            helper.succeed();
        });
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
    @GameTest(maxTicks = TEST_MAX_TICKS, environment = GREAT_ALONE)
    public void breakingAGreatStalkBringsTheWholeTrunkAboveItDown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int reach = SurfaceTuning.DEFAULT.greatMaxHeight();
        clearArea(helper, reach + 4);
        MockPlayer player = farPlayer(helper, "great-collapse");
        greatStalk(helper, 3, 3);
        int height = trunkHeight(helper, 3, 3);
        int breakIndex = Math.max(2, height / 2);
        BlockPos broken = helper.absolutePos(new BlockPos(3, BASE_ROW + breakIndex, 3));
        Set<BlockPos> before = stalkBlocks(helper);
        Set<BlockPos> falls = new HashSet<>();
        for (BlockPos pos : before) {
            if (pos.getY() > broken.getY() || pos.equals(broken)) {
                falls.add(pos);
            }
        }
        // The reference: the same blocks, broken by hand from the top down.
        List<BlockPos> order = new ArrayList<>(falls);
        order.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed());
        for (BlockPos pos : order) {
            breakByHand(helper, player, pos);
        }
        Map<String, Integer> byHand = dropped(helper);
        if (byHand.getOrDefault("deepcharter:fungal_stalk", 0) != falls.size()) {
            throw failure(helper, "breaking %s great stalk blocks by hand dropped %s", falls.size(), byHand);
        }
        clearArea(helper, reach + 4);
        greatStalk(helper, 3, 3);
        breakByHand(helper, player, broken);
        helper.runAfterDelay(COLLAPSE_WAIT, () -> {
            Set<BlockPos> after = stalkBlocks(helper);
            Set<BlockPos> expected = new HashSet<>(before);
            expected.removeAll(falls);
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
                if (pos.getY() > broken.getY()) {
                    throw failure(helper, "a block above the break stands at %s", pos.toShortString());
                }
            }
            Map<String, Integer> collapsed = dropped(helper);
            if (!collapsed.equals(byHand)) {
                throw failure(
                        helper,
                        "the collapse dropped %s, breaking the same blocks by hand dropped %s",
                        collapsed,
                        byHand);
            }
            if (level.getBlockState(broken).isAir() && after.size() != before.size() - falls.size()) {
                throw failure(helper, "the count of standing blocks is off");
            }
            helper.succeed();
        });
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

    // ---- The silhouette options of #910: braid, crook and fork. ----

    /** Where an option's origin stands in the test area: far enough from every edge that its widest reach (8 blocks) is cleared. */
    private static final int OPTION_AT = 9;

    /** Chunks east of the test, far enough that none of the test's own are near, whose neighbour is not loaded. */
    private static final int UNLOADED_CHUNKS_AWAY = 4000;

    private static final int OPTION_SEEDS = 40;
    private static final int OPTION_PLACED_SEEDS = 6;
    private static final int OPTION_CLEAR_HEIGHT = 22;
    /** The most any block of a shape may stand from its origin horizontally: the placer writes the 3x3 chunks round the origin's. */
    private static final int OPTION_MAX_REACH = 8;

    private static final int YIELD_LEAST = 50;
    private static final int YIELD_MOST = 90;
    private static final long BRAID_SEED = 3;
    private static final long CROOK_SEED = 5;
    private static final long FORK_SEED = 11;
    private static final String BRAID_TRUNK_ALONE = "deepcharter-test:stalk_count_braid_trunk";
    private static final String FORK_BRANCH_ALONE = "deepcharter-test:stalk_count_fork_branch";
    private static final String FORK_STEM_ALONE = "deepcharter-test:stalk_count_fork_stem";
    private static final String CROOK_TRUNK_ALONE = "deepcharter-test:stalk_count_crook_trunk";

    private static GreatStalkLayout layoutOf(GreatStalkShape shape, long seed) {
        return shape.layout(RandomSource.create(seed)::nextInt);
    }

    private static BlockPos optionOrigin(GameTestHelper helper) {
        return helper.absolutePos(new BlockPos(OPTION_AT, BASE_ROW, OPTION_AT));
    }

    private static BlockPos at(BlockPos origin, Cell cell) {
        return origin.offset(cell.x(), cell.y(), cell.z());
    }

    /** A flat stone floor under the area an option may grow in, and nothing above it. */
    private static void clearOptionArea(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = optionOrigin(helper);
        for (int dx = -OPTION_AT; dx <= OPTION_AT; dx++) {
            for (int dz = -OPTION_AT; dz <= OPTION_AT; dz++) {
                level.setBlock(origin.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = 0; y <= OPTION_CLEAR_HEIGHT; y++) {
                    level.setBlock(origin.offset(dx, y, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        for (ItemEntity item : items(helper)) {
            item.discard();
        }
    }

    private static @Nullable Part partOf(BlockState state) {
        if (state.is(ours("great_stalk"))) {
            return Part.TRUNK;
        }
        if (state.is(ours("great_stalk_cap"))) {
            return Part.CAP;
        }
        return state.is(ours("fungal_stalk")) ? Part.STRAND : null;
    }

    /** The stalk blocks standing in the option area, by their cell relative to the option's origin. */
    private static Map<Cell, Part> standing(GameTestHelper helper) {
        BlockPos origin = optionOrigin(helper);
        Map<Cell, Part> found = new HashMap<>();
        for (int dx = -OPTION_AT; dx <= OPTION_AT; dx++) {
            for (int dz = -OPTION_AT; dz <= OPTION_AT; dz++) {
                for (int y = 0; y <= OPTION_CLEAR_HEIGHT; y++) {
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
     * Holds the chunks the option area covers loaded: a collapse never loads a chunk beside the one it is in, so a stalk that reaches
     * past the test's own chunks needs them held.
     */
    private static void holdOptionArea(GameTestHelper helper) {
        BlockPos origin = optionOrigin(helper);
        for (int dx : new int[] {-OPTION_AT, OPTION_AT}) {
            for (int dz : new int[] {-OPTION_AT, OPTION_AT}) {
                FarChunks.force(helper, helper.getLevel(), (origin.getX() + dx) >> 4, (origin.getZ() + dz) >> 4);
            }
        }
    }

    private static boolean growOption(GameTestHelper helper, GreatStalkShape shape, long seed) {
        clearOptionArea(helper);
        return GreatStalk.place(helper.getLevel(), RandomSource.create(seed), optionOrigin(helper), shape);
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
     * What a shape's generator promises: every block is joined to the ground, stands within {@value #OPTION_MAX_REACH} blocks of the
     * origin, the whole is in the yield of a great stalk, the stalk is not rotationally symmetric, and two independent trunks never
     * touch. It grows in the world as the generator says.
     */
    private void shapeKeepsItsPromises(GameTestHelper helper, GreatStalkShape shape) {
        Set<Integer> yields = new HashSet<>();
        for (long seed = 1; seed <= OPTION_SEEDS; seed++) {
            GreatStalkLayout layout = layoutOf(shape, seed);
            Set<Cell> cells = layout.parts().keySet();
            yields.add(cells.size());
            if (cells.size() < YIELD_LEAST || cells.size() > YIELD_MOST) {
                throw failure(
                        helper,
                        "%s seed %s: %s blocks, outside %s to %s",
                        shape,
                        seed,
                        cells.size(),
                        YIELD_LEAST,
                        YIELD_MOST);
            }
            if (layout.without(Set.of()).parts().size() != cells.size()) {
                throw failure(helper, "%s seed %s: a block is not joined to the ground", shape, seed);
            }
            for (Cell cell : cells) {
                if (Math.abs(cell.x()) > OPTION_MAX_REACH || Math.abs(cell.z()) > OPTION_MAX_REACH || cell.y() < 0) {
                    throw failure(helper, "%s seed %s: a block at %s is out of bounds", shape, seed, cell);
                }
                if (cell.y() == 0 && layout.parts().get(cell) != Part.TRUNK) {
                    throw failure(
                            helper,
                            "%s seed %s: a %s at the base, %s",
                            shape,
                            seed,
                            layout.parts().get(cell),
                            cell);
                }
                int owner = layout.owners().getOrDefault(cell, GreatStalkLayout.SHARED);
                for (Cell next : List.of(
                        new Cell(cell.x() + 1, cell.y(), cell.z()),
                        new Cell(cell.x(), cell.y() + 1, cell.z()),
                        new Cell(cell.x(), cell.y(), cell.z() + 1))) {
                    int other = layout.owners().getOrDefault(next, owner);
                    if (cells.contains(next)
                            && other != owner
                            && other != GreatStalkLayout.SHARED
                            && owner != GreatStalkLayout.SHARED) {
                        throw failure(
                                helper, "%s seed %s: trunks %s and %s touch at %s", shape, seed, owner, other, cell);
                    }
                }
            }
            if (rotationallySymmetric(cells)) {
                throw failure(helper, "%s seed %s: the stalk is rotationally symmetric", shape, seed);
            }
        }
        if (yields.size() < 3) {
            throw failure(helper, "%s: every seed grew the same few shapes, block counts %s", shape, yields);
        }
        for (long seed = 1; seed <= OPTION_PLACED_SEEDS; seed++) {
            GreatStalkLayout layout = layoutOf(shape, seed);
            if (!growOption(helper, shape, seed)) {
                throw failure(helper, "%s seed %s: the placer would not grow it on a flat stone floor", shape, seed);
            }
            if (!standing(helper).equals(layout.parts())) {
                throw failure(helper, "%s seed %s: what grew is not what the generator laid out", shape, seed);
            }
        }
        helper.succeed();
    }

    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void aBraidKeepsItsPromises(GameTestHelper helper) {
        shapeKeepsItsPromises(helper, GreatStalkShape.BRAID);
    }

    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void aCrookKeepsItsPromises(GameTestHelper helper) {
        shapeKeepsItsPromises(helper, GreatStalkShape.CROOK);
    }

    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void aForkKeepsItsPromises(GameTestHelper helper) {
        shapeKeepsItsPromises(helper, GreatStalkShape.FORK);
    }

    /**
     * No ground under a trunk, or no room for one, and nothing grows; a cap or a strand with no room is left out, with what then hangs
     * from nothing.
     */
    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void everyOptionNeedsGroundAndRoomForItsTrunks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (GreatStalkShape shape : GreatStalkShape.values()) {
            GreatStalkLayout layout = layoutOf(shape, 1);
            Cell trunk = layout.parts().keySet().stream()
                    .filter(cell -> cell.y() == 1 && layout.parts().get(cell) == Part.TRUNK)
                    .findFirst()
                    .orElseThrow();
            Cell base = new Cell(trunk.x(), 0, trunk.z());
            clearOptionArea(helper);
            level.setBlock(at(optionOrigin(helper), trunk), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            if (GreatStalk.place(level, RandomSource.create(1), optionOrigin(helper), shape)
                    || !standing(helper).isEmpty()) {
                throw failure(helper, "%s grew over a block in its trunk", shape);
            }
            clearOptionArea(helper);
            level.setBlock(
                    at(optionOrigin(helper), base).below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            if (layout.parts().containsKey(base)
                    && !layout.optional().contains(base)
                    && (GreatStalk.place(level, RandomSource.create(1), optionOrigin(helper), shape)
                            || !standing(helper).isEmpty())) {
                throw failure(helper, "%s grew a trunk over a hole in the ground", shape);
            }
            Cell cap = layout.parts().entrySet().stream()
                    .filter(entry -> entry.getValue() == Part.CAP)
                    .map(Map.Entry::getKey)
                    .max(Comparator.comparingInt(Cell::y))
                    .orElseThrow();
            clearOptionArea(helper);
            level.setBlock(at(optionOrigin(helper), cap), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            if (!GreatStalk.place(level, RandomSource.create(1), optionOrigin(helper), shape)) {
                throw failure(helper, "%s would not grow with a block in the way of one cap", shape);
            }
            Set<Cell> expected =
                    new HashSet<>(layout.without(Set.of(cap)).parts().keySet());
            if (!standing(helper).keySet().equals(expected)
                    || !level.getBlockState(at(optionOrigin(helper), cap)).is(Blocks.STONE)) {
                throw failure(helper, "%s did not leave out exactly the capped cell in its way", shape);
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
        int owner = layout.owners().getOrDefault(broken, GreatStalkLayout.SHARED);
        boolean hasAbove = layout.parts().containsKey(new Cell(broken.x(), broken.y() + 1, broken.z()));
        Map<Cell, Part> left = new LinkedHashMap<>(layout.parts());
        left.remove(broken);
        Set<Cell> fallen = new HashSet<>();
        if (hasAbove) {
            for (Cell cell : left.keySet()) {
                boolean sameTrunk =
                        owner == GreatStalkLayout.SHARED || layout.owners().getOrDefault(cell, -1) == owner;
                if (cell.y() > broken.y() && sameTrunk) {
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
                        && layout.owners().getOrDefault(cell, -1) == owner
                        && layout.parts().containsKey(new Cell(cell.x(), cell.y() + 1, cell.z())))
                .sorted(Comparator.comparingInt(Cell::x).thenComparingInt(Cell::z))
                .toList();
    }

    /** The drops of breaking each of these blocks by hand, by item id. */
    private static Map<String, Integer> handDrops(GameTestHelper helper, Collection<Cell> cells) {
        Map<String, Integer> total = new TreeMap<>();
        for (Cell cell : cells) {
            BlockPos pos = at(optionOrigin(helper), cell);
            for (ItemStack drop : Block.getDrops(helper.getLevel().getBlockState(pos), helper.getLevel(), pos, null)) {
                total.merge(BuiltInRegistries.ITEM.getKey(drop.getItem()).toString(), drop.getCount(), Integer::sum);
            }
        }
        return total;
    }

    /**
     * Grows the shape, breaks one block by hand and checks the collapse against {@link #fallenBy}: exactly those blocks are gone, nothing
     * stands that is not joined to the ground, and the drops are what breaking each of them by hand drops. With {@code countDrops}, the
     * test runs alone in its environment, so that the items lying about are its own.
     */
    private void optionCollapse(
            GameTestHelper helper,
            GreatStalkShape shape,
            long seed,
            Function<GreatStalkLayout, Cell> pick,
            boolean countDrops) {
        GreatStalkLayout layout = layoutOf(shape, seed);
        Cell broken = pick.apply(layout);
        Set<Cell> fallen = fallenBy(layout, broken);
        holdOptionArea(helper);
        MockPlayer player = farPlayer(helper, "option-collapse");
        if (!growOption(helper, shape, seed)) {
            throw failure(helper, "%s would not grow on a flat stone floor", shape);
        }
        Set<Cell> everything = new HashSet<>(fallen);
        everything.add(broken);
        Map<String, Integer> byHand = handDrops(helper, everything);
        if (byHand.getOrDefault("deepcharter:fungal_stalk", 0) != everything.size() || byHand.size() != 1) {
            throw failure(helper, "breaking %s blocks by hand would drop %s", everything.size(), byHand);
        }
        breakByHand(helper, player, at(optionOrigin(helper), broken));
        helper.runAfterDelay(COLLAPSE_WAIT, () -> {
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
                        "%s: after breaking %s, %s blocks should fall and %s did not fall (%s), %s fell that should stand (%s)",
                        shape,
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
                throw failure(helper, "%s: a block floats, joined to nothing, after the collapse", shape);
            }
            if (countDrops && !dropped(helper).equals(byHand)) {
                throw failure(
                        helper,
                        "%s: the collapse dropped %s, breaking the same blocks by hand drops %s",
                        shape,
                        dropped(helper),
                        byHand);
            }
            helper.succeed();
        });
    }

    /** A braid's trunk breaks: its upper part, with the shelves on it, falls; the other two trunks and the lower part stand. */
    @GameTest(maxTicks = TEST_MAX_TICKS, environment = BRAID_TRUNK_ALONE)
    public void breakingOneTrunkOfABraidLeavesTheOthersStanding(GameTestHelper helper) {
        optionCollapse(
                helper,
                GreatStalkShape.BRAID,
                BRAID_SEED,
                layout -> trunkCellsWithAbove(layout, 2, 7).getFirst(),
                true);
    }

    /** A crook's thick trunk breaks: everything above, in all four columns, the shelves above it and the crown, falls. */
    @GameTest(maxTicks = TEST_MAX_TICKS, environment = CROOK_TRUNK_ALONE)
    public void breakingTheTrunkOfACrookBringsTheCrownDown(GameTestHelper helper) {
        optionCollapse(
                helper,
                GreatStalkShape.CROOK,
                CROOK_SEED,
                layout -> trunkCellsWithAbove(layout, 0, 4).getFirst(),
                true);
    }

    /** A fork's branch breaks above the fork: it and its cap fall; the stem and the other two branches stand. */
    @GameTest(maxTicks = TEST_MAX_TICKS, environment = FORK_BRANCH_ALONE)
    public void breakingOneBranchOfAForkLeavesTheRestStanding(GameTestHelper helper) {
        optionCollapse(
                helper,
                GreatStalkShape.FORK,
                FORK_SEED,
                layout -> {
                    int fork = layout.parts().keySet().stream()
                            .filter(cell -> layout.owners().getOrDefault(cell, -1) == 1)
                            .mapToInt(Cell::y)
                            .min()
                            .orElseThrow();
                    return trunkCellsWithAbove(layout, 1, fork + 1).getFirst();
                },
                true);
    }

    /** A fork's stem breaks below the fork: all three branches, their caps and their strands fall with it. */
    @GameTest(maxTicks = TEST_MAX_TICKS, environment = FORK_STEM_ALONE)
    public void breakingTheStemOfAForkBringsAllTheBranchesDown(GameTestHelper helper) {
        optionCollapse(
                helper,
                GreatStalkShape.FORK,
                FORK_SEED,
                layout -> trunkCellsWithAbove(layout, 0, 3).getFirst(),
                true);
    }

    /**
     * The kink of a thin trunk: the cell under a sideways step has nothing above it, yet the trunk beyond the step hung from it. Breaking
     * it brings that trunk down; none of it is left floating.
     */
    private static Function<GreatStalkLayout, Cell> kinkOf(int owner) {
        return layout -> layout.parts().keySet().stream()
                .filter(cell -> layout.owners().getOrDefault(cell, -1) == owner
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
        optionCollapse(helper, GreatStalkShape.BRAID, BRAID_SEED, kinkOf(1), false);
    }

    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void breakingTheKinkOfAForkBranchLeavesNothingFloating(GameTestHelper helper) {
        optionCollapse(helper, GreatStalkShape.FORK, FORK_SEED, kinkOf(2), false);
    }

    /** A shelf breaks off its trunk's side: its outer half, no longer joined to anything, falls, and the strand hanging from a cap with it. */
    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void breakingAShelfBringsDownWhatOnlyHungFromIt(GameTestHelper helper) {
        optionCollapse(
                helper,
                GreatStalkShape.BRAID,
                BRAID_SEED,
                layout -> layout.parts().keySet().stream()
                        .filter(cell -> layout.parts().get(cell) == Part.CAP && cell.y() == 7)
                        .min(Comparator.comparingInt(Cell::x).thenComparingInt(Cell::z))
                        .orElseThrow(),
                false);
    }

    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void breakingTheCapOfAForkBranchBringsItsStrandsDown(GameTestHelper helper) {
        optionCollapse(
                helper,
                GreatStalkShape.FORK,
                FORK_SEED,
                layout -> layout.parts().keySet().stream()
                        .filter(cell -> layout.parts().get(cell) == Part.CAP
                                && layout.parts().get(new Cell(cell.x(), cell.y() - 1, cell.z())) == Part.STRAND)
                        .min(Comparator.comparingInt(Cell::y)
                                .thenComparingInt(Cell::x)
                                .thenComparingInt(Cell::z))
                        .orElseThrow(),
                false);
    }

    /**
     * A player in the shade of a shelf or a cap, at the foot of the trunk it hangs from, takes nothing when that trunk comes down: a
     * block that would land in the player's cells bursts instead.
     */
    private void optionNeverLandsOnAPlayer(GameTestHelper helper, GreatStalkShape shape, long seed) {
        ServerLevel level = helper.getLevel();
        GreatStalkLayout layout = layoutOf(shape, seed);
        // The cap block that hangs lowest over free ground, and the trunk base it hangs from.
        Cell shade = layout.parts().keySet().stream()
                .filter(cell -> layout.parts().get(cell) == Part.CAP
                        && cell.y() >= 2
                        && !layout.parts().containsKey(new Cell(cell.x(), 0, cell.z()))
                        && !layout.parts().containsKey(new Cell(cell.x(), 1, cell.z())))
                .min(Comparator.comparingInt(Cell::y).thenComparingInt(Cell::x).thenComparingInt(Cell::z))
                .orElseThrow();
        int shadeOwner = layout.owners().getOrDefault(shade, GreatStalkLayout.SHARED);
        boolean ownBase = layout.parts().keySet().stream()
                .anyMatch(cell -> cell.y() == 0
                        && !layout.optional().contains(cell)
                        && layout.owners().getOrDefault(cell, -1) == shadeOwner);
        int owner = ownBase ? shadeOwner : GreatStalkLayout.SHARED;
        Cell base = layout.parts().keySet().stream()
                .filter(cell -> cell.y() == 0
                        && !layout.optional().contains(cell)
                        && layout.owners().getOrDefault(cell, -1) == owner)
                .min(Comparator.comparingInt(Cell::x).thenComparingInt(Cell::z))
                .orElseThrow();
        holdOptionArea(helper);
        if (!growOption(helper, shape, seed)) {
            throw failure(helper, "%s would not grow on a flat stone floor", shape);
        }
        BlockPos origin = optionOrigin(helper);
        MockPlayer player = MockPlayers.join(helper, "option-foot");
        player.player().setGameMode(GameType.SURVIVAL);
        player.teleportTo(level, Vec3.atBottomCenterOf(origin.offset(shade.x(), 0, shade.z())), 0, 0);
        BlockPos feet = player.player().blockPosition();
        float health = player.player().getHealth();
        TestTicks.each(helper, () -> {
            for (BlockPos cell : List.of(feet, feet.above())) {
                if (!level.getBlockState(cell).isAir()) {
                    throw failure(
                            helper,
                            "%s: a block %s landed in the player's cell %s",
                            shape,
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
                        "%s did not collapse as it should after breaking %s: %s stand that should not, %s are gone that should stand",
                        shape,
                        base,
                        extra,
                        missing);
            }
            if (player.player().getHealth() != health) {
                throw failure(
                        helper,
                        "%s: the player lost health: %s of %s",
                        shape,
                        player.player().getHealth(),
                        health);
            }
            helper.succeed();
        });
    }

    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void aSagBraidNeverLandsOnAPlayerAtItsFoot(GameTestHelper helper) {
        optionNeverLandsOnAPlayer(helper, GreatStalkShape.BRAID, BRAID_SEED);
    }

    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void aSagCrookNeverLandsOnAPlayerAtItsFoot(GameTestHelper helper) {
        optionNeverLandsOnAPlayer(helper, GreatStalkShape.CROOK, CROOK_SEED);
    }

    @GameTest(maxTicks = TEST_MAX_TICKS)
    public void aSagForkNeverLandsOnAPlayerAtItsFoot(GameTestHelper helper) {
        optionNeverLandsOnAPlayer(helper, GreatStalkShape.FORK, FORK_SEED);
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
        clearOptionArea(helper);
        BlockPos pos = optionOrigin(helper).above(3);
        helper.getLevel().setBlock(pos, falling(ours("great_stalk")), Block.UPDATE_ALL);
        helper.runAfterDelay(COLLAPSE_WAIT, () -> {
            if (!helper.getLevel().getBlockState(pos).isAir()) {
                throw failure(helper, "a stray falling block still hangs in the air");
            }
            helper.succeed();
        });
    }
}
