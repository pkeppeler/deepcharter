package io.github.pkeppeler.deepcharter.ore;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.layer.gen.BandBiomeSource;
import java.util.Arrays;
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
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.lighting.LightEngine;
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
 * <p>The zone is a third of {@code layer}, counted from 1. In a layer dimension the layer is the whole level. In a campaign world (its
 * generator's biome source is a {@link BandBiomeSource}) it is a band of the level, so the fill finds its rows there (#743).
 *
 * @param layer   the layer the zone is in, counted from 1
 * @param zone    0 for the top third of the layer, 2 for the bottom
 * @param ores    ore blocks and the chance of each for a block of stone
 * @param hazards hazard blocks and the chance of each for a block of stone that is not ore
 */
public record ZoneFillFeature(int layer, int zone, List<Entry<OreBlock>> ores, List<Entry<Block>> hazards)
        implements Feature {
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
                                    Codec.intRange(1, Integer.MAX_VALUE)
                                            .fieldOf("layer")
                                            .forGetter(ZoneFillFeature::layer),
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

    private static final Heightmap.Types[] HEIGHTMAP_TYPES = Heightmap.Types.values();

    private static boolean within(List<? extends Entry<?>> entries) {
        return entries.stream().mapToDouble(Entry::chance).sum() <= 1.0 + 1e-9;
    }

    @Override
    public MapCodec<ZoneFillFeature> codec() {
        return CODEC;
    }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        Band band = LayerChain.layerBand(generator, level, layer, "A zone fill");
        int minY = band.minY();
        int height = band.height();
        // The chunk itself, not the region: a block lookup through the region costs several times as much, and this
        // reads every block of a third of the layer. The chunk's own writes keep its heightmaps and light sources.
        ChunkAccess chunk = level.getChunk(origin);
        // The zone is a run of rows (a third of the layer); find its ends once.
        int low = minY;
        while (Zones.index(minY, height, low) != zone) {
            low++;
        }
        int high = low;
        while (high + 1 < minY + height && Zones.index(minY, height, high + 1) == zone) {
            high++;
        }
        // A level cut to part of a band (the cost test's floors, ADR 0086) holds only some of the zone's rows. Every
        // shipped level holds whole bands, so this changes nothing there.
        low = Math.max(low, chunk.getMinY());
        high = Math.min(high, chunk.getMinY() + chunk.getHeight() - 1);
        return fill(chunk, random, low, high, writesDirectly(chunk));
    }

    /**
     * Fills rows {@code low} to {@code high} of {@code chunk}: one roll for each block of ground, or two when the first finds no ore,
     * whether or not anything is placed, so the draws are the same whichever way the blocks are written. The rolls and the states they
     * place do not change from block to block, and the rock hardly does: a layer is mostly one state, so its ore states are worked out
     * once for each new rock. A section that holds nothing but ground is not read block by block: its blocks are only rolled for, and the
     * few that place something are read. With {@code direct}, a block whose old and new states have the same bookkeeping
     * ({@link #sameBookkeeping}) is written into its section, skipping what {@code ProtoChunk.setBlockState} would find nothing to do
     * for; any other block takes the chunk's own write.
     *
     * @return whether it placed anything
     */
    public boolean fill(ChunkAccess chunk, RandomSource random, int low, int high, boolean direct) {
        int firstX = chunk.getPos().getMinBlockX();
        int firstZ = chunk.getPos().getMinBlockZ();
        boolean placed = false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double[] oreFloors = floors(ores);
        double[] hazardFloors = floors(hazards);
        Placements placements = new Placements(ores.size(), hazards.size());
        for (int index = chunk.getSectionIndex(low); index <= chunk.getSectionIndex(high); index++) {
            LevelChunkSection section = chunk.getSection(index);
            if (section.hasOnlyAir() || !section.maybeHas(state -> state.is(LayerBlocks.GROUND))) {
                continue;
            }
            boolean onlyGround = !section.maybeHas(state -> !state.is(LayerBlocks.GROUND));
            int sectionY = chunk.getSectionYFromSectionIndex(index) << 4;
            for (int y = Math.max(low, sectionY); y <= Math.min(high, sectionY + 15); y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        @Nullable BlockState rock = null;
                        if (!onlyGround) {
                            rock = section.getBlockState(x, y & 15, z);
                            if (rock != placements.rock) {
                                placements.rockIs(rock, rock.is(LayerBlocks.GROUND));
                            }
                            if (!placements.rockIsGround) {
                                continue;
                            }
                        }
                        int ore = roll(oreFloors, random);
                        int hazard = ore >= 0 ? -1 : roll(hazardFloors, random);
                        if (ore < 0 && hazard < 0) {
                            continue;
                        }
                        if (rock == null) {
                            rock = section.getBlockState(x, y & 15, z);
                            if (rock != placements.rock) {
                                placements.rockIs(rock, true);
                            }
                        }
                        BlockState state;
                        boolean writeDirectly;
                        if (ore >= 0) {
                            state = placements.oreStates[ore];
                            if (state == null) {
                                state = ores.get(ore).block().in(rock);
                                placements.oreStates[ore] = state;
                                placements.oreDirect[ore] = direct && sameBookkeeping(rock, state);
                            }
                            writeDirectly = placements.oreDirect[ore];
                        } else {
                            state = placements.hazardStates[hazard];
                            if (state == null) {
                                Block block = hazards.get(hazard).block();
                                state = block instanceof CompanyRockBlock company
                                        ? company.in(rock)
                                        : block.defaultBlockState();
                                placements.hazardStates[hazard] = state;
                                placements.hazardDirect[hazard] = direct && sameBookkeeping(rock, state);
                            }
                            writeDirectly = placements.hazardDirect[hazard];
                        }
                        if (writeDirectly) {
                            section.setBlockState(x, y & 15, z, state, false);
                        } else {
                            chunk.setBlockState(pos.set(firstX + x, y, firstZ + z), state, Block.UPDATE_CLIENTS);
                        }
                        placed = true;
                    }
                }
            }
        }
        return placed;
    }

    /** The rock last read and the states placed over it, which are worked out once for each new rock. */
    private static final class Placements {
        final @Nullable BlockState[] oreStates;
        final @Nullable BlockState[] hazardStates;
        final boolean[] oreDirect;
        final boolean[] hazardDirect;

        @Nullable
        BlockState rock;

        boolean rockIsGround;

        Placements(int ores, int hazards) {
            oreStates = new BlockState[ores];
            hazardStates = new BlockState[hazards];
            oreDirect = new boolean[ores];
            hazardDirect = new boolean[hazards];
        }

        void rockIs(BlockState newRock, boolean isGround) {
            rock = newRock;
            rockIsGround = isGround;
            Arrays.fill(oreStates, null);
            Arrays.fill(hazardStates, null);
        }
    }

    /**
     * Whether a chunk's blocks may be written into its sections: a prototype chunk (not a live chunk's imposter) before light is
     * initialised, the zone fill's own step. Then {@code ProtoChunk.setBlockState} writes the section, and only keeps its heightmaps and,
     * after that step, its light, which {@link #sameBookkeeping} leaves unchanged. Checked against the 26.3 jar; the equality test in
     * {@code ZoneFillWritesTest} is the drift guard.
     */
    public static boolean writesDirectly(ChunkAccess chunk) {
        return chunk.getClass() == ProtoChunk.class
                && !((ProtoChunk) chunk).getPersistedStatus().isOrAfter(ChunkStatus.INITIALIZE_LIGHT);
    }

    /**
     * Whether swapping {@code old} for {@code replacement} leaves the chunk's bookkeeping alone: both solid, no block entity, no fluid, the
     * same light properties, and the same answer for every heightmap's opacity test. Each heightmap test is called, not read off a list of
     * what it tests, so a change of a type in a Minecraft update is covered.
     */
    private static boolean sameBookkeeping(BlockState old, BlockState replacement) {
        if (old.isAir()
                || replacement.isAir()
                || old.hasBlockEntity()
                || replacement.hasBlockEntity()
                || !old.getFluidState().isEmpty()
                || !replacement.getFluidState().isEmpty()
                || LightEngine.hasDifferentLightProperties(old, replacement)) {
            return false;
        }
        for (Heightmap.Types type : HEIGHTMAP_TYPES) {
            if (type.isOpaque().test(old) != type.isOpaque().test(replacement)) {
                return false;
            }
        }
        return true;
    }

    /** The running totals of the entries' chances, so that a roll finds its entry the way a sum over the list would. */
    private static double[] floors(List<? extends Entry<?>> entries) {
        double[] floors = new double[entries.size()];
        double floor = 0;
        for (int i = 0; i < floors.length; i++) {
            floor += entries.get(i).chance();
            floors[i] = floor;
        }
        return floors;
    }

    /** One roll over the running totals: the index of the first one the roll falls below, or -1 for none. No entries, no roll. */
    private static int roll(double[] floors, RandomSource random) {
        if (floors.length == 0) {
            return -1;
        }
        double roll = random.nextDouble();
        for (int i = 0; i < floors.length; i++) {
            if (roll < floors[i]) {
                return i;
            }
        }
        return -1;
    }
}
