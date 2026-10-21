package io.github.pkeppeler.deepcharter.ore;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import io.github.pkeppeler.deepcharter.layer.Zones;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.feature.Feature;
import org.jspecify.annotations.Nullable;

/**
 * Fills one zone of a layer, one stone block at a time: each block becomes an ore with the chance of its entry
 * (the first entry that the roll falls in), and a block that is not ore becomes a hazard in the same way with a
 * second roll. The chances come from the original game's rows (see the zone tables in ADR 0015), so a block is
 * judged on its own, as a tile was.
 *
 * <p>It is a placed feature of the zone's own biome with no placement modifiers, so it runs once for each chunk, at
 * the chunk's corner, and it only touches that chunk's blocks of {@code zone} (a third of the layer, by
 * {@link Zones#index}). Only the layer's own rock ({@link LayerBlocks#GROUND}) is replaced: caves, the crust and anything an earlier step
 * placed stay as they are. An ore, and Company rock, is placed in the rock it replaces ({@link OreBlock#in}, {@link CompanyRockBlock#in}).
 *
 * @param zone    0 for the top third of the layer, 2 for the bottom
 * @param ores    ore blocks and the chance of each for a block of stone
 * @param hazards hazard blocks and the chance of each for a block of stone that is not ore
 */
public record ZoneFillFeature(int zone, List<Entry<OreBlock>> ores, List<Entry<Block>> hazards) implements Feature {
    /** A block and the chance of it for one stone block. */
    public record Entry<B extends Block>(B block, double chance) {
        static <B extends Block> Codec<Entry<B>> codec(Codec<B> blocks) {
            return RecordCodecBuilder.create(instance -> instance.group(
                            blocks.fieldOf("block").forGetter(Entry::block),
                            Codec.doubleRange(0, 1).fieldOf("chance").forGetter(Entry::chance))
                    .apply(instance, Entry::new));
        }
    }

    /** An ore entry names an ore block, so the fill can always give it a host. */
    private static final Codec<Entry<OreBlock>> ORE_ENTRY = Entry.codec(BuiltInRegistries.BLOCK
            .byNameCodec()
            .flatXmap(
                    block -> block instanceof OreBlock ore
                            ? DataResult.success(ore)
                            : DataResult.error(() -> block + " is not an ore block"),
                    ore -> DataResult.<Block>success(ore)));

    private static final Codec<Entry<Block>> HAZARD_ENTRY = Entry.codec(BuiltInRegistries.BLOCK.byNameCodec());

    public static final MapCodec<ZoneFillFeature> CODEC = RecordCodecBuilder.<ZoneFillFeature>mapCodec(instance ->
                    instance.group(
                                    Codec.intRange(0, Zones.COUNT - 1)
                                            .fieldOf("zone")
                                            .forGetter(ZoneFillFeature::zone),
                                    ORE_ENTRY.listOf().fieldOf("ores").forGetter(ZoneFillFeature::ores),
                                    HAZARD_ENTRY.listOf().fieldOf("hazards").forGetter(ZoneFillFeature::hazards))
                            .apply(instance, ZoneFillFeature::new))
            .validate(feature -> within(feature.ores) && within(feature.hazards)
                    ? DataResult.success(feature)
                    : DataResult.error(() ->
                            "The chances of a zone fill's ores, and of its hazards, must each add up to at most 1"));

    private static boolean within(List<? extends Entry<?>> entries) {
        return entries.stream().mapToDouble(Entry::chance).sum() <= 1.0 + 1e-9;
    }

    @Override
    public MapCodec<ZoneFillFeature> codec() {
        return CODEC;
    }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        Band band = LayerChain.bandAt(level.getLevel(), origin.getY()).orElseThrow();
        int minY = band.minY();
        int height = band.height();
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
            if (section.hasOnlyAir() || !section.maybeHas(state -> state.is(LayerBlocks.GROUND))) {
                continue;
            }
            int sectionY = chunk.getSectionYFromSectionIndex(index) << 4;
            for (int y = Math.max(low, sectionY); y <= Math.min(high, sectionY + 15); y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState rock = section.getBlockState(x, y & 15, z);
                        if (!rock.is(LayerBlocks.GROUND)) {
                            continue;
                        }
                        BlockState state = null;
                        OreBlock ore = roll(ores, random);
                        if (ore != null) {
                            state = ore.in(rock);
                        } else {
                            Block hazard = roll(hazards, random);
                            if (hazard != null) {
                                state = hazard instanceof CompanyRockBlock company
                                        ? company.in(rock)
                                        : hazard.defaultBlockState();
                            }
                        }
                        if (state != null) {
                            chunk.setBlockState(pos.set(firstX + x, y, firstZ + z), state, Block.UPDATE_CLIENTS);
                            placed = true;
                        }
                    }
                }
            }
        }
        return placed;
    }

    /** One roll over the entries: the first whose chance the roll falls in, or null for none. */
    private static <B extends Block> @Nullable B roll(List<Entry<B>> entries, RandomSource random) {
        if (entries.isEmpty()) {
            return null;
        }
        double roll = random.nextDouble();
        double floor = 0;
        for (Entry<B> entry : entries) {
            floor += entry.chance();
            if (roll < floor) {
                return entry.block();
            }
        }
        return null;
    }
}
