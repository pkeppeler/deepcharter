package io.github.pkeppeler.deepcharter.layer.gen;

import java.util.List;
import java.util.stream.Stream;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.Zones;

/**
 * Places a layer's zone biomes by height: the top third of the layer gets the first biome, the middle third the
 * second, the bottom third the third (see {@link Zones}). It reads no noise, so a layer's zones are the same
 * everywhere across it. A biome cell is 4 blocks tall and is judged by its middle block, so a boundary that falls
 * inside a cell (layer 2's thirds do) is off by at most two blocks.
 *
 * <p>Registered as {@code deepcharter:zones}, with {@code min_y} and {@code height} (which must equal the layer's
 * dimension type) and {@code biomes}, one per zone, top to bottom.
 */
public final class ZoneBiomeSource extends BiomeSource implements BiomeResolver {
	public static final MapCodec<ZoneBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.INT.fieldOf("min_y").forGetter(source -> source.minY),
			Codec.intRange(Zones.COUNT, Integer.MAX_VALUE).fieldOf("height").forGetter(source -> source.height),
			Biome.CODEC.listOf().validate(ZoneBiomeSource::onePerZone).fieldOf("biomes").forGetter(source -> source.biomes))
			.apply(instance, ZoneBiomeSource::new));

	private final int minY;
	private final int height;
	private final List<Holder<Biome>> biomes;

	public ZoneBiomeSource(int minY, int height, List<Holder<Biome>> biomes) {
		this.minY = minY;
		this.height = height;
		this.biomes = List.copyOf(biomes);
	}

	public int minY() {
		return minY;
	}

	public int height() {
		return height;
	}

	public static void register() {
		Registry.register(BuiltInRegistries.BIOME_SOURCE, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "zones"), CODEC);
	}

	private static DataResult<List<Holder<Biome>>> onePerZone(List<Holder<Biome>> biomes) {
		return biomes.size() == Zones.COUNT
				? DataResult.success(biomes)
				: DataResult.error(() -> "A layer has " + Zones.COUNT + " zones, so exactly that many biomes, got " + biomes.size());
	}

	@Override
	protected MapCodec<? extends BiomeSource> codec() {
		return CODEC;
	}

	@Override
	protected Stream<Holder<Biome>> collectPossibleBiomes() {
		return biomes.stream();
	}

	@Override
	public BiomeResolver createResolver(Climate.Sampler sampler) {
		return this;
	}

	@Override
	public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ) {
		int y = Mth.clamp(QuartPos.toBlock(quartY) + 2, minY, minY + height - 1);
		return biomes.get(Zones.index(minY, height, y));
	}
}
