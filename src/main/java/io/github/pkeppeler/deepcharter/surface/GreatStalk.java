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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;

/**
 * The great stalk, the rare giant of the fungal stalks: a braid of three thin trunks, 13 to 17 blocks tall, twisting round each other
 * with a bracket shelf off each ({@link GreatStalkBraid}), 64 to 79 blocks of the stalk's own {@code great_stalk} and
 * {@code great_stalk_cap}, so it falls as {@link StalkCollapse} says.
 */
public final class GreatStalk {
    private static final Block TRUNK = block("great_stalk");
    private static final Block CAP = block("great_stalk_cap");

    private GreatStalk() {}

    private static Block block(String path) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("deepcharter", path));
    }

    /**
     * Grows a great stalk with {@code origin} as the corner of its 3x3 footprint, unless the ground or the air does not allow one: every
     * trunk cell must be air, and those at the lowest level over solid ground, or nothing grows. A cap or a root with no room is left out,
     * with whatever then hangs from nothing. With {@code inChunk} a cell outside the chunk of {@code origin} has no room either, so the
     * stalk never reaches into a neighbour that may be decorated before or after it (#972). Returns whether it grew.
     */
    public static boolean place(WorldGenLevel level, RandomSource random, BlockPos origin, boolean inChunk) {
        GreatStalkLayout layout = GreatStalkBraid.layout(random::nextInt);
        Set<Cell> blocked = new HashSet<>();
        ChunkPos home = ChunkPos.containing(origin);
        for (Cell cell : layout.parts().keySet()) {
            BlockPos pos = origin.offset(cell.x(), cell.y(), cell.z());
            boolean room = (!inChunk || ChunkPos.containing(pos).equals(home))
                    && level.getBlockState(pos).isAir();
            boolean grounded =
                    cell.y() > 0 || level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
            if (!room || !grounded) {
                if (!layout.optional().contains(cell) && layout.parts().get(cell) == Part.TRUNK) {
                    return false;
                }
                blocked.add(cell);
            }
        }
        layout.without(blocked)
                .parts()
                .forEach((cell, part) -> level.setBlock(
                        origin.offset(cell.x(), cell.y(), cell.z()),
                        (part == Part.TRUNK ? TRUNK : CAP).defaultBlockState(),
                        Block.UPDATE_CLIENTS));
        return true;
    }
}
