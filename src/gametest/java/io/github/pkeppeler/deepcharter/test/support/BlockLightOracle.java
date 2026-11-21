package io.github.pkeppeler.deepcharter.test.support;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The block light a level should hold in a box, worked out from the blocks alone: a flood fill from every emitter, losing one level
 * a step or the block's own light dampening where that is more (vanilla's rule, from {@code BlockState.getLightEmission} and
 * {@code getLightDampening}). It never asks the light engine what it holds, so it judges the engine's stored light: a lamp whose
 * spill the engine lost shows as a cell the fill lights and the engine does not. It ignores the occlusion shape of a partial block, so
 * a box with stairs or slabs in the way of the light is outside what it can judge. Light spreads 15 blocks at most, so the box must have
 * every chunk within 15 blocks of it loaded.
 */
public final class BlockLightOracle {
    private static final int REACH = 15;

    private BlockLightOracle() {}

    /** One cell where the stored light is not the light the blocks give. */
    public record Mismatch(BlockPos pos, int stored, int expected) {
        @Override
        public String toString() {
            return pos.toShortString() + " stored " + stored + " expected " + expected;
        }
    }

    /** The light the blocks give in a box, for the cells of the box only. */
    public static final class Field {
        private final BoundingBox box;
        private final int[] light;

        private Field(BoundingBox box, int[] light) {
            this.box = box;
            this.light = light;
        }

        public int at(BlockPos pos) {
            return light[index(box, pos.getX(), pos.getY(), pos.getZ())];
        }

        public BoundingBox box() {
            return box;
        }
    }

    private static int index(BoundingBox box, int x, int y, int z) {
        return ((x - box.minX()) * box.getYSpan() + (y - box.minY())) * box.getZSpan() + (z - box.minZ());
    }

    /** The light {@code box} should hold, from the blocks of the box and the {@value #REACH} blocks round it, counting only emitters {@code counts} accepts. */
    public static Field expected(ServerLevel level, BoundingBox box, Predicate<BlockPos> counts) {
        BoundingBox scan = new BoundingBox(
                box.minX() - REACH,
                Math.max(
                        level.getMinY(),
                        box.minY() - REACH), // level-height: clamps light reach to the level's own rows
                box.minZ() - REACH,
                box.maxX() + REACH,
                Math.min(
                        level.getMaxY(),
                        box.maxY() + REACH), // level-height: clamps light reach to the level's own rows
                box.maxZ() + REACH);
        requireLoaded(level, scan);
        int cells = scan.getXSpan() * scan.getYSpan() * scan.getZSpan();
        BlockState[] states = new BlockState[cells];
        int[] light = new int[cells];
        List<List<Integer>> byLevel = new ArrayList<>();
        for (int i = 0; i <= REACH; i++) {
            byLevel.add(new ArrayList<>());
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = scan.minX(); x <= scan.maxX(); x++) {
            for (int y = scan.minY(); y <= scan.maxY(); y++) {
                for (int z = scan.minZ(); z <= scan.maxZ(); z++) {
                    BlockState state = level.getBlockState(pos.set(x, y, z));
                    int i = index(scan, x, y, z);
                    states[i] = state;
                    int emission = state.getLightEmission();
                    if (emission > 0 && counts.test(pos)) {
                        light[i] = emission;
                        byLevel.get(emission).add(i);
                    }
                }
            }
        }
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int value = REACH; value >= 1; value--) {
            List<Integer> queue = byLevel.get(value);
            for (int at = 0; at < queue.size(); at++) {
                int i = queue.get(at);
                if (light[i] != value) {
                    continue; // raised since it was queued
                }
                int z = i % scan.getZSpan() + scan.minZ();
                int y = i / scan.getZSpan() % scan.getYSpan() + scan.minY();
                int x = i / scan.getZSpan() / scan.getYSpan() + scan.minX();
                for (int[] step : steps) {
                    int nx = x + step[0];
                    int ny = y + step[1];
                    int nz = z + step[2];
                    if (!scan.isInside(nx, ny, nz)) {
                        continue;
                    }
                    int n = index(scan, nx, ny, nz);
                    int next = value - Math.max(1, states[n].getLightDampening());
                    if (next > light[n]) {
                        light[n] = next;
                        byLevel.get(next).add(n);
                    }
                }
            }
        }
        int[] inside = new int[box.getXSpan() * box.getYSpan() * box.getZSpan()];
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    inside[index(box, x, y, z)] = light[index(scan, x, y, z)];
                }
            }
        }
        return new Field(box, inside);
    }

    /** The light every emitter of the loaded blocks gives in {@code box}. */
    public static Field expected(ServerLevel level, BoundingBox box) {
        return expected(level, box, pos -> true);
    }

    /** The cells of the field where the level's stored block light differs, in x, y, z order. */
    public static List<Mismatch> mismatches(ServerLevel level, Field field) {
        List<Mismatch> found = new ArrayList<>();
        BoundingBox box = field.box();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    pos.set(x, y, z);
                    int stored = level.getLightEngine()
                            .getLayerListener(LightLayer.BLOCK)
                            .getLightValue(pos);
                    int expected = field.at(pos);
                    if (stored != expected) {
                        found.add(new Mismatch(pos.immutable(), stored, expected));
                    }
                }
            }
        }
        return found;
    }

    private static void requireLoaded(ServerLevel level, BoundingBox scan) {
        List<ChunkPos> missing = new ArrayList<>();
        ChunkPos.rangeClosed(
                        new ChunkPos(scan.minX() >> 4, scan.minZ() >> 4),
                        new ChunkPos(scan.maxX() >> 4, scan.maxZ() >> 4))
                .filter(chunk -> level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null)
                .forEach(missing::add);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("the oracle reads blocks, and these chunks are not loaded: " + missing);
        }
    }
}
