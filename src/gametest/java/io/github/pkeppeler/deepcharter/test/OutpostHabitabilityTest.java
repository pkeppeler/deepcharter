package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.colony.ColonyKit;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.outpost.OutpostData;
import io.github.pkeppeler.deepcharter.outpost.OutpostData.Outpost;
import io.github.pkeppeler.deepcharter.outpost.OutpostHabitability;
import io.github.pkeppeler.deepcharter.outpost.OutpostHabitability.Status;
import io.github.pkeppeler.deepcharter.outpost.RatingAxis;
import io.github.pkeppeler.deepcharter.outpost.Ratings;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.SuitScenes;
import io.github.pkeppeler.deepcharter.test.support.SuitScenes.Hall;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Server GameTests for #993's outpost habitability (ADR 0076, ADR 0083): whether a hall's shell holds its depth's ratings, read from the
 * blocks. The kit's hall leaves its pit and hatch open, so against a row that no layer asks yet (heat and quake 2) it holds only once they
 * are patched with slag brick. A layer-1 row asks nothing, so the kit's hall is habitable as built. No depth has a row for the unbuilt depth,
 * so nothing holds there.
 */
public class OutpostHabitabilityTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 400;
    /** An example of what a later layer might ask, as {@code OutpostRatingsTest} has it: heat and quake at 2, pressure and flooding at 1. */
    private static final Ratings EXAMPLE_LAYER = new Ratings(2, 1, 1, 2);

    /** The hall's whole shell: the box of {@code EDGE} 5 and {@code HEIGHT} 5 plus the floor and ceiling, less its 9 by 9 by 5 inside. */
    private static final int SHELL_CELLS = 442;
    /** The two by two shaft the test stage leaves through the ceiling's hatch: four gaps. */
    private static final int SHAFT_GAPS = 4;

    private static final int PIT_RADIUS = 1;
    private static final int HATCH_RADIUS = 2;
    private static final int TOP_ROW = 6;

    private static List<BlockPos> openCells(Hall hall) {
        List<BlockPos> cells = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                hall.floor().offset(-HATCH_RADIUS, 0, -HATCH_RADIUS),
                hall.floor().offset(HATCH_RADIUS, TOP_ROW, HATCH_RADIUS))) {
            int dy = pos.getY() - hall.floor().getY();
            int out = Math.max(
                    Math.abs(pos.getX() - hall.floor().getX()),
                    Math.abs(pos.getZ() - hall.floor().getZ()));
            boolean pit = dy == 0 && out <= PIT_RADIUS;
            boolean hatch = dy == TOP_ROW && out <= HATCH_RADIUS;
            if ((pit || hatch) && !hall.level().getBlockState(pos).isAir()) {
                cells.add(pos.immutable());
            }
        }
        return cells;
    }

    private static List<BlockState> shell(Hall hall) {
        List<BlockState> states = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                hall.floor().offset(-5, 0, -5), hall.floor().offset(5, TOP_ROW, 5))) {
            states.add(hall.level().getBlockState(pos));
        }
        return states;
    }

    private static void patch(Hall hall, List<BlockPos> cells, Block with) {
        for (BlockPos pos : cells) {
            hall.level().setBlock(pos, with.defaultBlockState(), 3);
        }
    }

    /** The shell's unrated cells and the axes they miss are what is built; patching them with slag brick makes the hall hold, a pane of glass breaks it again. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aKitHallHoldsAnExampleRowOnceItsPitAndHatchAreRatedAndAPaneOfGlassBreaksIt(GameTestHelper helper) {
        Hall hall = SuitScenes.stageHall(helper, 6400, "habitable-kit");
        SuitScenes.whenHallReady(helper, hall, () -> {
            Outpost outpost = hall.build();
            List<BlockPos> open = openCells(hall);
            Status built = OutpostHabitability.of(hall.level(), outpost, Optional.of(EXAMPLE_LAYER));
            expect(
                    helper,
                    !built.habitable()
                            && built.shellBlocks() == SHELL_CELLS - SHAFT_GAPS
                            && built.unrated() == open.size()
                            && built.shortOn().equals(OutpostHabitability.AXES),
                    "the kit leaves a crust pit and a rock hatch, short on every axis the suit is judged on, never quake: %s with %s open cells",
                    built,
                    open.size());
            expect(
                    helper,
                    open.size() == 9 + 21,
                    "a 3 by 3 pit and a 5 by 5 hatch less a 2 by 2 shaft, listed %s",
                    open.size());

            patch(hall, open, SlagBrick.BLOCK);
            Status patched = OutpostHabitability.of(hall.level(), outpost, Optional.of(EXAMPLE_LAYER));
            expect(
                    helper,
                    patched.habitable()
                            && patched.unrated() == 0
                            && patched.shortOn().isEmpty(),
                    "slag brick in the pit and the hatch makes the shell hold: %s",
                    patched);

            // One pane of window glass holds heat and flooding but not pressure: short on pressure alone, however many
            // blocks hold.
            BlockPos pane = hall.floor().offset(5, 3, 0);
            hall.level().setBlock(pane, ColonyKit.WINDOW_GLASS.defaultBlockState(), 3);
            Status glass = OutpostHabitability.of(hall.level(), outpost, Optional.of(new Ratings(2, 1, 2, 0)));
            expect(
                    helper,
                    !glass.habitable()
                            && glass.unrated() == 1
                            && glass.shortOn().equals(Set.of(RatingAxis.PRESSURE)),
                    "one glass pane is short on pressure and on nothing else: %s",
                    glass);
            hall.level().setBlock(pane, ColonyKit.RIVETED_PLATE.defaultBlockState(), 3);

            // A gap, air or a fluid, is not a block and is not judged: it is the creature rule's exposure.
            BlockPos hole = hall.floor().offset(5, 2, 1);
            hall.level().setBlock(hole, Blocks.AIR.defaultBlockState(), 3);
            hall.level().setBlock(hall.floor().offset(5, 2, 2), Blocks.WATER.defaultBlockState(), 3);
            Status gaps = OutpostHabitability.of(hall.level(), outpost, Optional.of(EXAMPLE_LAYER));
            expect(
                    helper,
                    gaps.habitable() && gaps.shellBlocks() == patched.shellBlocks() - 2,
                    "a gap in the shell is not judged, and not counted: %s against %s",
                    gaps,
                    patched);

            // It reads blocks and changes none.
            List<BlockState> before = shell(hall);
            OutpostHabitability.of(hall.level(), outpost, Optional.of(EXAMPLE_LAYER));
            OutpostHabitability.of(hall.level(), outpost, OutpostHabitability.requiredAt(hall.level(), outpost));
            expect(helper, before.equals(shell(hall)), "reading the shell must change no block");
        });
    }

    /** Layer 1 asks nothing, so the kit's hall holds as built: its open pit and hatch are no shortfall against a row of zeros. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aLayerOneKitHallIsHabitableAsBuilt(GameTestHelper helper) {
        Hall hall = SuitScenes.stageHall(helper, 6460, "habitable-layer-one");
        SuitScenes.whenHallReady(helper, hall, () -> {
            Outpost outpost = hall.build();
            expect(
                    helper,
                    OutpostHabitability.requiredAt(hall.level(), outpost).equals(Optional.of(Ratings.NONE)),
                    "layer 1 asks nothing");
            Status status = OutpostHabitability.of(
                    hall.level(), outpost, OutpostHabitability.requiredAt(hall.level(), outpost));
            expect(
                    helper,
                    status.habitable() && status.unrated() == 0 && status.shellBlocks() == SHELL_CELLS - SHAFT_GAPS,
                    "a hall in layer 1 is habitable: %s",
                    status);
        });
    }

    /** The unbuilt depth has no rated material, so no hall there holds, whatever it is made of: all its blocks are unrated. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aHallInTheUnbuiltDepthIsNeverHabitable(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, 1);
        SuitScenes.Pocket pocket = SuitScenes.unbuilt(level, 6520);
        Outpost outpost = new Outpost(new CharterId(UUID.randomUUID()), 1, level.dimension(), pocket.floor());
        FarChunks.awaitEntityTicking(helper, level, pocket.floor(), () -> {
            expect(
                    helper,
                    OutpostHabitability.requiredAt(level, outpost).isEmpty(),
                    "unbuilt depth has no row at %s",
                    CampaignBands.UNBUILT.minY() + LayerTuning.DEFAULT.crustThickness());
            Status status = OutpostHabitability.of(level, outpost, OutpostHabitability.requiredAt(level, outpost));
            expect(
                    helper,
                    !status.habitable() && status.unrated() == status.shellBlocks() && status.shellBlocks() > 0,
                    "nothing holds in unbuilt depth, not even the rock around a hall: %s",
                    status);
            // Slag brick, the best there is, does not change it.
            for (BlockPos pos : BlockPos.betweenClosed(
                    pocket.floor().offset(-5, 0, -5), pocket.floor().offset(5, 6, 5))) {
                if (Math.abs(pos.getX() - pocket.floor().getX()) == 5
                        && level.getBlockState(pos).isAir()) {
                    level.setBlock(pos, SlagBrick.BLOCK.defaultBlockState(), 3);
                }
            }
            expect(
                    helper,
                    !OutpostHabitability.of(level, outpost, Optional.empty()).habitable(),
                    "slag brick in the walls still leaves a hall in unbuilt depth uninhabitable");
            helper.succeed();
        });
    }

    /** The record finds a hall by a position inside it, never one in its shell or in another dimension. */
    @GameTest
    public void theRecordFindsAHallByAPositionInsideItAndInItsOwnDimension(GameTestHelper helper) {
        CharterId charter = new CharterId(UUID.randomUUID());
        var dimension = helper.getLevel().dimension();
        Outpost outpost = new Outpost(charter, 1, dimension, new BlockPos(10, 70, 10));
        OutpostData data = SuitScenes.dataWith(outpost);
        expect(
                helper,
                data.enclosing(dimension, new BlockPos(10, 71, 10)).isPresent(),
                "the middle of the hall is enclosed");
        expect(
                helper,
                data.enclosing(dimension, new BlockPos(14, 75, 6)).isPresent(),
                "the hall's corner is inside it");
        expect(
                helper,
                data.enclosing(dimension, new BlockPos(10, 70, 10)).isEmpty(),
                "the floor is the shell, not the inside");
        expect(helper, data.enclosing(dimension, new BlockPos(10, 76, 10)).isEmpty(), "the ceiling is the shell");
        expect(helper, data.enclosing(dimension, new BlockPos(15, 72, 10)).isEmpty(), "the wall is the shell");
        expect(helper, data.enclosing(dimension, new BlockPos(10, 72, -50)).isEmpty(), "a place far off is outside");
        expect(
                helper,
                data.enclosing(net.minecraft.world.level.Level.NETHER, new BlockPos(10, 71, 10))
                        .isEmpty(),
                "the same place in another dimension is another place");
        helper.succeed();
    }
}
