package io.github.pkeppeler.deepcharter.test.support;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * Throwaway for issue 185 phase 3: one chunk generator that runs vanilla's noise generator for the surface band and a
 * second noise generator (the layer content) for the rest. Each sub-generator has noise settings whose
 * {@code noise.min_y} and {@code noise.height} are its own band, so its density, surface rules and carvers only see
 * that band. The surface band is built first, so the surface rule pass of vanilla's generator walks down through air
 * below its band, and the layer generator's pass is the cheap one.
 *
 * <p>With {@code placeholder} the layer generator does not run: the sections below {@code split_y} get a
 * single-state stone palette instead (the lower bound for filling layer bands lazily). Registered as
 * {@code deepcharter-test:band_delegating}.
 */
public final class BandDelegatingGenerator extends ChunkGenerator {
	public static final MapCodec<BandDelegatingGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.biomeSource),
			BiomeSource.CODEC.fieldOf("layer_biome_source").forGetter(generator -> generator.layerBiomeSource),
			NoiseGeneratorSettings.CODEC.fieldOf("surface_settings").forGetter(generator -> generator.surfaceSettings),
			NoiseGeneratorSettings.CODEC.fieldOf("layer_settings").forGetter(generator -> generator.layerSettings),
			Codec.INT.fieldOf("split_y").forGetter(generator -> generator.splitY),
			Codec.BOOL.fieldOf("placeholder").forGetter(generator -> generator.placeholder))
			.apply(instance, BandDelegatingGenerator::new));

	private static final Strategy<BlockState> BLOCK_STATES = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);

	private final BiomeSource layerBiomeSource;
	private final Holder<NoiseGeneratorSettings> surfaceSettings;
	private final Holder<NoiseGeneratorSettings> layerSettings;
	private final int splitY;
	private final boolean placeholder;
	private final NoiseBasedChunkGenerator surface;
	private final NoiseBasedChunkGenerator layer;

	public BandDelegatingGenerator(BiomeSource biomeSource, BiomeSource layerBiomeSource, Holder<NoiseGeneratorSettings> surfaceSettings,
			Holder<NoiseGeneratorSettings> layerSettings, int splitY, boolean placeholder) {
		super(biomeSource);
		this.layerBiomeSource = layerBiomeSource;
		this.surfaceSettings = surfaceSettings;
		this.layerSettings = layerSettings;
		this.splitY = splitY;
		this.placeholder = placeholder;
		this.surface = new NoiseBasedChunkGenerator(biomeSource, surfaceSettings);
		this.layer = new NoiseBasedChunkGenerator(layerBiomeSource, layerSettings);
	}

	public static void register() {
		Registry.register(BuiltInRegistries.CHUNK_GENERATOR, Identifier.fromNamespaceAndPath("deepcharter-test", "band_delegating"), CODEC);
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	@Override
	public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState, StructureManager structureManager,
			BiomeManager biomeManager, WorldGenRegion carverBiomeRegion, Set<Holder<Biome>> possibleBiomes) {
		return surface.buildTerrain(chunk, blender, randomState, structureManager, biomeManager, carverBiomeRegion, possibleBiomes)
				.thenCompose(built -> {
					if (placeholder) {
						fillPlaceholder(built);
						return CompletableFuture.completedFuture(built);
					}
					return layer.buildTerrain(built, blender, randomState, structureManager, biomeManager, carverBiomeRegion, possibleBiomes);
				});
	}

	/** Every section below the split gets one stone state in a zero-bit palette, keeping its biomes. */
	private void fillPlaceholder(ChunkAccess chunk) {
		LevelChunkSection[] sections = chunk.getSections();
		int top = chunk.getSectionIndex(splitY - 1);
		BlockState stone = Blocks.STONE.defaultBlockState();
		for (int index = 0; index <= top; index++) {
			sections[index] = new LevelChunkSection(new PalettedContainer<>(stone, BLOCK_STATES), sections[index].getBiomes());
		}
	}

	@Override
	public int getGenDepth() {
		return surfaceSettings.value().noiseSettings().height() + layerSettings.value().noiseSettings().height();
	}

	@Override
	public int getSeaLevel() {
		return surface.getSeaLevel();
	}

	@Override
	public int getMinY() {
		return layer.getMinY();
	}

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState) {
		return surface.getBaseHeight(x, z, type, heightAccessor, randomState);
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
		return surface.getBaseColumn(x, z, heightAccessor, randomState);
	}

	@Override
	public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos feetPos, SamplerContext samplerContext) {
		surface.addDebugScreenInfo(result, randomState, feetPos, samplerContext);
	}

	@Override
	public void spawnOriginalMobs(WorldGenRegion region) {
		surface.spawnOriginalMobs(region);
	}
}
