package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.outpost.DepthRatings;
import io.github.pkeppeler.deepcharter.outpost.OutpostData.Outpost;
import io.github.pkeppeler.deepcharter.outpost.OutpostSettling;
import io.github.pkeppeler.deepcharter.outpost.OutpostShell;
import io.github.pkeppeler.deepcharter.outpost.OutpostShell.Shell;
import io.github.pkeppeler.deepcharter.outpost.OutpostShell.Terrain;
import io.github.pkeppeler.deepcharter.outpost.Ratings;
import io.github.pkeppeler.deepcharter.outpost.SettlingTuning;
import io.github.pkeppeler.deepcharter.outpost.ShellTuning;
import io.github.pkeppeler.deepcharter.test.support.GridBlocks;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Server GameTests for the shell's exposure rule (#1239, #926 slice A, ADR 0092): which blocks of an outpost a creature can reach. The rule
 * reads blocks only, so most cases run on {@link GridBlocks}, a block world in memory, and need no chunk; the survey of a real room in the
 * rock is {@code OutpostSettlingTest}'s.
 */
public class OutpostShellTest {
    private static final BlockPos CENTRE = BlockPos.ZERO;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final ShellTuning TUNING = ShellTuning.DEFAULT;
    /** What layer 3 asks: slag holds, stalk planks do not. */
    private static final Optional<Ratings> LAYER_3 = DepthRatings.forLayer(3);

    private static final Predicate<BlockState> BREAKABLE = OutpostSettling.breakable(LAYER_3);
    private static final BlockState PLANKS = block("stalk_planks").defaultBlockState();
    private static final BlockState SLAG = SlagBrick.BLOCK.defaultBlockState();
    private static final Outpost OUTPOST =
            new Outpost(new CharterId(new UUID(1, 2)), 3, Level.OVERWORLD, CENTRE);
    /** The hall's zone, as away settling surveys it. */
    private static final BoundingBox ZONE = OutpostSettling.zone(OUTPOST, SettlingTuning.DEFAULT);

