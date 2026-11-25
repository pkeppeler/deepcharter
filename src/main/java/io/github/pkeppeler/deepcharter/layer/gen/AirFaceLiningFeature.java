package io.github.pkeppeler.deepcharter.layer.gen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import java.util.BitSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.feature.Feature;
import org.jspecify.annotations.Nullable;

/**
 * Lines the faces of a layer's rock that are open to its caverns (#504): every block of {@code rock} in the band of {@code layer} within
 * {@code depth} blocks of an open cell, straight along an axis, becomes {@code lining}. The edge cell counts: a block exactly {@code depth}
 * blocks from an open cell is lined, one more is not. Layer 3's columns line its caves this way, and the cheap dark cubes of its rock stay
 * between them, so the see-through corners of a column always show rock (the user's decision on #504).
 *
 * <p>A block of {@code seals} the zone fill left within that depth of an open cell is lined too, and one up to {@link #SEAL_MARGIN} blocks
 * further in becomes {@code rock}: the lining does not fill its cell, so it passes light, and lava behind it would light thousands of open
 * cells, the most costly thing in the band to generate (#972). In layer 3 lava stays inside the rock, a hazard for the drill and not a lamp.
 *
 * <p>Open is air or growth ({@link #OPEN}), never a block the zone fill or the noise placed, so a chunk decides the same whatever its
 * neighbours have already grown: a stalk a neighbour grew into a cave still leaves the cave open. A chunk changes only its own blocks and
 * reads its neighbours' across its sides.
 *
 * <p>Like the zone fill it runs once per chunk, placed in each zone biome of the layer with no placement modifiers. It first reads which
 * cells are open, in the chunk and as far into its neighbours as a seal reaches, a section at a time and skipping sections with nothing
 * open, then lines the rock of the chunk's sections from those cells. Reading each rock block's neighbours one at a time through the
 * level cost more than the rest of the layer's features together (#972).
 *
 * @param layer  the layer whose band it lines, counted from 1
 * @param rock   the rock it lines
 * @param lining the block a lined rock becomes
 * @param depth  how many blocks deep the lining goes from an open face, from 1
 * @param seals  blocks besides the rock that the lining replaces where they face a cavern, and that become rock up to
 *               {@link #SEAL_MARGIN} blocks further in
 */
