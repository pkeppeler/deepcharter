package io.github.pkeppeler.deepcharter.layer.gen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.ore.OreBlock;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import org.jspecify.annotations.Nullable;

/**
 * An ore exposure on a cave wall of a layer (#1277): a seam of one ore across the face of the rock where a cave or a
 * shaft opens it, what a first-generation miner would have followed by lamplight. From the origin it looks up and down
 * for open space beside the layer's rock, and turns {@code minSize} to {@code maxSize} blocks of that rock's face into
 * the ore, in the rock it replaces ({@link OreBlock#in}). The ore is picked by weight from the table of the zone the
 * face is in, so a Deep Claim wall shows Deep Claim's ores.
 *
 * <p>It reads and writes only its own chunk (ADR 0088): a block on the chunk's side counts its neighbours outside as
 * rock, so a chunk decides the same whatever its neighbours have grown.
 *
 * @param layer   the layer, counted from 1
 * @param zones   the ores of each zone, top zone first, each with its weight
 * @param minSize the fewest blocks of a seam
 * @param maxSize the most
 */
public record CaveOreFeature(int layer, List<List<Weighted>> zones, int minSize, int maxSize) implements Feature {
    /** An ore and its weight in its zone's table. */
    public record Weighted(OreBlock ore, int weight) {
        static final Codec<Weighted> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        BuiltInRegistries.BLOCK
                                .byNameCodec()
                                .comapFlatMap(
                                        block -> block instanceof OreBlock ore
                                                ? DataResult.success(ore)
                                                : DataResult.error(() -> block + " is not an ore block"),
                                        ore -> ore)
                                .fieldOf("block")
                                .forGetter(Weighted::ore),
                        Codec.intRange(1, Integer.MAX_VALUE).fieldOf("weight").forGetter(Weighted::weight))
                .apply(instance, Weighted::new));
    }

    /** The largest seam: a face of a few blocks, not a vein through the hill. */
    private static final int MAX_SEAM = 24;

    public static final MapCodec<CaveOreFeature> CODEC = RecordCodecBuilder.<CaveOreFeature>mapCodec(instance ->
                    instance.group(
                                    Codec.intRange(1, Integer.MAX_VALUE)
                                            .fieldOf("layer")
                                            .forGetter(CaveOreFeature::layer),
                                    Weighted.CODEC
                                            .listOf(1, Integer.MAX_VALUE)
                                            .listOf(Zones.COUNT, Zones.COUNT)
                                            .fieldOf("zones")
                                            .forGetter(CaveOreFeature::zones),
                                    Codec.intRange(1, MAX_SEAM)
                                            .fieldOf("min_size")
                                            .forGetter(CaveOreFeature::minSize),
                                    Codec.intRange(1, MAX_SEAM)
                                            .fieldOf("max_size")
                                            .forGetter(CaveOreFeature::maxSize))
                            .apply(instance, CaveOreFeature::new))
            .validate(feature -> feature.minSize <= feature.maxSize
                    ? DataResult.success(feature)
                    : DataResult.error(() -> "min_size " + feature.minSize + " is over max_size " + feature.maxSize));

    /** Rows up and down from the origin that it looks for a face. */
    private static final int SEARCH = 8;

    public CaveOreFeature {
        zones = zones.stream().map(List::copyOf).toList();
    }

    public static void register() {
        Registry.register(
                BuiltInRegistries.FEATURE_TYPE, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "cave_ore"), CODEC);
    }

    @Override
    public MapCodec<CaveOreFeature> codec() {
        return CODEC;
    }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        Band band = LayerChain.layerBand(generator, level, layer, "A cave ore exposure");
        ChunkAccess chunk = level.getChunk(origin);
        ChunkPos chunkPos = chunk.getPos();
        int low = Math.max(band.minY(), chunk.getMinY());
        int high = Math.min(band.maxY(), chunk.getMinY() + chunk.getHeight() - 1);
        @Nullable BlockPos face = null;
        for (int step = 0; step <= 2 * SEARCH && face == null; step++) {
            // 0, 1, -1, 2, -2, ...: the nearest face first.
            int y = origin.getY() + (step % 2 == 0 ? -step / 2 : (step + 1) / 2);
            if (y >= low && y <= high) {
                face = faceAt(chunk, chunkPos, origin.atY(y));
            }
        }
        if (face == null) {
            return false;
        }
        int zone = Zones.index(band.minY(), band.height(), face.getY());
        OreBlock ore = pick(zones.get(zone), random);
        int size = random.nextIntBetweenInclusive(minSize, maxSize);
        List<BlockPos> seam = new ArrayList<>(List.of(face));
        List<BlockPos> placed = new ArrayList<>();
        while (!seam.isEmpty() && placed.size() < size) {
            BlockPos at = seam.remove(random.nextInt(seam.size()));
            BlockState rock = chunk.getBlockState(at);
            if (!rock.is(LayerBlocks.GROUND)) {
                continue;
            }
            chunk.setBlockState(at, ore.in(rock), Block.UPDATE_CLIENTS);
            placed.add(at);
            for (Direction direction : Direction.values()) {
                BlockPos next = at.relative(direction);
                if (inChunk(chunkPos, next, low, high)
                        && !placed.contains(next)
                        && isFace(chunk, chunkPos, next, low, high)) {
                    seam.add(next);
                }
            }
        }
        return !placed.isEmpty();
    }

    /**
     * The first block of the layer's rock beside open space in the column at {@code at}'s row, looking at the six
     * neighbours; null for none.
     */
    private static @Nullable BlockPos faceAt(ChunkAccess chunk, ChunkPos chunkPos, BlockPos at) {
        if (!chunk.getBlockState(at).isAir()) {
            return null;
        }
        for (Direction direction : Direction.values()) {
            BlockPos next = at.relative(direction);
            if (inChunk(chunkPos, next, chunk.getMinY(), chunk.getMinY() + chunk.getHeight() - 1)
                    && chunk.getBlockState(next).is(LayerBlocks.GROUND)) {
                return next;
            }
        }
        return null;
    }

    /** True for the layer's rock with open space beside it, inside the chunk. */
    private static boolean isFace(ChunkAccess chunk, ChunkPos chunkPos, BlockPos at, int low, int high) {
        if (!chunk.getBlockState(at).is(LayerBlocks.GROUND)) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            BlockPos next = at.relative(direction);
            if (inChunk(chunkPos, next, low, high) && chunk.getBlockState(next).isAir()) {
                return true;
            }
        }
        return false;
    }

    private static boolean inChunk(ChunkPos chunk, BlockPos at, int low, int high) {
        return ChunkPos.containing(at).equals(chunk) && at.getY() >= low && at.getY() <= high;
    }

    private static OreBlock pick(List<Weighted> table, RandomSource random) {
        int total = table.stream().mapToInt(Weighted::weight).sum();
        int roll = random.nextInt(total);
        for (Weighted entry : table) {
            roll -= entry.weight();
            if (roll < 0) {
                return entry.ore();
            }
        }
        throw new IllegalStateException("a roll under the total weight always lands on an entry");
    }
}
