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

/**
 * Throwaway for issue 185 phase 3: like {@link SurfaceZonesBiomeSource}, but it hands the surface source only the
 * band at and above {@code split_y} when it builds a chunk's resolver. The multi-noise source samples its climate
 * functions over the whole volume it is given, so phase 2's source (which sampled point by point) was cheaper in
 * volume but slow per call. Registered as {@code deepcharter-test:banded}.
 */
public final class BandedBiomeSource extends BiomeSource {
	public static final MapCodec<BandedBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.INT.fieldOf("split_y").forGetter(source -> source.splitY),
			BiomeSource.CODEC.fieldOf("surface").forGetter(source -> source.surface),
			BiomeSource.CODEC.fieldOf("deep").forGetter(source -> source.deep))
			.apply(instance, BandedBiomeSource::new));

	private final int splitY;
	private final BiomeSource surface;
	private final BiomeSource deep;

	public BandedBiomeSource(int splitY, BiomeSource surface, BiomeSource deep) {
		this.splitY = splitY;
		this.surface = surface;
		this.deep = deep;
	}

	public static void register() {
		Registry.register(BuiltInRegistries.BIOME_SOURCE, Identifier.fromNamespaceAndPath("deepcharter-test", "banded"), CODEC);
	}

	@Override
	protected MapCodec<? extends BiomeSource> codec() {
		return CODEC;
	}

	@Override
	protected Stream<Holder<Biome>> collectPossibleBiomes() {
		return Stream.concat(surface.possibleBiomes().stream(), deep.possibleBiomes().stream());
	}

	@Override
	public BiomeResolver createResolver(Climate.Sampler sampler) {
		BiomeResolver surfaceResolver = surface.createResolver(sampler);
		BiomeResolver deepResolver = deep.createResolver(sampler);
		int splitQuart = QuartPos.fromBlock(splitY);
		return (quartX, quartY, quartZ) -> quartY >= splitQuart
				? surfaceResolver.getNoiseBiome(quartX, quartY, quartZ)
				: deepResolver.getNoiseBiome(quartX, quartY, quartZ);
	}

	@Override
	public BiomeResolver createResolverForChunk(Climate.Sampler sampler, int minQuartX, int minQuartY, int minQuartZ,
			int quartSizeX, int quartSizeY, int quartSizeZ) {
		int splitQuart = QuartPos.fromBlock(splitY);
		int surfaceMin = Math.max(minQuartY, splitQuart);
		int surfaceSize = minQuartY + quartSizeY - surfaceMin;
		BiomeResolver surfaceResolver = surfaceSize > 0
				? surface.createResolverForChunk(sampler, minQuartX, surfaceMin, minQuartZ, quartSizeX, surfaceSize, quartSizeZ)
				: null;
		BiomeResolver deepResolver = deep.createResolverForChunk(sampler, minQuartX, minQuartY, minQuartZ, quartSizeX, Math.min(quartSizeY, Math.max(splitQuart - minQuartY, 0)), quartSizeZ);
		return (quartX, quartY, quartZ) -> quartY >= splitQuart
				? surfaceResolver.getNoiseBiome(quartX, quartY, quartZ)
				: deepResolver.getNoiseBiome(quartX, quartY, quartZ);
	}
}