public record AirFaceLiningFeature(int layer, Block rock, Block lining, int depth, List<Block> seals)
        implements Feature {
    /** Growth in a cave that leaves it open to the lining: what the layers' features grow into air. */
    public static final TagKey<Block> OPEN =
            TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "open_to_lining"));

    /** The deepest lining: a neighbour chunk is the furthest the feature may read. */
    private static final int MAX_DEPTH = 8;

    /**
     * How many blocks past the lining a seal still becomes rock. One row let lava light the caverns around it; two keep the light in
     * the rock (#972).
     */
    public static final int SEAL_MARGIN = 2;

    public static final MapCodec<AirFaceLiningFeature> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Codec.intRange(1, Integer.MAX_VALUE).fieldOf("layer").forGetter(AirFaceLiningFeature::layer),
                    BuiltInRegistries.BLOCK.byNameCodec().fieldOf("rock").forGetter(AirFaceLiningFeature::rock),
                    BuiltInRegistries.BLOCK.byNameCodec().fieldOf("lining").forGetter(AirFaceLiningFeature::lining),
                    Codec.intRange(1, MAX_DEPTH).fieldOf("depth").forGetter(AirFaceLiningFeature::depth),
                    BuiltInRegistries.BLOCK
                            .byNameCodec()
                            .listOf()
                            .fieldOf("seals")
                            .forGetter(AirFaceLiningFeature::seals))
            .apply(instance, AirFaceLiningFeature::new));

    public AirFaceLiningFeature {
        seals = List.copyOf(seals);
    }

    public static void register() {
        Registry.register(
                BuiltInRegistries.FEATURE_TYPE,
                Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "air_face_lining"),
                CODEC);
    }

    @Override
    public MapCodec<AirFaceLiningFeature> codec() {
        return CODEC;
    }

    /** Air, or growth that leaves a cave open. */
    public static boolean isOpen(BlockState state) {
        return state.isAir() || state.is(OPEN);
    }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        Band band = LayerChain.layerBand(generator, level, layer, "An air-face lining");
        ChunkAccess chunk = level.getChunk(origin);
        int firstX = chunk.getPos().getMinBlockX();
        int firstZ = chunk.getPos().getMinBlockZ();
        // A level cut to part of a band (the cost test's floors, ADR 0086) holds only some of its rows. Every shipped
        // level holds whole bands, so this changes nothing there.
        int low = Math.max(band.minY(), chunk.getMinY());
        int high = Math.min(band.minY() + band.height() - 1, chunk.getMinY() + chunk.getHeight() - 1);
        if (low > high) {
            return false;
        }
        BlockState lined = lining.defaultBlockState();
        int sealReach = depth + SEAL_MARGIN;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        OpenCells open = OpenCells.read(level, chunk, low, high, sealReach);
        boolean placed = false;
        for (int index = chunk.getSectionIndex(low); index <= chunk.getSectionIndex(high); index++) {
            LevelChunkSection section = chunk.getSection(index);
            if (section.hasOnlyAir() || !section.maybeHas(state -> state.is(rock) || seals(state))) {
                continue;
            }
            int sectionY = chunk.getSectionYFromSectionIndex(index) << 4;
            for (int y = Math.max(low, sectionY); y <= Math.min(high, sectionY + 15); y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y & 15, z);
                        boolean seal = seals(state);
                        if (!seal && !state.is(rock)) {
                            continue;
                        }
                        if (open.near(x, y, z, depth)) {
                            chunk.setBlockState(pos.set(firstX + x, y, firstZ + z), lined, Block.UPDATE_CLIENTS);
                            placed = true;
                        } else if (seal && open.near(x, y, z, sealReach)) {
                            chunk.setBlockState(
                                    pos.set(firstX + x, y, firstZ + z), rock.defaultBlockState(), Block.UPDATE_CLIENTS);
                            placed = true;
                        }
                    }
                }
            }
        }
        return placed;
    }

    /** Whether {@code state} is one of the blocks the lining seals. */
    private boolean seals(BlockState state) {
        for (Block seal : seals) {
            if (state.is(seal)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Which cells around a chunk's band are open, read once before the chunk is lined: the chunk's columns and {@code reach} columns of
     * its neighbours, from {@code reach} rows below the band to {@code reach} rows above it. A cell outside the level is closed: only a
     * level cut to a band's rows (the cost test's windows, ADR 0086) reads past its edge, and the campaign world it is cut from has
     * rock there, never a cavern. Lining never opens or closes a cell (neither the lining nor the rock is open), so the cells read before
     * it are the cells it would see.
     */
    private static final class OpenCells {
        private final int reach;
        private final int low;
        private final int width;
        private final int rows;
        private final BitSet open;
        private @Nullable BlockState lastState;
        private boolean lastOpen;

        private OpenCells(int reach, int low, int rows) {
            this.reach = reach;
            this.low = low;
            this.width = 16 + 2 * reach;
            this.rows = rows;
            this.open = new BitSet(width * width * rows);
        }

        static OpenCells read(WorldGenLevel level, ChunkAccess chunk, int low, int high, int reach) {
            OpenCells cells = new OpenCells(reach, low - reach, high - low + 1 + 2 * reach);
            ChunkPos home = chunk.getPos();
            for (int chunkX = home.x() - 1; chunkX <= home.x() + 1; chunkX++) {
                for (int chunkZ = home.z() - 1; chunkZ <= home.z() + 1; chunkZ++) {
                    ChunkAccess source =
                            chunkX == home.x() && chunkZ == home.z() ? chunk : level.getChunk(chunkX, chunkZ);
                    cells.readFrom(source, (chunkX - home.x()) * 16, (chunkZ - home.z()) * 16);
                }
            }
            return cells;
        }

        /** Reads the cells of {@code source}, whose first column is {@code offsetX}, {@code offsetZ} from the home chunk's. */
        private void readFrom(ChunkAccess source, int offsetX, int offsetZ) {
            int fromX = Math.max(-reach, offsetX);
            int toX = Math.min(15 + reach, offsetX + 15);
            int fromZ = Math.max(-reach, offsetZ);
            int toZ = Math.min(15 + reach, offsetZ + 15);
            if (fromX > toX || fromZ > toZ) {
                return;
            }
            int top = low + rows - 1;
            int y = low;
            while (y <= top) {
                int sectionTop = Math.min(top, ((y >> 4) << 4) + 15);
                int index = source.getSectionIndex(y);
                // Outside the level a cell is closed, so its bit stays clear.
                LevelChunkSection section =
                        index < 0 || index >= source.getSectionsCount() ? null : source.getSection(index);
                if (section != null && section.hasOnlyAir()) {
                    fill(fromX, toX, fromZ, toZ, y, sectionTop);
                } else if (section != null && section.maybeHas(AirFaceLiningFeature::isOpen)) {
                    for (int row = y; row <= sectionTop; row++) {
                        for (int x = fromX; x <= toX; x++) {
                            for (int z = fromZ; z <= toZ; z++) {
                                if (isOpenCached(section.getBlockState(x - offsetX, row & 15, z - offsetZ))) {
                                    open.set(index(x, row, z));
                                }
                            }
                        }
                    }
                }
                y = sectionTop + 1;
            }
        }

        private void fill(int fromX, int toX, int fromZ, int toZ, int fromY, int toY) {
            for (int row = fromY; row <= toY; row++) {
                for (int x = fromX; x <= toX; x++) {
                    // z is the innermost index, so a row of z is one run of bits.
                    open.set(index(x, row, fromZ), index(x, row, toZ) + 1);
                }
            }
        }

        /** {@link #isOpen}, remembering the last state that is not air: most cells read are air or the same rock. */
        private boolean isOpenCached(BlockState state) {
            if (state.isAir()) {
                return true;
            }
            if (state != lastState) {
                lastState = state;
                lastOpen = isOpen(state);
            }
            return lastOpen;
        }

        /** Whether an open cell lies within {@code distance} blocks of chunk cell {@code x}, {@code y}, {@code z} along an axis. */
        boolean near(int x, int y, int z, int distance) {
            for (int step = 1; step <= distance; step++) {
                if (open.get(index(x + step, y, z))
                        || open.get(index(x - step, y, z))
                        || open.get(index(x, y + step, z))
                        || open.get(index(x, y - step, z))
                        || open.get(index(x, y, z + step))
                        || open.get(index(x, y, z - step))) {
                    return true;
                }
            }
            return false;
        }

        private int index(int x, int y, int z) {
            return ((y - low) * width + x + reach) * width + z + reach;
        }
    }
}