    private static Block block(String path) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
    }

    /** The kit's hall cut into rock: walls, floor and ceiling of {@code wall}, an interior 9 across and 5 high. */
    private static GridBlocks hall(BlockState wall) {
        return new GridBlocks()
                .fill(CENTRE.offset(-5, 0, -5), CENTRE.offset(5, 6, 5), wall)
                .fill(CENTRE.offset(-4, 1, -4), CENTRE.offset(4, 5, 4), AIR);
    }

    private static Shell survey(GridBlocks grid) {
        return OutpostShell.survey(grid.terrain(), ZONE, TUNING, BREAKABLE);
    }

    private static BlockPos inside() {
        return CENTRE.offset(2, 2, 2);
    }

    // ----- open and outside -----

    @GameTest
    public void aClosedHallIsNotExposed(GameTestHelper helper) {
        Shell shell = survey(hall(PLANKS));
        expect(helper, !shell.isOutside(inside()), "the hall's air is a room");
        expect(helper, shell.candidates().isEmpty(), "a closed hall exposes nothing: %s", shell.candidates());
        expect(helper, shell.gaps(OUTPOST) == 0, "a closed hall has no gaps: %s", shell.gaps(OUTPOST));
        helper.succeed();
    }

    @GameTest
    public void aHallWithA33TallShaftIsExposed(GameTestHelper helper) {
        GridBlocks grid = hall(PLANKS).fill(CENTRE.offset(0, 6, 0), CENTRE.offset(0, 6 + 32, 0), AIR);
        Shell shell = survey(grid);
        expect(helper, shell.isOutside(inside()), "the hall's air joins a shaft over the span");
        BlockPos wall = CENTRE.offset(5, 3, 0);
        expect(
                helper,
                shell.candidates().contains(wall) && shell.reaches(wall),
                "a plank of the wall has a face on outside air: %s",
                shell.candidates());
        expect(
                helper,
                shell.candidates().stream().noneMatch(pos -> grid.getBlockState(pos).is(Blocks.STONE)),
                "the rock round the shaft is wild and never a candidate");
        // The hatch of the kit: 5 by 5 in the ceiling, left as it was. Here it is the 1 by 1 shaft, so the ring's gap is its cell.
        expect(helper, shell.gaps(OUTPOST) == 1, "the shaft leaves one gap in the ceiling: %s", shell.gaps(OUTPOST));

        GridBlocks shorter = hall(PLANKS).fill(CENTRE.offset(0, 6, 0), CENTRE.offset(0, 6 + 25, 0), AIR);
        expect(
                helper,
                !survey(shorter).isOutside(inside()),
                "a shaft of 26 over the hall's 5 spans 31 blocks, still a room");
        helper.succeed();
    }

    @GameTest
    public void aSpanOf32IsClosedAndASpanOf33IsOutsideOnEveryAxis(GameTestHelper helper) {
        for (Direction.Axis axis : Direction.Axis.values()) {
            for (int length : new int[] {ShellTuning.DEFAULT.openSpan(), ShellTuning.DEFAULT.openSpan() + 1}) {
                BlockPos from = new BlockPos(0, 0, 0);
                BlockPos to = from.relative(axis, length - 1);
                GridBlocks grid = new GridBlocks().fill(from, to, AIR);
                BoundingBox zone = BoundingBox.fromCorners(from.offset(-2, -2, -2), to.offset(2, 2, 2));
                Shell shell = OutpostShell.survey(grid.terrain(), zone, TUNING, BREAKABLE);
                boolean outside = shell.isOutside(from);
                expect(
                        helper,
                        outside == (length > ShellTuning.DEFAULT.openSpan()),
                        "a straight pocket %s blocks long along %s should be %s",
                        length,
                        axis,
                        length > ShellTuning.DEFAULT.openSpan() ? "outside" : "closed");
                expect(helper, shell.isOutside(to) == outside, "both ends of one pocket agree along %s", axis);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void aPocketOf4096CellsIsClosedAndOf4097IsOutside(GameTestHelper helper) {
        BlockPos from = BlockPos.ZERO;
        BlockPos to = from.offset(15, 15, 15);
        BoundingBox zone = BoundingBox.fromCorners(from.offset(-1, -1, -1), to.offset(2, 1, 1));
        GridBlocks cube = new GridBlocks().fill(from, to, AIR);
        expect(
                helper,
                OutpostShell.survey(cube.terrain(), zone, TUNING, BREAKABLE).isOutside(from) == false,
                "a 16 cube is 4096 cells and a room");
        GridBlocks plusOne = new GridBlocks().fill(from, to, AIR).set(to.east(), AIR);
        expect(
                helper,
                OutpostShell.survey(plusOne.terrain(), zone, TUNING, BREAKABLE).isOutside(from),
                "one cell more is 4097 and outside");
        helper.succeed();
    }

    // ----- doors -----

    private static BlockState door(Block block, DoubleBlockHalf half, boolean open) {
        return block.defaultBlockState()
                .setValue(DoorBlock.HALF, half)
                .setValue(BlockStateProperties.OPEN, open);
    }

    /** The hall with a doorway in its east wall that opens on a corridor of 35 cells, which is outside. */
    private static GridBlocks hallWithDoor(Block door, boolean open) {
        return hall(SLAG)
                .fill(CENTRE.offset(6, 1, 0), CENTRE.offset(40, 2, 0), AIR)
                .set(CENTRE.offset(5, 1, 0), door(door, DoubleBlockHalf.LOWER, open))
                .set(CENTRE.offset(5, 2, 0), door(door, DoubleBlockHalf.UPPER, open));
    }

    @GameTest
    public void anOpenDoorLetsReachThroughAndAClosedDoorStopsIt(GameTestHelper helper) {
        expect(
                helper,
                survey(hallWithDoor(Blocks.OAK_DOOR, true)).isOutside(inside()),
                "through an open door the hall joins the corridor");
        expect(
                helper,
                !survey(hallWithDoor(Blocks.OAK_DOOR, false)).isOutside(inside()),
                "a closed door keeps the hall a room");
        expect(
                helper,
                survey(hallWithDoor(Blocks.OAK_DOOR, false)).isOutside(CENTRE.offset(8, 1, 0)),
                "the corridor stays outside");
        helper.succeed();
    }

    @GameTest
    public void aClosedUnratedDoorIsACandidateAndAClosedRatedDoorSeals(GameTestHelper helper) {
        BlockPos lower = CENTRE.offset(5, 1, 0);
        BlockPos upper = lower.above();
        Shell oak = survey(hallWithDoor(Blocks.OAK_DOOR, false));
        expect(
                helper,
                oak.candidates().contains(lower) && oak.candidates().contains(upper),
                "a closed door unrated at layer 3 stops reach and can itself be broken: %s",
                oak.candidates());
        Shell iron = survey(hallWithDoor(Blocks.IRON_DOOR, false));
        expect(
                helper,
                !iron.isOutside(inside()) && iron.candidates().isEmpty(),
                "a closed door the test pack rates is shell: %s",
                iron.candidates());
        helper.succeed();
    }

    // ----- unloaded chunks and cost -----

    @GameTest
    public void anUnloadedNeighbourChunkCountsAsOutside(GameTestHelper helper) {
        // The hall's east wall stands in chunk 1, whose interior air reaches the chunk's edge at x = 15.
        BlockPos east = new BlockPos(11, 64, 0);
        GridBlocks grid = new GridBlocks()
                .fill(east.offset(-5, 0, -5), east.offset(5, 6, 5), PLANKS)
                .fill(east.offset(-4, 1, -4), east.offset(4, 5, 4), AIR);
        Outpost outpost = new Outpost(OUTPOST.charter(), 3, Level.OVERWORLD, east);
        BoundingBox zone = OutpostSettling.zone(outpost, SettlingTuning.DEFAULT);
        Shell loaded = OutpostShell.survey(grid.terrain(), zone, TUNING, BREAKABLE);
        expect(helper, !loaded.isOutside(east.above()), "with every chunk loaded the hall is a room");
        Shell shell = OutpostShell.survey(
                grid.terrainWithout(chunk -> ChunkPos.getX(chunk) == 1), zone, TUNING, BREAKABLE);
        expect(
                helper,
                shell.isOutside(east.above()),
                "a hall whose air reaches a chunk that is not loaded is outside: nothing is known of it");
        expect(helper, shell.isOutside(new BlockPos(20, 70, 0)), "the unloaded chunk itself is outside");
        helper.succeed();
    }

    @GameTest
    public void aZoneNextToACaveStaysUnderTheReadBudget(GameTestHelper helper) {
        GridBlocks grid = hall(PLANKS)
                .fill(new BlockPos(ZONE.maxX() + 1, -100, -100), new BlockPos(ZONE.maxX() + 200, 100, 100), AIR);
        Shell shell = survey(grid);
        expect(
                helper,
                shell.isOutside(new BlockPos(ZONE.maxX() + 1, 0, 0)),
                "the cave next to the zone is outside");
        expect(
                helper,
                shell.reads() > 0 && shell.reads() < 60_000,
                "a flood next to a cave of 8 million cells should read under 60,000 blocks, it read %s",
                shell.reads());
        helper.succeed();
    }

    @GameTest
    public void openedGivesWhatAFreshSurveyGives(GameTestHelper helper) {
        // A closed hall with a long corridor beyond its east wall; a visit breaks the wall's middle plank.
        GridBlocks before = hall(PLANKS).fill(CENTRE.offset(6, 3, 0), CENTRE.offset(45, 3, 0), AIR);
        Shell incremental = survey(before);
        BlockPos wall = CENTRE.offset(5, 3, 0);
        expect(helper, incremental.candidates().contains(wall), "the wall plank by the corridor is a candidate");
        expect(helper, !incremental.isOutside(inside()), "the hall is closed before the break");
        before.set(wall, AIR);
        incremental.opened(wall);
        Shell fresh = survey(before);
        expect(helper, incremental.isOutside(inside()) && fresh.isOutside(inside()), "the break opens the hall");
        expect(
                helper,
                new HashSet<>(incremental.candidates()).equals(new HashSet<>(fresh.candidates())),
                "the incremental candidates should equal a fresh survey's: %s against %s",
                incremental.candidates().size(),
                fresh.candidates().size());
        Set<BlockPos> differing = new HashSet<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                new BlockPos(ZONE.minX() - 1, ZONE.minY() - 1, ZONE.minZ() - 1),
                new BlockPos(ZONE.maxX() + 1, ZONE.maxY() + 1, ZONE.maxZ() + 1))) {
            if (incremental.isOutside(pos) != fresh.isOutside(pos)) {
                differing.add(pos.immutable());
            }
        }
        expect(helper, differing.isEmpty(), "outside cells that differ from a fresh survey: %s", differing);
        expect(
                helper,
                incremental.gaps(OUTPOST) == fresh.gaps(OUTPOST),
                "the gaps agree: %s and %s",
                incremental.gaps(OUTPOST),
                fresh.gaps(OUTPOST));
        helper.succeed();
    }

    // ----- sheltered, for the readout -----

    @GameTest
    public void aSpotInAClosedRatedHallIsShelteredAndOneAtAnOpenHatchIsNot(GameTestHelper helper) {
        Predicate<BlockState> breakable = BREAKABLE;
        BlockPos spot = inside();
        expect(
                helper,
                OutpostShell.sheltered(hall(SLAG).terrain(), spot, breakable, TUNING),
                "a closed slag hall shelters a block put inside");
        GridBlocks hatch = hall(SLAG).fill(CENTRE.offset(0, 6, 0), CENTRE.offset(0, 6 + 32, 0), AIR);
        expect(
                helper,
                !OutpostShell.sheltered(hatch.terrain(), spot, breakable, TUNING),
                "a hall open to a shaft over the span shelters nothing");
        expect(
                helper,
                !OutpostShell.sheltered(hatch.terrain(), CENTRE.offset(0, 6, 0), breakable, TUNING),
                "a block at the hatch itself has the shaft behind it");
        GridBlocks smallHatch = hall(SLAG).fill(CENTRE.offset(0, 6, 0), CENTRE.offset(0, 9, 0), AIR);
        expect(
                helper,
                OutpostShell.sheltered(smallHatch.terrain(), spot, breakable, TUNING),
                "a hatch onto a short pocket is still a room");
        expect(
                helper,
                !OutpostShell.sheltered(
                        hall(SLAG).terrainWithout(chunk -> true), spot, breakable, TUNING),
                "a spot whose chunks are not loaded is not promised");
        helper.succeed();
    }

    // ----- what is a candidate -----

    @GameTest
    public void wildBlocksAreNeverCandidatesAndPlacedBlocksAre(GameTestHelper helper) {
        List<Block> wild = List.of(
                Blocks.STONE,
                Blocks.DEEPSLATE,
                block("ironium_ore"),
                block("goldium_ore"),
                LayerBlocks.BREACH_CRUST,
                LayerBlocks.GRAINED_CRUST,
                LayerBlocks.GLOWCAP,
                block("gas_pocket"),
                block("company_rock"),
                block("great_stalk"));
        for (Block block : wild) {
            expect(
                    helper,
                    !BREAKABLE.test(block.defaultBlockState()),
                    "%s is natural and never a candidate",
                    BuiltInRegistries.BLOCK.getKey(block));
        }
        for (Block block : List.of(
                LayerBlocks.PACKED_SHALE, PLANKS.getBlock(), Blocks.COBBLESTONE, Blocks.OAK_DOOR, Blocks.TORCH)) {
            expect(
                    helper,
                    BREAKABLE.test(block.defaultBlockState()),
                    "%s is placed and unrated at layer 3, so a candidate",
                    BuiltInRegistries.BLOCK.getKey(block));
        }
        expect(helper, !BREAKABLE.test(AIR), "air is not a block");
        expect(helper, !BREAKABLE.test(Blocks.WATER.defaultBlockState()), "a fluid is not a block");
        expect(helper, !BREAKABLE.test(SLAG), "slag holds at layer 3");
        helper.succeed();
    }

    @GameTest
    public void realHoldsSlagHoldsAtLayer3StalkPlanksDoNotAndEverythingHoldsAtLayer1(GameTestHelper helper) {
        expect(helper, OutpostSettling.holds(SLAG, LAYER_3), "slag brick holds at layer 3");
        expect(helper, OutpostSettling.holds(LayerBlocks.GREEN_SHALE.defaultBlockState(), LAYER_3), "green shale holds");
        expect(helper, !OutpostSettling.holds(PLANKS, LAYER_3), "stalk planks do not hold at layer 3");
        expect(
                helper,
                !OutpostSettling.holds(LayerBlocks.PACKED_SHALE.defaultBlockState(), LAYER_3),
                "layer 1's packed shale does not hold at layer 3");
        Optional<Ratings> layer1 = DepthRatings.forLayer(1);
        for (BlockState state : List.of(PLANKS, Blocks.COBBLESTONE.defaultBlockState(), SLAG, AIR)) {
            expect(helper, OutpostSettling.holds(state, layer1), "%s holds at layer 1, which asks nothing", state);
        }
        expect(
                helper,
                !OutpostSettling.holds(SLAG, Optional.empty()),
                "a depth with no rated material holds nothing, slag brick included");
        helper.succeed();
    }
}
