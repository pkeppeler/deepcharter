package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.colony.ColonyBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceRegistry;
import java.util.OptionalInt;
import java.util.SplittableRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.loot.LootTable;
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

    private final StructureSite site;
    private final ServerLevel level;
    private final LevelChunk chunk;
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
    private @Nullable BlockPos inChunk(int u, int y, int v) {
        BlockPos pos = column(u, y, v);
        return chunkPos.equals(ChunkPos.containing(pos)) ? pos : null;
    }

    /** A barrel that fills from {@code table} when it is first opened, the same for the same site every time. */
    void lootBarrel(int u, int y, int v, ResourceKey<LootTable> table) {
        set(u, y, v, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        BlockPos pos = inChunk(u, y, v);
        if (pos != null && chunk.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity barrel) {
            barrel.setLootTable(table, site.seed());
        }
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
     * A Company horn that always hangs from a sturdy block: a chain from the first solid block above it, or with none in reach a
     * beam over it on a post. The support lies in the horn's own chunk, which is complete when drawn (a neighbour may not be).
     *
     * @param floorY the height of the floor of the hollow
     */
    void companyHorn(int u, int y, int v, int floorY) {
        OptionalInt ceiling = ceilingAbove(u, y, v);
        if (ceiling.isPresent()) {
            for (int cy = y + 1; cy < ceiling.getAsInt(); cy++) {
                set(u, cy, v, Blocks.IRON_CHAIN.defaultBlockState());
            }
        } else {
            BlockState iron = Blocks.IRON_BLOCK.defaultBlockState();
            int[] step = postStep(u, v);
            box(u + step[0], floorY, v + step[1], u + step[0], y + 1, v + step[1], iron);
            set(u, y + 1, v, iron);
        }
        set(u, y, v, AmbienceRegistry.COMPANY_HORN.defaultBlockState());
    }

    /** A step to a neighbouring column in the same chunk as {@code (u, v)}, the same from every chunk, so only the horn's chunk draws the post. */
    private int[] postStep(int u, int v) {
        BlockPos column = column(u, 0, v);
        for (int[] step : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            BlockPos next = column(u + step[0], 0, v + step[1]);
            if (ChunkPos.containing(next).equals(ChunkPos.containing(column))) {
                return step;
            }
        }
        throw new IllegalStateException("every neighbour of " + column + " is in another chunk");
    }

    /** The height of the first sturdy-underside block above {@code y} within {@link StructureKind#HORN_REACH}; empty if none, or the column is in another chunk. */
    private OptionalInt ceilingAbove(int u, int y, int v) {
        for (int up = 1; up <= StructureKind.HORN_REACH; up++) {
            BlockPos pos = inChunk(u, y + up, v);
            if (pos == null || pos.getY() > band.maxY() || chunk.isOutsideBuildHeight(pos)) {
                return OptionalInt.empty();
            }
            BlockState state = chunk.getBlockState(pos);
            if (!RoomSeal.needsSealing(state) && state.isFaceSturdy(chunk, pos, Direction.DOWN)) {
                return OptionalInt.of(y + up);
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

    /** The world direction of a direction given in the structure's own axes: east is {@code +u}, south is {@code +v}. */
    Direction facing(Direction local) {
        if (!site.alongZ()) {
            return local;
        }
        return switch (local) {
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.EAST;
            case WEST -> Direction.NORTH;
            case NORTH -> Direction.WEST;
            case UP, DOWN -> local;
        };
    }
}
