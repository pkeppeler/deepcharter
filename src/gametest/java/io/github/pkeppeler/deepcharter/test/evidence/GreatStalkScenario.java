package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.surface.GreatStalk;
import io.github.pkeppeler.deepcharter.surface.GreatStalkBraid;
import io.github.pkeppeler.deepcharter.surface.GreatStalkLayout;
import io.github.pkeppeler.deepcharter.surface.GreatStalkLayout.Cell;
import io.github.pkeppeler.deepcharter.surface.GreatStalkLayout.Part;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.TestColony;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Comparator;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "great-stalk" (#910): the great stalk, a braid, grown on the open regolith plain the texture scenarios use (found
 * from the colony, not at a fixed place), on a flat pad of the plain's own ground. A still from the front and one from the back (the
 * silhouette is lopsided), one from a way off among a patch of salvage crates, and a recording in which the lowest part of a trunk is
 * broken and what hangs from it comes down in a spore puff.
 */
public class GreatStalkScenario extends EvidenceScenario {
    /** The seed of the stalk shown. */
    private static final long SEED = 3;

    private static final int PAD_REACH = 12;
    private static final int PAD_HEIGHT = 24;
    /**
     * The camera stands {@link #FILL} of the stalk's height away (never nearer than {@link #LEAST_DISTANCE}), a third of the way up it,
     * and aims at its middle, so the stalk fills most of the frame; the front view looks away from the colony.
     */
    private static final double FILL = 0.8;

    private static final double LEAST_DISTANCE = 11;
    private static final double EYE_FRACTION = 1.0 / 3;
    private static final double LOOK_FRACTION = 0.5;
    /** The wide view: from this far off and this high, among the crates that lie this far in front of the stalk. */
    private static final double WIDE_DISTANCE = 30;

    private static final double WIDE_EYE_HEIGHT = 3;
    private static final double WIDE_LOOK_HEIGHT = 7;
    private static final int CRATES_IN_FRONT = 8;
    private static final int CHUNK_RADIUS = 2;
    private static final int SETTLE_TICKS = 20;
    private static final int TICKS_PER_FRAME = 2;
    /** The sag of the tallest trunk, its bursts and a margin; the recording stops when it is over. */
    private static final int COLLAPSE_TICKS = 90;
    /** The cell of the layout that is broken: the lowest part of a trunk with something above it, this high from the base. */
    private static final int BREAK_LEVEL = 2;

    @Override
    protected String name() {
        return "great-stalk";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        TextureTestWorld.open(context, shot -> screenshot(context, shot), world -> {
            BlockPos origin = BlockPos.containing(world.regolithPlain());
            int away = awayFromColony(world, origin);
            world.serverDo(server -> stage(TestLayers.level(server, 0), origin, away));
            EvidencePins.handsHidden = true;
            EvidencePins.itemNameHidden = true;
            try {
                stills(context, world, origin, away);
                collapse(context, world, origin, away);
            } finally {
                EvidencePins.reset();
            }
        });
    }

