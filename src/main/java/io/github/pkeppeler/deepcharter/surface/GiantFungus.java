package io.github.pkeppeler.deepcharter.surface;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.surface.GiantLayout.Fit;
import io.github.pkeppeler.deepcharter.surface.GreatStalkLayout.Cell;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Grows a giant fungus (#1202) from its {@link GiantLayout}, fitted into a chunk: every cell it needs must be open
 * (air, or the small growth a giant grows over, an ordinary stalk or a glowcap), and the foot of each stem or spire
 * must stand on solid ground, or nothing grows. A cell it can do without (the flare, the glowcaps) is left out where
 * there is no room for it. It never writes outside the chunk it was fitted to (ADR 0088), and never over another giant.
 */
public final class GiantFungus {
    private static final Block FUNGAL_STALK =
            BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("deepcharter", "fungal_stalk"));

    private GiantFungus() {}

    /** Whether a giant may grow into a cell that holds {@code state}: air, an ordinary stalk or a glowcap. */
    public static boolean open(BlockState state) {
        return state.isAir() || state.is(FUNGAL_STALK) || state.is(LayerBlocks.GLOWCAP);
    }

    /** The block state that draws {@code piece}. */
    public static BlockState state(GiantPiece piece) {
        return switch (piece) {
            case GiantPiece.Stem stem ->
                GiantFungusBlocks.PARASOL_STEM
                        .defaultBlockState()
                        .setValue(GiantFungusBlocks.PIECE, stem.piece())
                        .setValue(GiantFungusBlocks.FACING, stem.facing())
                        .setValue(GiantFungusBlocks.ARM, stem.arm());
            case GiantPiece.Ring ignored -> GiantFungusBlocks.PARASOL_RING.defaultBlockState();
            case GiantPiece.Gills gills ->
                GiantFungusBlocks.PARASOL_GILLS
                        .defaultBlockState()
                        .setValue(GiantFungusBlocks.FACING, gills.facing())
                        .setValue(GiantFungusBlocks.DIAGONAL, gills.diagonal())
                        .setValue(GiantFungusBlocks.LIT, gills.lit());
            case GiantPiece.Cap cap -> capState(cap);
            case GiantPiece.Rim rim ->
                GiantFungusBlocks.PARASOL_RIM
                        .defaultBlockState()
                        .setValue(GiantFungusBlocks.FACING, rim.facing())
                        .setValue(GiantFungusBlocks.CORNER, rim.corner())
                        .setValue(GiantFungusBlocks.RIM_LEVEL, rim.level() / 4)
                        .setValue(GiantFungusBlocks.LIP, rim.lip());
            case GiantPiece.Flare flare ->
                GiantFungusBlocks.FUNGAL_FLARE
                        .defaultBlockState()
                        .setValue(GiantFungusBlocks.FACING, flare.facing())
                        .setValue(GiantFungusBlocks.CORNER, flare.corner())
                        .setValue(GiantFungusBlocks.TALL, flare.tall());
            case GiantPiece.Spire spire ->
                GiantFungusBlocks.SPIRE
                        .defaultBlockState()
                        .setValue(GiantFungusBlocks.GIRTH, spire.girth())
                        .setValue(GiantFungusBlocks.PIECE, spire.piece())
                        .setValue(GiantFungusBlocks.FACING, spire.facing())
                        .setValue(GiantFungusBlocks.TAPER, spire.taper());
            case GiantPiece.Tip tip ->
                GiantFungusBlocks.SPIRE_TIP
                        .defaultBlockState()
                        .setValue(GiantFungusBlocks.PIECE, tip.piece())
                        .setValue(GiantFungusBlocks.FACING, tip.facing())
                        .setValue(GiantFungusBlocks.NEEDLE, tip.needle())
                        .setValue(GiantFungusBlocks.LIT, tip.lit());
            case GiantPiece.SpireBase ignored -> GiantFungusBlocks.SPIRE_BASE.defaultBlockState();
            case GiantPiece.Glowcap ignored -> LayerBlocks.GLOWCAP.defaultBlockState();
        };
    }

    /**
     * A cap cell's state: the models are drawn rising east ({@code rise}) and south ({@code side_rise}), and turned a
     * quarter at a time, so the rise is turned into the quarter where both are not negative.
     */
    private static BlockState capState(GiantPiece.Cap cap) {
        int x = cap.riseX();
        int z = cap.riseZ();
        Direction facing;
        int rise;
        int side;
        if ((x > 0 && z >= 0) || (x == 0 && z == 0)) {
            facing = Direction.EAST;
            rise = x;
            side = z;
        } else if (z > 0 && x <= 0) {
            facing = Direction.SOUTH;
            rise = z;
            side = -x;
        } else if (x < 0 && z <= 0) {
            facing = Direction.WEST;
            rise = -x;
            side = -z;
        } else {
            facing = Direction.NORTH;
            rise = -z;
            side = x;
        }
        return GiantFungusBlocks.PARASOL_CAP
                .defaultBlockState()
                .setValue(GiantFungusBlocks.LEVEL, cap.level() / 4)
                .setValue(GiantFungusBlocks.RISE, rise / 4)
                .setValue(GiantFungusBlocks.SIDE_RISE, side / 4)
                .setValue(GiantFungusBlocks.FACING, facing)
                .setValue(GiantFungusBlocks.UMBO, cap.umbo());
    }

    /** Where {@code cell} of a fitted layout is, for a chunk and the floor row its base stands on. */
    public static BlockPos at(ChunkPos chunk, Fit fit, int floorY, Cell cell) {
        return new BlockPos(
                chunk.getMinBlockX() + fit.localX(cell), floorY + cell.y(), chunk.getMinBlockZ() + fit.localZ(cell));
    }

    /**
     * Grows {@code fit} in {@code chunk} with its base on row {@code floorY} (the first open row over the floor), if
     * every cell it needs is open and each foot stands on solid ground. Returns whether it grew.
     */
    public static boolean place(LevelAccessor level, ChunkPos chunk, Fit fit, int floorY) {
        Map<Cell, GiantPiece> pieces = fit.layout().pieces();
        List<Map.Entry<Cell, GiantPiece>> kept = new ArrayList<>();
        for (Map.Entry<Cell, GiantPiece> entry : pieces.entrySet()) {
            Cell cell = entry.getKey();
            BlockPos pos = at(chunk, fit, floorY, cell);
            boolean optional = fit.layout().optional().contains(cell);
            boolean room = ChunkPos.containing(pos).equals(chunk) && open(level.getBlockState(pos));
            if (room && (cell.y() == 0 || optional)) {
                room = grounded(level, pos, entry.getValue(), optional);
            }
            if (room) {
                kept.add(entry);
            } else if (!optional) {
                return false;
            }
        }
        for (Map.Entry<Cell, GiantPiece> entry : kept) {
            BlockPos pos = at(chunk, fit, floorY, entry.getKey());
            clearStalkAbove(level, pos);
            level.setBlock(pos, state(entry.getValue()), Block.UPDATE_CLIENTS);
        }
        return true;
    }

    /**
     * Whether a cell on the floor can hold its piece: solid ground under a foot, a flare or a mound; a glowcap where a
     * glowcap can grow. A part above the floor hangs from the rest.
     */
    private static boolean grounded(LevelAccessor level, BlockPos pos, GiantPiece piece, boolean optional) {
        if (piece instanceof GiantPiece.Glowcap) {
            return LayerBlocks.GLOWCAP.defaultBlockState().canSurvive(level, pos);
        }
        if (!optional && !(piece instanceof GiantPiece.SpireBase || piece instanceof GiantPiece.Stem)) {
            return true;
        }
        return standsOnGround(level, pos);
    }

    /** Whether the cell under {@code pos} is solid ground: not open, and sturdy on top. */
    static boolean standsOnGround(LevelAccessor level, BlockPos pos) {
        BlockPos below = pos.below();
        return !open(level.getBlockState(below)) && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    /**
     * Takes away the ordinary stalk a giant grows through, and what stood on it, so none of it floats over the giant.
     */
    static void clearStalkAbove(LevelAccessor level, BlockPos pos) {
        if (!level.getBlockState(pos).is(FUNGAL_STALK)) {
            return;
        }
        BlockPos up = pos.above();
        while (level.getBlockState(up).is(FUNGAL_STALK)
                || level.getBlockState(up).is(LayerBlocks.GLOWCAP)) {
            level.setBlock(up, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            up = up.above();
        }
    }
}
