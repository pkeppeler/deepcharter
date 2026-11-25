package io.github.pkeppeler.deepcharter.test;

import com.mojang.datafixers.util.Pair;
import io.github.pkeppeler.deepcharter.layer.FloorCrust;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.layer.gen.BandBiomeSource;
import io.github.pkeppeler.deepcharter.layer.gen.BandBiomeSource.Layer;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * {@link BandBiomeSource#createResolver} looks the bands up in a table of cells from the lowest layer to the surface (#743). This pins the
 * table's bounds, which the shipped data exercises for two configurations only: for every cell from far below the lowest layer to far
 * above the surface it must answer what the height rule gives directly (the zone biome of the layer holding the cell's middle block, else
 * the unbuilt biome, and the surface's own source from {@code surface_min_y} up). The configurations put the lowest layer and the surface at heights
 * that are not multiples of 4, leave gaps between layers, and take no layer at all.
 */
public class BandBiomeSourceTest {
    @GameTest
    public void theBandTableAnswersTheHeightRuleAtEveryCell(GameTestHelper helper) {
        HolderLookup.RegistryLookup<Biome> biomes =
                helper.getLevel().registryAccess().lookupOrThrow(Registries.BIOME);
        Holder<Biome> unbuilt = biomes.getOrThrow(Biomes.THE_VOID);
        Holder<Biome> surfaceBiome = biomes.getOrThrow(Biomes.PLAINS);
        List<Holder<Biome>> upper = List.of(
                biomes.getOrThrow(Biomes.DESERT), biomes.getOrThrow(Biomes.FOREST), biomes.getOrThrow(Biomes.TAIGA));
        List<Holder<Biome>> lower = List.of(
                biomes.getOrThrow(Biomes.SWAMP),
                biomes.getOrThrow(Biomes.BEACH),
                biomes.getOrThrow(Biomes.SNOWY_PLAINS));
        MultiNoiseBiomeSource surface = MultiNoiseBiomeSource.createFromList(
                new Climate.ParameterList<>(List.of(Pair.of(Climate.parameters(0, 0, 0, 0, 0, 0, 0), surfaceBiome))));

        // Layers top to bottom, as the shipped data lists them. Each case: surface_min_y and the layers.
        List<Case> cases = List.of(
                // Everything on cell edges, a gap between the layers.
                new Case(
                        0,
                        List.of(
                                new Layer(-400, 240, upper, FloorCrust.DRAW),
                                new Layer(-800, 300, lower, FloorCrust.DRAW))),
                // The lowest layer's minY, the gap's edges and the surface off the 4-block grid.
                new Case(
                        -51,
                        List.of(
                                new Layer(-403, 241, upper, FloorCrust.DRAW),
                                new Layer(-1001, 301, lower, FloorCrust.DRAW))),
                new Case(
                        -50,
                        List.of(
                                new Layer(-402, 242, upper, FloorCrust.DRAW),
                                new Layer(-999, 299, lower, FloorCrust.DRAW))),
                new Case(
                        -49,
                        List.of(
                                new Layer(-401, 243, upper, FloorCrust.DRAW),
                                new Layer(-998, 298, lower, FloorCrust.DRAW))),
                // A layer that ends exactly at the surface, with a short layer below it.
                new Case(
                        -37,
                        List.of(
                                new Layer(-337, 300, upper, FloorCrust.DRAW),
                                new Layer(-361, 20, lower, FloorCrust.DRAW))),
                // One layer, a surface just above it.
                new Case(-1, List.of(new Layer(-301, 297, lower, FloorCrust.DRAW))),
                // No layer: everything below the surface is unbuilt.
                new Case(-7, List.of()),
                // A surface above zero.
                new Case(21, List.of(new Layer(-83, 100, upper, FloorCrust.DRAW))));

        // The one-entry surface answers its single biome whatever the sampler reads.
        Climate.Sampler sampler = flatSampler();
        for (Case c : cases) {
            BandBiomeSource source = new BandBiomeSource(c.surfaceMinY(), surface, c.layers(), unbuilt);
            BiomeResolver table = source.createResolver(sampler);
            BiomeResolver surfaceResolver = surface.createResolver(sampler);
            int lowest = c.layers().stream().mapToInt(Layer::minY).min().orElse(c.surfaceMinY());
            int from = QuartPos.fromBlock(lowest) - 40;
            int to = QuartPos.fromBlock(c.surfaceMinY()) + 40;
            for (int quartY = from; quartY <= to; quartY++) {
                Holder<Biome> expected = direct(c, surfaceResolver, unbuilt, quartY);
                Holder<Biome> actual = table.getNoiseBiome(3, quartY, -5);
                if (expected != actual) {
                    throw helper.assertionException(Component.literal("surface_min_y " + c.surfaceMinY() + ", layers "
                            + c.layers().stream()
                                    .map(l -> l.minY() + "+" + l.height())
                                    .toList() + ": cell " + quartY + " (block " + QuartPos.toBlock(quartY)
                            + ") reads " + name(actual) + ", the height rule gives " + name(expected)));
                }
            }
        }
        helper.succeed();
    }

    /** The height rule, stated directly: the cell is judged by its middle block. */
    private static Holder<Biome> direct(Case c, BiomeResolver surface, Holder<Biome> unbuilt, int quartY) {
        int y = QuartPos.toBlock(quartY) + 2;
        if (y >= c.surfaceMinY()) {
            return surface.getNoiseBiome(3, quartY, -5);
        }
        for (Layer layer : c.layers()) {
            if (y >= layer.minY() && y < layer.minY() + layer.height()) {
                return layer.biomes().get(Zones.index(layer.minY(), layer.height(), y));
            }
        }
        return unbuilt;
    }

    /** A sampler that reads 0 everywhere: the surface source here has one entry, so it answers that whatever the climate. */
    private static Climate.Sampler flatSampler() {
        DensitySampler flat = new DensitySampler() {
            @Override
            public void sampleVolume(SamplerContext context, DensityBuffer buffer, DensityVolume volume) {
                DensitySampler.sampleVolumeNaive(context, buffer, volume, this);
            }

            @Override
            public float sampleValue(SamplerContext context, int x, int y, int z) {
                return 0f;
            }
        };
        DensitySampler.Bound bound = new DensitySampler.Bound(flat, SamplerContext.EMPTY_UNCACHED);
        return new Climate.Sampler(bound, bound, bound, bound, bound, bound);
    }

    private static String name(Holder<Biome> biome) {
        return biome.unwrapKey().orElseThrow().identifier().toString();
    }

    private record Case(int surfaceMinY, List<Layer> layers) {}
}