    /**
     * Levels a pad under the stalk (the ground of the plain at its origin, over a cleared box), grows it, and lays a patch of crates in
     * front of it, so that the stalk stands alone on the plain's own ground.
     */
    private static void stage(ServerLevel level, BlockPos origin, int away) {
        TextureTestWorld.loadChunks(level, origin, CHUNK_RADIUS);
        BlockState floor = level.getBlockState(origin.below());
        for (BlockPos cell : BlockPos.betweenClosed(
                origin.offset(-PAD_REACH, -1, -PAD_REACH), origin.offset(PAD_REACH, PAD_HEIGHT, PAD_REACH))) {
            level.setBlock(
                    cell,
                    cell.getY() < origin.getY() ? floor : Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_ALL); // room-carver: a pad of the plain's own ground on the surface, which is not
            // layer rock
        }
        if (!GreatStalk.place(level, RandomSource.create(SEED), origin, false)) {
            throw new AssertionError("the great stalk would not grow on its pad at " + origin);
        }
        BlockState crate = BuiltInRegistries.BLOCK
                .getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "salvage_crate"))
                .defaultBlockState();
        Direction facing = away > 0 ? Direction.SOUTH : Direction.NORTH;
        BlockPos patch = origin.offset(1, 0, -away * CRATES_IN_FRONT);
        level.setBlock(patch, crate.setValue(BlockStateProperties.HORIZONTAL_FACING, facing), Block.UPDATE_ALL);
        level.setBlock(patch.east(), crate.setValue(BlockStateProperties.HORIZONTAL_FACING, facing), Block.UPDATE_ALL);
        level.setBlock(
                patch.east().above(),
                crate.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST),
                Block.UPDATE_ALL);
        level.setBlock(
                patch.west(2).offset(0, 0, away),
                crate.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST),
                Block.UPDATE_ALL);
    }

    /** Which way along z points away from the colony from {@code at}: 1 for south, -1 for north. */
    private static int awayFromColony(TextureTestWorld world, BlockPos at) {
        BlockPos colony = world.serverGet(server -> TestColony.anchor(
                Colony.placed(server).orElseThrow(() -> new AssertionError("no colony")), ColonyAnchor.STATUE));
        return at.getZ() >= colony.getZ() ? 1 : -1;
    }

    private void stills(ClientGameTestContext context, TextureTestWorld world, BlockPos origin, int away) {
        context.runOnClient(client -> client.gui.toastManager().clear());
        look(world, origin, away);
        world.still("great-stalk-front");
        look(world, origin, -away);
        world.still("great-stalk-back");
        Vec3 middle = Vec3.atBottomCenterOf(origin).add(1, 0, 1);
        world.view(
                0,
                middle.add(0, WIDE_EYE_HEIGHT, -away * WIDE_DISTANCE),
                middle.add(0, WIDE_LOOK_HEIGHT, 0),
                SETTLE_TICKS);
        world.still("great-stalk-wide");
        frame(context);
    }

    /** The camera looking along z in the direction {@code toward} at the stalk grown at {@code origin}, framed from its bounds. */
    private static void look(TextureTestWorld world, BlockPos origin, int toward) {
        GreatStalkLayout layout = GreatStalkBraid.layout(RandomSource.create(SEED)::nextInt);
        int height = layout.parts().keySet().stream().mapToInt(Cell::y).max().orElseThrow() + 1;
        double low = layout.parts().keySet().stream().mapToInt(Cell::x).min().orElseThrow();
        double high = layout.parts().keySet().stream().mapToInt(Cell::x).max().orElseThrow();
        double lowZ = layout.parts().keySet().stream().mapToInt(Cell::z).min().orElseThrow();
        double highZ = layout.parts().keySet().stream().mapToInt(Cell::z).max().orElseThrow();
        Vec3 middle = Vec3.atBottomCenterOf(origin).add((low + high) / 2 + 0.5, 0, (lowZ + highZ) / 2 + 0.5);
        double distance = Math.max(LEAST_DISTANCE, FILL * height) + (highZ - lowZ) / 2;
        world.view(
                0,
                middle.add(0, height * EYE_FRACTION, -toward * distance),
                middle.add(0, height * LOOK_FRACTION, 0),
                SETTLE_TICKS);
    }

    /** One break, and the frames until what hung from it is down. */
    private void collapse(ClientGameTestContext context, TextureTestWorld world, BlockPos origin, int away) {
        GreatStalkLayout layout = GreatStalkBraid.layout(RandomSource.create(SEED)::nextInt);
        Cell cut = layout.parts().keySet().stream()
                .filter(cell -> cell.y() == BREAK_LEVEL
                        && layout.parts().get(cell) == Part.TRUNK
                        && layout.parts().containsKey(new Cell(cell.x(), cell.y() + 1, cell.z())))
                .min(Comparator.comparingInt(Cell::x).thenComparingInt(Cell::z))
                .orElseThrow();
        look(world, origin, away);
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
