package io.github.pkeppeler.deepcharter.ore;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.feature.Feature;

import io.github.pkeppeler.deepcharter.layer.Zones;

/**
 * Fills one zone of a layer, one stone block at a time: each block becomes an ore with the chance of its entry
 * (the first entry that the roll falls in), and a block that is not ore becomes a hazard in the same way with a
 * second roll. The chances come from the original game's rows (see the zone tables in ADR 0015), so a block is
 * judged on its own, as a tile was.
 *
 * <p>It is a placed feature of the zone's own biome with no placement modifiers, so it runs once for each chunk, at
 * the chunk's corner, and it only touches that chunk's blocks of {@code zone} (a third of the layer, by
 * {@link Zones#index}). Only {@code minecraft:stone} is replaced: caves, the crust and anything an earlier step
 * placed stay as they are.
 *
 * @param zone    0 for the top third of the layer, 2 for the bottom
 * @param ores    ore blocks and the chance of each for a block of stone
 * @param hazards hazard blocks and the chance of each for a block of stone that is not ore
 */
public record ZoneFillFeature(int zone, List<Entry> ores, List<Entry> hazards) implements Feature {
	/** A block and the chance of it for one stone block. */
	public record Entry(Block block, double chance) {
		public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				BuiltInRegistries.BLOCK.byNameCodec().fieldOf("block").forGetter(Entry::block),
				Codec.doubleRange(0, 1).fieldOf("chance").forGetter(Entry::chance))
				.apply(instance, Entry::new));
	}

	public static final MapCodec<ZoneFillFeature> CODEC = RecordCodecBuilder.<ZoneFillFeature>mapCodec(instance -> instance.group(
			Codec.intRange(0, Zones.COUNT - 1).fieldOf("zone").forGetter(ZoneFillFeature::zone),
			Entry.CODEC.listOf().fieldOf("ores").forGetter(ZoneFillFeature::ores),
			Entry.CODEC.listOf().fieldOf("hazards").forGetter(ZoneFillFeature::hazards))
			.apply(instance, ZoneFillFeature::new))
			.validate(feature -> within(feature.ores) && within(feature.hazards)
					? DataResult.success(feature)
					: DataResult.error(() -> "The chances of a zone fill's ores, and of its hazards, must each add up to at most 1"));

	private static boolean within(List<Entry> entries) {
		return entries.stream().mapToDouble(Entry::chance).sum() <= 1.0 + 1e-9;
	}

	@Override
	public MapCodec<ZoneFillFeature> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
		int minY = level.getMinY();
		int height = level.getHeight();
		int firstX = origin.getX() >> 4 << 4;
		int firstZ = origin.getZ() >> 4 << 4;
		// The chunk itself, not the region: a block lookup through the region costs several times as much, and this
		// reads every block of a third of the layer. The chunk's own writes keep its heightmaps and light sources.
		ChunkAccess chunk = level.getChunk(origin);
		boolean placed = false;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		// The zone is a run of rows (a third of the layer); find its ends once.
		int low = minY;
		while (Zones.index(minY, height, low) != zone) {
			low++;
		}
		int high = low;
		while (high + 1 < minY + height && Zones.index(minY, height, high + 1) == zone) {
			high++;
		}
		for (int index = chunk.getSectionIndex(low); index <= chunk.getSectionIndex(high); index++) {
			LevelChunkSection section = chunk.getSection(index);
			if (section.hasOnlyAir() || !section.maybeHas(state -> state.is(Blocks.STONE))) {
				continue;
			}
			int sectionY = chunk.getSectionYFromSectionIndex(index) << 4;
			for (int y = Math.max(low, sectionY); y <= Math.min(high, sectionY + 15); y++) {
				for (int x = 0; x < 16; x++) {
					for (int z = 0; z < 16; z++) {
						if (!section.getBlockState(x, y & 15, z).is(Blocks.STONE)) {
							continue;
						}
						Block block = roll(ores, random);
						if (block == null) {
							block = roll(hazards, random);
						}
						if (block != null) {
							chunk.setBlockState(pos.set(firstX + x, y, firstZ + z), block.defaultBlockState(), Block.UPDATE_CLIENTS);
							placed = true;
						}
					}
				}
			}
		}
		return placed;
	}

	/** One roll over the entries: the first whose chance the roll falls in, or null for none. */
	private static Block roll(List<Entry> entries, RandomSource random) {
		if (entries.isEmpty()) {
			return null;
		}
		double roll = random.nextDouble();
		double floor = 0;
		for (Entry entry : entries) {
			floor += entry.chance();
			if (roll < floor) {
				return entry.block();
			}
		}
		return null;
	}
}
