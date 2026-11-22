package io.github.pkeppeler.deepcharter.surface;

import io.github.pkeppeler.deepcharter.surface.GreatStalkLayout.Cell;
import io.github.pkeppeler.deepcharter.surface.GreatStalkLayout.Part;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The shape of a great stalk, the rare giant of the fungal stalks: a trunk two blocks thick and much taller than an ordinary column
 * ({@link SurfaceTuning#greatMinHeight()} to {@link SurfaceTuning#greatMaxHeight()}), under a cap. The cap is a rim, the trunk's 2x2
 * and a block of reach on every side without its four corners, over a dome of the trunk's own 2x2. Everything is the stalk's own
 * blocks ({@code great_stalk} and {@code great_stalk_cap}), so it falls as {@link StalkCollapse} says.
 */
public final class GreatStalk {
    /** The trunk is this many blocks on a side. */
    private static final int THICKNESS = 2;
    /** The rim of the cap reaches this far past the trunk on every side. */
    private static final int CAP_REACH = 1;
    /** The rim and the dome, above the trunk. */
    private static final int CAP_LAYERS = 2;

    /**
     * The silhouette great stalks grow in, one of the options of #910, or {@code null} for the straight stalk described above. A
     * temporary switch: the option picked stays and this and the rest go.
     */
    private static final @Nullable GreatStalkShape SHAPE = null;

    private static final Block TRUNK = block("great_stalk");
    private static final Block CAP = block("great_stalk_cap");
    private static final Block STRAND = block("fungal_stalk");

    private GreatStalk() {}

    private static Block block(String path) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("deepcharter", path));
    }

    /**
     * Grows a great stalk whose trunk has {@code origin} as its lowest corner (the 2x2 runs east and south from it), unless the ground
     * or the air does not allow one: all four base blocks must be air over solid ground, and the air above them must be clear for the
     * shortest trunk and its cap. A tall trunk is cut short to the clear air above its four columns. Returns whether it grew.
     */
    public static boolean place(WorldGenLevel level, RandomSource random, BlockPos origin) {
        return place(level, random, origin, SHAPE);
    }

    /**
     * Grows a great stalk of the given shape ({@code null} for the straight one) with {@code origin} as the lowest corner of its layout:
     * every trunk cell must be air, and those at the lowest level over solid ground, or nothing grows. A cap, a root or a strand that has
     * no room is left out, with whatever then hangs from nothing. Returns whether it grew.
     */
    public static boolean place(
            WorldGenLevel level, RandomSource random, BlockPos origin, @Nullable GreatStalkShape shape) {
        if (shape == null) {
            return placeStraight(level, random, origin);
        }
        GreatStalkLayout layout = shape.layout(random::nextInt);
        Set<Cell> blocked = new HashSet<>();
        for (Cell cell : layout.parts().keySet()) {
            BlockPos pos = origin.offset(cell.x(), cell.y(), cell.z());
            boolean room = level.getBlockState(pos).isAir();
            boolean grounded =
                    cell.y() > 0 || level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
            if (!room || !grounded) {
                if (!layout.optional().contains(cell) && layout.parts().get(cell) == Part.TRUNK) {
                    return false;
                }
                blocked.add(cell);
            }
        }
        BlockState strand = STRAND.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        layout.without(blocked)
                .parts()
                .forEach((cell, part) -> level.setBlock(
                        origin.offset(cell.x(), cell.y(), cell.z()),
                        switch (part) {
                            case TRUNK -> TRUNK.defaultBlockState();
                            case CAP -> CAP.defaultBlockState();
                            case STRAND -> strand;
                        },
                        Block.UPDATE_CLIENTS));
        return true;
    }

    private static boolean placeStraight(WorldGenLevel level, RandomSource random, BlockPos origin) {
        SurfaceTuning tuning = SurfaceTuning.DEFAULT;
        int clear = tuning.greatMaxHeight() + CAP_LAYERS;
        for (int dx = 0; dx < THICKNESS; dx++) {
            for (int dz = 0; dz < THICKNESS; dz++) {
                BlockPos base = origin.offset(dx, 0, dz);
                if (!level.getBlockState(base.below()).isFaceSturdy(level, base.below(), Direction.UP)) {
                    return false;
                }
                int air = 0;
                while (air < clear && level.getBlockState(base.above(air)).isAir()) {
                    air++;
                }
                clear = Math.min(clear, air);
            }
        }
        int height = Math.min(
                tuning.greatMinHeight() + random.nextInt(tuning.greatMaxHeight() - tuning.greatMinHeight() + 1),
                clear - CAP_LAYERS);
        if (height < tuning.greatMinHeight()) {
            return false;
        }
        BlockState trunk = TRUNK.defaultBlockState();
        BlockState cap = CAP.defaultBlockState();
        for (int dx = 0; dx < THICKNESS; dx++) {
            for (int dz = 0; dz < THICKNESS; dz++) {
                for (int y = 0; y < height; y++) {
                    level.setBlock(origin.offset(dx, y, dz), trunk, Block.UPDATE_CLIENTS);
                }
                level.setBlock(origin.offset(dx, height + 1, dz), cap, Block.UPDATE_CLIENTS);
            }
        }
        for (int dx = -CAP_REACH; dx < THICKNESS + CAP_REACH; dx++) {
            for (int dz = -CAP_REACH; dz < THICKNESS + CAP_REACH; dz++) {
                boolean cornerX = dx < 0 || dx >= THICKNESS;
                boolean cornerZ = dz < 0 || dz >= THICKNESS;
                BlockPos pos = origin.offset(dx, height, dz);
                // The rim's outer corners are left out; the rim never replaces a block that is not air.
                if (!(cornerX && cornerZ) && level.getBlockState(pos).isAir()) {
                    level.setBlock(pos, cap, Block.UPDATE_CLIENTS);
                }
            }
        }
        return true;
    }
}
