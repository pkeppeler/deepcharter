package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.surface.GreatStalk;
import io.github.pkeppeler.deepcharter.surface.GreatStalkLayout;
import io.github.pkeppeler.deepcharter.surface.GreatStalkLayout.Cell;
import io.github.pkeppeler.deepcharter.surface.GreatStalkLayout.Part;
import io.github.pkeppeler.deepcharter.surface.GreatStalkShape;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "great-stalk-options" (#910, phase 1): the three silhouettes a great stalk may grow in (braid, crook, fork), grown
 * side by side on the open regolith plain the texture scenarios use (found from the colony, not at a fixed place), each on a flat pad of
 * its own. A still of each from the same angle and distance, one of all three together, then a recording in which the lowest thick part
 * of each is broken in turn and what hangs from it comes down in a spore puff. Phase 2 removes the scenario with the options that lose.
 */
public class GreatStalkOptionsScenario extends EvidenceScenario {
    /** One option: its still name and the seed of the stalk shown. */
    private record Option(String name, GreatStalkShape shape, long seed) {}

    private static final List<Option> OPTIONS = List.of(
            new Option("braid", GreatStalkShape.BRAID, 3),
            new Option("crook", GreatStalkShape.CROOK, 5),
            new Option("fork", GreatStalkShape.FORK, 11));
    /** The stalks stand this far apart along the plain's east-west line, with a pad of {@link #PAD_REACH} on each side of each. */
    private static final int SPACING = 22;

    private static final int PAD_REACH = 10;
    private static final int PAD_HEIGHT = 24;
    /** The camera looks north at a stalk from this far south of its base and this far above it, at the point this high up it. */
    private static final double DISTANCE = 26;

    private static final double EYE_HEIGHT = 7;
    private static final double LOOK_HEIGHT = 7;
    /** All three together are seen from further off, at the middle of the line. */
    private static final double GROUP_DISTANCE = 52;

    private static final double GROUP_EYE_HEIGHT = 9;
    private static final double GROUP_LOOK_HEIGHT = 6;
    private static final int SETTLE_TICKS = 20;
    private static final int TICKS_PER_FRAME = 2;
    /** The sag of the tallest option, its bursts and a margin; the recording stops when it is over. */
    private static final int COLLAPSE_TICKS = 90;
    /** The cell of the layout that is broken: the lowest thick part with something above it, this high from the base. */
    private static final int BREAK_LEVEL = 2;

    private static final int CHUNK_RADIUS = 2;

    @Override
    protected String name() {
        return "great-stalk-options";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        TextureTestWorld.open(context, shot -> screenshot(context, shot), world -> {
            Vec3 plain = world.regolithPlain();
            List<BlockPos> origins = new ArrayList<>();
            for (int index = 0; index < OPTIONS.size(); index++) {
                origins.add(BlockPos.containing(plain).offset((index - 1) * SPACING, 0, 0));
            }
            world.serverDo(server -> stage(TestLayers.level(server, 0), origins));
            EvidencePins.handsHidden = true;
            EvidencePins.itemNameHidden = true;
            try {
                stills(context, world, origins);
                collapses(context, world, origins);
            } finally {
                EvidencePins.reset();
            }
        });
    }

    /**
     * Levels a pad under each stalk (the ground of the plain at its middle, over a cleared box) and grows the option on it, so that the
     * three stand on the same ground with nothing of the world's own growth beside them.
     */
    private static void stage(ServerLevel level, List<BlockPos> origins) {
        BlockPos middle = origins.get(origins.size() / 2);
        BlockState floor = level.getBlockState(middle.below());
        for (BlockPos origin : origins) {
            TextureTestWorld.loadChunks(level, origin, CHUNK_RADIUS);
            for (BlockPos cell : BlockPos.betweenClosed(
                    origin.offset(-PAD_REACH, -1, -PAD_REACH), origin.offset(PAD_REACH, PAD_HEIGHT, PAD_REACH))) {
                level.setBlock(
                        cell,
                        cell.getY() < origin.getY() ? floor : Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_ALL); // room-carver: a pad of the plain's own ground on the surface, which is
                // not layer rock
            }
        }
        for (int index = 0; index < OPTIONS.size(); index++) {
            Option option = OPTIONS.get(index);
            if (!GreatStalk.place(level, RandomSource.create(option.seed()), origins.get(index), option.shape())) {
                throw new AssertionError(
                        "the " + option.name() + " would not grow on its pad at " + origins.get(index));
            }
        }
    }

    private void stills(ClientGameTestContext context, TextureTestWorld world, List<BlockPos> origins) {
        for (int index = 0; index < OPTIONS.size(); index++) {
            look(world, origins.get(index), DISTANCE, EYE_HEIGHT, LOOK_HEIGHT);
            world.still(OPTIONS.get(index).name());
        }
        look(world, origins.get(origins.size() / 2), GROUP_DISTANCE, GROUP_EYE_HEIGHT, GROUP_LOOK_HEIGHT);
        world.still("all-three");
        frame(context);
    }

    /** The camera {@code distance} blocks south of the base of the stalk at {@code origin}, looking north. */
    private static void look(TextureTestWorld world, BlockPos origin, double distance, double eye, double target) {
        Vec3 base = Vec3.atBottomCenterOf(origin).add(1, 0, 1);
        world.view(0, base.add(0, eye, distance), base.add(0, target, 0), SETTLE_TICKS);
    }

    /** Each option in turn: the camera on it, one break, and the frames until what hung from it is down. */
    private void collapses(ClientGameTestContext context, TextureTestWorld world, List<BlockPos> origins) {
        for (int index = 0; index < OPTIONS.size(); index++) {
            Option option = OPTIONS.get(index);
            BlockPos origin = origins.get(index);
            GreatStalkLayout layout = option.shape().layout(RandomSource.create(option.seed())::nextInt);
            Cell cut = layout.parts().keySet().stream()
                    .filter(cell -> cell.y() == BREAK_LEVEL
                            && layout.parts().get(cell) == Part.TRUNK
                            && layout.parts().containsKey(new Cell(cell.x(), cell.y() + 1, cell.z())))
                    .min(Comparator.comparingInt(Cell::x).thenComparingInt(Cell::z))
                    .orElseThrow();
            look(world, origin, DISTANCE, EYE_HEIGHT, LOOK_HEIGHT);
            frame(context);
            world.serverDo(
                    server -> TestLayers.level(server, 0).destroyBlock(origin.offset(cut.x(), cut.y(), cut.z()), true));
            for (int tick = 0; tick < COLLAPSE_TICKS; tick += TICKS_PER_FRAME) {
                context.waitTicks(TICKS_PER_FRAME); // tick-wait: the collapse takes a fixed number of server ticks
                context.runOnClient(client -> client.gui.toastManager().clear());
                frame(context);
            }
        }
    }
}
