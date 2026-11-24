package io.github.pkeppeler.deepcharter.layer.gen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.feature.Feature;

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
 * <p>Like the zone fill it runs once per chunk, placed in each zone biome of the layer with no placement modifiers. It reads the chunk's
 * sections and skips those that hold no rock; inside a section with no open cell, and none within {@code depth} rows above or below, only
 * the columns at the chunk's sides can be lined, by an open cell next door.
 *
 * @param layer  the layer whose band it lines, counted from 1
 * @param rock   the rock it lines
 * @param lining the block a lined rock becomes
 * @param depth  how many blocks deep the lining goes from an open face, from 1
 * @param seals  blocks besides the rock that the lining replaces where they face a cavern, and that become rock one block further in
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
        int low = band.minY();
        int high = band.minY() + band.height() - 1;
        BlockState lined = lining.defaultBlockState();
        int sealReach = depth + SEAL_MARGIN;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        boolean placed = false;
        for (int index = chunk.getSectionIndex(low); index <= chunk.getSectionIndex(high); index++) {
            LevelChunkSection section = chunk.getSection(index);
            if (section.hasOnlyAir() || !section.maybeHas(state -> state.is(rock) || seals(state))) {
                continue;
            }
            // A lining reads at most MAX_DEPTH + SEAL_MARGIN blocks out (a seal, further in), less than a section, so
            // it
            // reaches no further than the sections next to its own.
            boolean sidesOnly =
                    !mayBeOpen(chunk, index - 1) && !mayBeOpen(chunk, index) && !mayBeOpen(chunk, index + 1);
            int sectionY = chunk.getSectionYFromSectionIndex(index) << 4;
            for (int y = Math.max(low, sectionY); y <= Math.min(high, sectionY + 15); y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (sidesOnly
                                && x >= sealReach
                                && x <= 15 - sealReach
                                && z >= sealReach
                                && z <= 15 - sealReach) {
                            continue;
                        }
                        BlockState state = section.getBlockState(x, y & 15, z);
                        boolean seal = seals(state);
                        if (!seal && !state.is(rock)) {
                            continue;
                        }
                        pos.set(firstX + x, y, firstZ + z);
                        if (nearOpen(level, chunk, pos, probe, depth)) {
                            chunk.setBlockState(pos, lined, Block.UPDATE_CLIENTS);
                            placed = true;
                        } else if (seal && nearOpen(level, chunk, pos, probe, sealReach)) {
                            chunk.setBlockState(pos, rock.defaultBlockState(), Block.UPDATE_CLIENTS);
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

    /** Whether an open cell lies within {@code reach} blocks of {@code pos} straight along an axis. */
    private static boolean nearOpen(
            WorldGenLevel level, ChunkAccess chunk, BlockPos pos, BlockPos.MutableBlockPos probe, int reach) {
        for (Direction direction : Direction.values()) {
            for (int step = 1; step <= reach; step++) {
                probe.setWithOffset(
                        pos, direction.getStepX() * step, direction.getStepY() * step, direction.getStepZ() * step);
                if (isOpen(stateAt(level, chunk, probe))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The block at {@code pos}: from the chunk's own sections when it is in the chunk, through the region next door. */
    private static BlockState stateAt(WorldGenLevel level, ChunkAccess chunk, BlockPos pos) {
        if (pos.getX() >> 4 == chunk.getPos().x()
                && pos.getZ() >> 4 == chunk.getPos().z()) {
            return chunk.getBlockState(pos);
        }
        return level.getBlockState(pos);
    }

    /** Whether section {@code index} of {@code chunk} may hold an open cell; a section outside the chunk may. */
    private static boolean mayBeOpen(ChunkAccess chunk, int index) {
        if (index < 0 || index >= chunk.getSectionsCount()) {
            return true;
        }
        return chunk.getSection(index).maybeHas(AirFaceLiningFeature::isOpen);
    }
}
