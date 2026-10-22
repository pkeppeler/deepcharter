package io.github.pkeppeler.deepcharter.layer.gen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.Zones;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

/**
 * The biomes of the campaign world, one source for every band (#742, ADR 0059): the surface's own {@code multi_noise} from
 * {@code surface_min_y} up, the zone biomes of each layer by height (the rule of {@link ZoneBiomeSource}), and one empty biome for the
 * unbuilt rock below the layers. Only the surface reads noise, so a layer's zones are the same everywhere across it and nothing below the
 * surface costs a climate lookup. A biome cell is 4 blocks tall and is judged by its middle block; every band edge is on a 16-block
 * section edge, so no cell straddles two bands.
 *
 * <p>Registered as {@code deepcharter:bands}: {@code surface_min_y}, {@code surface} (a {@code multi_noise} source without its type),
 * {@code layers} (each its {@code min_y}, {@code height} and one biome per zone, top to bottom, ending at or below
 * {@code surface_min_y}) and {@code unbuilt}.
 */
public final class BandBiomeSource extends BiomeSource {
    /** One layer's span and its zone biomes, top to bottom. */
    public record Layer(int minY, int height, List<Holder<Biome>> biomes) {
        private static final Codec<Layer> CODEC = RecordCodecBuilder.<Layer>create(instance -> instance.group(
                                Codec.INT.fieldOf("min_y").forGetter(Layer::minY),
                                Codec.intRange(Zones.COUNT, Integer.MAX_VALUE)
                                        .fieldOf("height")
                                        .forGetter(Layer::height),
                                Biome.CODEC.listOf().fieldOf("biomes").forGetter(Layer::biomes))
                        .apply(instance, Layer::new))
                .validate(Layer::onePerZone);

        public Layer {
            biomes = List.copyOf(biomes);
        }

        private static DataResult<Layer> onePerZone(Layer layer) {
            return layer.biomes.size() == Zones.COUNT
                    ? DataResult.success(layer)
                    : DataResult.error(() -> "A layer has " + Zones.COUNT + " zones, so exactly that many biomes, got "
                            + layer.biomes.size());
        }

        private boolean contains(int y) {
            return y >= minY && y < minY + height;
        }
    }

    public static final MapCodec<BandBiomeSource> CODEC = RecordCodecBuilder.<BandBiomeSource>mapCodec(
                    instance -> instance.group(
                                    Codec.INT.fieldOf("surface_min_y").forGetter(source -> source.surfaceMinY),
                                    MultiNoiseBiomeSource.CODEC
                                            .codec()
                                            .fieldOf("surface")
                                            .forGetter(source -> source.surface),
                                    Layer.CODEC.listOf().fieldOf("layers").forGetter(source -> source.layers),
                                    Biome.CODEC.fieldOf("unbuilt").forGetter(source -> source.unbuilt))
                            .apply(instance, BandBiomeSource::new))
            .validate(BandBiomeSource::layersBelowTheSurface);

    private final int surfaceMinY;
    private final MultiNoiseBiomeSource surface;
    private final List<Layer> layers;
    private final Holder<Biome> unbuilt;

    public BandBiomeSource(int surfaceMinY, MultiNoiseBiomeSource surface, List<Layer> layers, Holder<Biome> unbuilt) {
        this.surfaceMinY = surfaceMinY;
        this.surface = surface;
        this.layers = List.copyOf(layers);
        this.unbuilt = unbuilt;
    }

    public static void register() {
        Registry.register(
                BuiltInRegistries.BIOME_SOURCE, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "bands"), CODEC);
    }

    private static DataResult<BandBiomeSource> layersBelowTheSurface(BandBiomeSource source) {
        return source.layers.stream().allMatch(layer -> layer.minY + layer.height <= source.surfaceMinY)
                ? DataResult.success(source)
                : DataResult.error(() -> "A layer must end at or below surface_min_y " + source.surfaceMinY);
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.of(
                        surface.possibleBiomes().stream(),
                        layers.stream().flatMap(layer -> layer.biomes.stream()),
                        Stream.of(unbuilt))
                .flatMap(stream -> stream);
    }

    @Override
    public BiomeResolver createResolver(Climate.Sampler sampler) {
        BiomeResolver surfaceResolver = surface.createResolver(sampler);
        return (quartX, quartY, quartZ) -> {
            int y = QuartPos.toBlock(quartY) + 2;
            if (y >= surfaceMinY) {
                return surfaceResolver.getNoiseBiome(quartX, quartY, quartZ);
            }
            for (Layer layer : layers) {
                if (layer.contains(y)) {
                    return layer.biomes.get(Zones.index(layer.minY, layer.height, y));
                }
            }
            return unbuilt;
        };
    }
}
