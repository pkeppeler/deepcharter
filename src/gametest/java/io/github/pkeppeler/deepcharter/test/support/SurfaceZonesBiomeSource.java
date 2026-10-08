package io.github.pkeppeler.deepcharter.test.support;

import java.util.stream.Stream;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import io.github.pkeppeler.deepcharter.layer.gen.ZoneBiomeSource;

/**
 * Throwaway for issue 185 phase 2: the surface's biome source at and above {@code split_y}, the layer zones below it.
 * Registered as {@code deepcharter-test:surface_zones}.
 */
public final class SurfaceZonesBiomeSource extends BiomeSource {
	public static final MapCodec<SurfaceZonesBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.INT.fieldOf("split_y").forGetter(source -> source.splitY),
			BiomeSource.CODEC.fieldOf("surface").forGetter(source -> source.surface),
			ZoneBiomeSource.CODEC.codec().fieldOf("zones").forGetter(source -> source.zones))
			.apply(instance, SurfaceZonesBiomeSource::new));

	private final int splitY;
	private final BiomeSource surface;
	private final ZoneBiomeSource zones;

	public SurfaceZonesBiomeSource(int splitY, BiomeSource surface, ZoneBiomeSource zones) {
		this.splitY = splitY;
		this.surface = surface;
		this.zones = zones;
	}

	public static void register() {
		Registry.register(BuiltInRegistries.BIOME_SOURCE, Identifier.fromNamespaceAndPath("deepcharter-test", "surface_zones"), CODEC);
	}

	@Override
	protected MapCodec<? extends BiomeSource> codec() {
		return CODEC;
	}

	@Override
	protected Stream<Holder<Biome>> collectPossibleBiomes() {
		return Stream.concat(surface.possibleBiomes().stream(), zones.possibleBiomes().stream());
	}

	@Override
	public BiomeResolver createResolver(Climate.Sampler sampler) {
		BiomeResolver surfaceResolver = surface.createResolver(sampler);
		BiomeResolver zoneResolver = zones.createResolver(sampler);
		return (quartX, quartY, quartZ) -> QuartPos.toBlock(quartY) + 2 >= splitY
				? surfaceResolver.getNoiseBiome(quartX, quartY, quartZ)
				: zoneResolver.getNoiseBiome(quartX, quartY, quartZ);
	}
}
