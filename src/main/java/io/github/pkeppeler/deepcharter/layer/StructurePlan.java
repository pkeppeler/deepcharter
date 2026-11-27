package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.colony.ColonyBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceRegistry;
import java.util.OptionalInt;
import java.util.SplittableRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * Draws one structure into one chunk. A structure is drawn in its own coordinates: {@code u} along its long axis, {@code v}
 * across it, {@code y} up from the floor of its hollow. Only the blocks inside the chunk are set, so every chunk that a
 * structure touches draws its own part, and the parts meet. A random choice ({@link #roll}) depends on the coordinates
 * only, never on the order of drawing, so the parts always agree.
 *
 * <p>Blocks are set without neighbour updates and without {@code onPlace}, so a rail or a candle never reaches into a
 * neighbouring chunk that is not loaded. The Conduit's casing is never overwritten. {@link #seal} runs before the carve.
 */
final class StructurePlan {
    private static final int FLAGS = Block.UPDATE_SKIP_ON_PLACE;

    final StructureSite site;
    private final ServerLevel level;
    final LevelChunk chunk;
    private final Band band;
    private final ChunkPos chunkPos;

    StructurePlan(StructureSite site, ServerLevel level, LevelChunk chunk, Band band) {
        this.site = site;
        this.level = level;
        this.chunk = chunk;
        this.band = band;
        this.chunkPos = chunk.getPos();
    }

    /**
     * Replaces every fluid and gas pocket in the structure's bounds and the one-block shell round them (the part in
     * this chunk) with stone, before the carve: the walls, roof and floor are rock that the zone fill (ADR 0015) may
     * have left lava or gas in, and lava has no fluid tick until a neighbour changes. The neighbour chunk seals its own
     * part of the shell. The flags wake nothing, so no lava beside the shell is told that its neighbour changed. See
     * {@link RoomSeal}. A row is sealed with the layer rock found in the same row of the shell, so in layer 1's strata
     * (#1277) a seal is the rock of the wall round it; a row with none takes the rock {@link LayerBlocks#layerGroundOf}
     * names there.
     */
    void seal() {
        BoundingBox bounds = site.bounds();
        int minX = Math.max(bounds.minX() - 1, chunkPos.getMinBlockX());
        int maxX = Math.min(bounds.maxX() + 1, chunkPos.getMaxBlockX());
        int minZ = Math.max(bounds.minZ() - 1, chunkPos.getMinBlockZ());
        int maxZ = Math.min(bounds.maxZ() + 1, chunkPos.getMaxBlockZ());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = Math.max(bounds.minY() - 1, band.minY()); y <= Math.min(bounds.maxY() + 1, band.maxY()); y++) {
            @Nullable BlockState ground = null;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    pos.set(x, y, z);
                    if (!RoomSeal.needsSealing(chunk.getBlockState(pos))) {
                        continue;
                    }
                    if (ground == null) {
                        int row = y;
                        ground = RoomSeal.rockOfRow(chunk, y, minX, maxX, minZ, maxZ)
                                .orElseGet(() -> LayerBlocks.layerGroundOf(level, row)
                                        .orElseGet(() -> LayerBlocks.layerGround(band.layer()))
                                        .defaultBlockState());
                    }
                    chunk.setBlockState(pos, ground, FLAGS);
                    level.getLightEngine().checkBlock(pos);
                }
            }
        }
    }

    /** The world position of a place in the structure, in any chunk. */
    private BlockPos column(int u, int y, int v) {
        return site.origin().offset(site.alongZ() ? v : u, y, site.alongZ() ? u : v);
    }

    /** The world position of a place in the structure, or null where it lies in another chunk. */
    @Nullable
    BlockPos inChunk(int u, int y, int v) {
        BlockPos pos = column(u, y, v);
        return chunkPos.equals(ChunkPos.containing(pos)) ? pos : null;
    }

    void set(int u, int y, int v, BlockState state) {
        BlockPos pos = inChunk(u, y, v);
        if (pos == null) {
            return;
        }
        BlockState old = chunk.getBlockState(pos);
        if (old.is(ColonyBlocks.CONDUIT) || old.equals(state)) {
            return;
        }
        chunk.setBlockState(pos, state, FLAGS);
        // The chunk was lit when it generated, so a change to the light is told to the light engine.
        level.getLightEngine().checkBlock(pos);
    }

    void box(int u1, int y1, int v1, int u2, int y2, int v2, BlockState state) {
        for (int u = Math.min(u1, u2); u <= Math.max(u1, u2); u++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int v = Math.min(v1, v2); v <= Math.max(v1, v2); v++) {
                    set(u, y, v, state);
                }
            }
        }
    }

    void air(int u1, int y1, int v1, int u2, int y2, int v2) {
        box(u1, y1, v1, u2, y2, v2, Blocks.AIR.defaultBlockState());
    }

    /**
     * A Company horn over the middle of the hall, {@link StructureKind#HORN_HEIGHT} above the floor, that always hangs
     * from a sturdy block: a chain from the first solid block above it, or with none in reach a beam over it on a post
     * from the floor. The support lies in the horn's own chunk, which is complete when drawn (a neighbour may not be).
     */
    void companyHorn() {
        int y = StructureKind.HORN_HEIGHT;
        OptionalInt ceiling = ceilingAbove();
        if (ceiling.isPresent()) {
            for (int cy = y + 1; cy < ceiling.getAsInt(); cy++) {
                set(0, cy, 0, Blocks.IRON_CHAIN.defaultBlockState());
            }
        } else {
            BlockState iron = Blocks.IRON_BLOCK.defaultBlockState();
            int[] step = postStep();
            box(step[0], 0, step[1], step[0], y + 1, step[1], iron);
            set(0, y + 1, 0, iron);
        }
        set(0, y, 0, AmbienceRegistry.COMPANY_HORN.defaultBlockState());
    }

    /** A step to a neighbouring column in the horn's chunk, the same from every chunk, so only it draws the post. */
    private int[] postStep() {
        BlockPos column = column(0, 0, 0);
        for (int[] step : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            BlockPos next = column(step[0], 0, step[1]);
            if (ChunkPos.containing(next).equals(ChunkPos.containing(column))) {
                return step;
            }
        }
        throw new IllegalStateException("every neighbour of " + column + " is in another chunk");
    }

    /**
     * The height of the first sturdy-underside block above the horn within {@link StructureKind#HORN_REACH}; empty if
     * none, or the column is in another chunk.
     */
    private OptionalInt ceilingAbove() {
        for (int up = 1; up <= StructureKind.HORN_REACH; up++) {
            BlockPos pos = inChunk(0, StructureKind.HORN_HEIGHT + up, 0);
            if (pos == null || pos.getY() > band.maxY() || chunk.isOutsideBuildHeight(pos)) {
                return OptionalInt.empty();
            }
            BlockState state = chunk.getBlockState(pos);
            if (!RoomSeal.needsSealing(state) && state.isFaceSturdy(chunk, pos, Direction.DOWN)) {
                return OptionalInt.of(StructureKind.HORN_HEIGHT + up);
            }
        }
        return OptionalInt.empty();
    }

    /** True with the given chance, and the same answer for the same place in the same structure every time. */
    boolean roll(int u, int y, int v, double chance) {
        return new SplittableRandom(site.seed() ^ u * 73856093L ^ y * 19349663L ^ v * 83492791L).nextDouble() < chance;
    }

    /** A whole number from 0 up to {@code bound}, the same for the same {@code salt} in the same structure every time. */
    int pick(int salt, int bound) {
        return pick(site, salt, bound);
    }

    /** {@link #pick(int, int)} for a site that is not being drawn. */
    static int pick(StructureSite site, int salt, int bound) {
        return new SplittableRandom(site.seed() ^ salt * 0x9E3779B97F4A7C15L).nextInt(bound);
    }

    StructureSite site() {
        return site;
    }

    /** A rail that runs along the structure's long axis. */
    BlockState railAlongU() {
        return Blocks.RAIL
                .defaultBlockState()
                .setValue(RailBlock.SHAPE, site.alongZ() ? RailShape.NORTH_SOUTH : RailShape.EAST_WEST);
    }

    /**
     * The world direction of the structure's own east, {@code +u}: east, or south for a structure that runs along z.
     */
    Direction east() {
        return site.alongZ() ? Direction.SOUTH : Direction.EAST;
    }
}
