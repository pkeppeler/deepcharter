package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Evidence scenario "salvage-concepts" (#245, docs/design/salvage-concepts.md): the two looks of the salvage crate (the default and the sled pack)
 * and the fungal stalk, each seen where a player meets it, near and from a way off on the open plain, in a crater (the great pit, the one
 * crater every colony has in sight), and in a cave (a room carved in layer 1, lit by a pod lamp). The default of each (crate a, stalk b) is the mod's own look;
 * the sled crate is a test pack (#505 dropped the other round-1 options). Every still is {@code <block>-<option>-<place>}: {@code crate-a-near}, {@code stalk-b-cave}.
 *
 * <p>A cluster is placed the way the features place one: crates in a patch, a few lying on the ground and one stacked, stalks in a stand of
 * different heights. The crates in a crater and in a cave are put there for the look: the features place crates only in the colony's
 * yard, and stalks in craters and caves.
 */
public class SalvageConceptsScenario extends EvidenceScenario {
    /** One shape of a block: its still prefix and the test pack that turns it on (none for the mod's own look). */
    private record Option(
            String block, String letter, @Nullable String pack) {}

    private static final List<Option> OPTIONS = List.of(
            new Option("crate", "a", null),
            new Option("crate", "b", TestPacks.SALVAGE_CRATE_B),
            new Option("stalk", "b", null));
    /** The great pit's middle, south of the colony (ADR 0036): its floor is the crater. */
    private static final BlockPos PIT = new BlockPos(24, 0, 150);
    /** The carved cave's floor, in layer 1: a lamp-lit room, 32 wide and 16 deep, with the scene in its west half. */
    private static final BlockPos CAVE = new BlockPos(3000, LAYER_ROOM_Y, 3000);

    private static final int CAVE_HALF_WIDTH = 16;
    private static final int CAVE_DEPTH = 14;
    private static final int CAVE_HEIGHT = 6;
    /** The crates of the cave scene stand this far west and north of the cave's mark, and the lamp is between the crates and the stalks. */
    private static final BlockPos CAVE_SCENE = new BlockPos(-6, 0, -7);
    /** The generated stalks and crates are cleared from this far round a scene, so that nothing grown stands in front of the camera. */
    private static final int CLEAR_RADIUS = 16;

    private static final int CLEAR_HEIGHT = 10;
    private static final int LAMP = 12;
    /** Where the clusters stand, east of the camera's line, so that a crate cluster and a stalk stand can both be in one scene. */
    private static final int STALKS_OFFSET = 9;

    private static final double NEAR = 4.5;
    private static final double MID = 15;
    private static final double CRATER = 8;
    private static final double CAVE_DISTANCE = 6;
    private static final int SETTLE_TICKS = 20;

    @Override
    protected String name() {
        return "salvage-concepts";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        TextureTestWorld.open(context, shot -> screenshot(context, shot), world -> {
            Vec3 plain = world.regolithPlain();
            Vec3 pit = world.<Vec3>serverGet(server -> floor(server.overworld(), PIT));
            carveCave(world);
            Scene surface = new Scene(0, plain);
            Scene crater = new Scene(0, pit);
            Scene cave = new Scene(1, Vec3.atBottomCenterOf(CAVE.offset(CAVE_SCENE)));
            for (Scene scene : List.of(surface, crater, cave)) {
                world.serverDo(server -> build(TestLayers.level(server, scene.layer), scene.centre));
            }
            for (Option option : OPTIONS) {
                if (option.pack() != null) {
                    ClientPacks.enable(context, option.pack());
                }
                try {
                    shoot(world, option, "near", surface, NEAR, 1.6, NEAR);
                    shoot(world, option, "mid", surface, MID, 3.0, 0);
                    shoot(world, option, "crater", crater, CRATER, 1.7, 0);
                    shoot(world, option, "cave", cave, CAVE_DISTANCE, 1.6, 0);
                } finally {
                    if (option.pack() != null) {
                        ClientPacks.disable(context, option.pack());
                    }
                }
            }
            frame(context);
        });
    }

    /** Where a cluster of both kinds stands: crates at the centre, the stalks {@link #STALKS_OFFSET} blocks east of it. */
    private record Scene(int layer, Vec3 centre) {
        Vec3 subject(Option option) {
            return option.block().equals("crate") ? centre : centre.add(STALKS_OFFSET, 0, 0);
        }
    }

    /**
     * The camera looks north at the subject from {@code distance} blocks south of it, {@code eye} blocks above its foot, and {@code aside}
     * blocks to the west so that the near view is a little off the axis of the crates.
     */
    private void shoot(
            TextureTestWorld world,
            Option option,
            String place,
            Scene scene,
            double distance,
            double eye,
            double aside) {
        Vec3 subject = scene.subject(option);
        Vec3 target = subject.add(0, option.block().equals("crate") ? 0.6 : 1.8, 0);
        world.view(scene.layer(), subject.add(-aside, eye, distance), target, SETTLE_TICKS);
        world.still(option.block() + "-" + option.letter() + "-" + place);
    }

    private static Vec3 floor(ServerLevel level, BlockPos column) {
        TextureTestWorld.loadChunks(level, column, 2);
        return Vec3.atBottomCenterOf(level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, column));
    }

    private static void carveCave(TextureTestWorld world) {
        world.serverDo(server -> {
            ServerLevel level = TestLayers.level(server, 1);
            TextureTestWorld.loadChunks(level, CAVE, 2);
            RoomCarver.carve(
                    level,
                    CAVE.offset(-CAVE_HALF_WIDTH, 0, -CAVE_DEPTH),
                    CAVE.offset(CAVE_HALF_WIDTH, CAVE_HEIGHT - 1, 2),
                    Blocks.AIR.defaultBlockState());
            level.setBlock(
                    CAVE.offset(CAVE_SCENE).offset(STALKS_OFFSET / 2, 3, 3),
                    TextureTestWorld.lamp(LAMP),
                    Block.UPDATE_ALL);
        });
    }

    /** A patch of crates at {@code centre}, and a stand of stalks {@link #STALKS_OFFSET} blocks east: each on the ground of its own column. */
    private static void build(ServerLevel level, Vec3 centre) {
        BlockPos at = BlockPos.containing(centre);
        clearGrown(level, at);
        BlockState crate = block("salvage_crate").defaultBlockState();
        BlockPos east = ground(level, at.east());
        level.setBlock(
                ground(level, at),
                crate.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH),
                Block.UPDATE_ALL);
        level.setBlock(east, crate.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH), Block.UPDATE_ALL);
        level.setBlock(
                east.above(), crate.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST), Block.UPDATE_ALL);
        level.setBlock(
                ground(level, at.offset(-2, 0, 1)),
                crate.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST),
                Block.UPDATE_ALL);
        BlockState stalk =
                block("fungal_stalk").defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        for (int[] standing : new int[][] {{0, 0, 5}, {2, 1, 3}, {-1, 2, 4}, {1, -2, 2}}) {
            BlockPos base = ground(level, at.offset(STALKS_OFFSET + standing[0], 0, standing[1]));
            for (int height = 0; height < standing[2]; height++) {
                level.setBlock(base.above(height), stalk, Block.UPDATE_ALL);
            }
        }
    }

    /** Takes out the crates and stalks the world grew near the scene (a crater floor is full of them): the options are seen on their own. */
    private static void clearGrown(ServerLevel level, BlockPos at) {
        for (BlockPos cell : BlockPos.betweenClosed(
                at.offset(-CLEAR_RADIUS, -2, -CLEAR_RADIUS), at.offset(CLEAR_RADIUS, CLEAR_HEIGHT, CLEAR_RADIUS))) {
            if (level.getBlockState(cell).is(block("fungal_stalk"))
                    || level.getBlockState(cell).is(block("salvage_crate"))) {
                level.setBlock(
                        cell,
                        Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_ALL); // room-carver: takes out a crate or a stalk on the surface, which is not
                // layer rock
            }
        }
    }

    /** The first free block over the ground of a column: up from the cell given while it is solid, then down while the block below is free. */
    private static BlockPos ground(ServerLevel level, BlockPos column) {
        BlockPos ground = column;
        while (!level.getBlockState(ground).isAir()) {
            ground = ground.above();
        }
        while (level.getBlockState(ground.below()).isAir()) {
            ground = ground.below();
        }
        return ground;
    }

    private static Block block(String path) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
    }
}
