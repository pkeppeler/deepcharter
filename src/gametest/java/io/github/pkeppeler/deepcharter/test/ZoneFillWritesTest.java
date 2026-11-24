package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.ore.OreBlock;
import io.github.pkeppeler.deepcharter.ore.ZoneFillFeature;
import io.github.pkeppeler.deepcharter.ore.ZoneFillFeature.Entry;
import java.util.EnumSet;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * The zone fill writes the blocks it places into the chunk's sections when that changes nothing a chunk write would keep (#1169, ADR 0086).
 * Two prototype chunks of the same rock are filled with the same seed, one by that path and one by the chunk's own writes: they hold the
 * same blocks and the same heightmaps, and the random source is left in the same place, so the draws are the same in order and number.
 * The hazards include a block that is no heightmap's opaque block and a fluid, which the section write must leave to the chunk.
 */
public class ZoneFillWritesTest {
    private static final int MIN_Y = 0;
    private static final int HEIGHT = 96;
    private static final long SEED = 0x1169L;

    @GameTest
    public void writingIntoTheSectionsGivesTheSameChunkAndTheSameDraws(GameTestHelper helper) {
        List<Entry<OreBlock>> ores = BuiltInRegistries.BLOCK.stream()
                .filter(OreBlock.class::isInstance)
                .map(OreBlock.class::cast)
                .limit(3)
                .map(ore -> new Entry<>(ore, 0.12))
                .toList();
        List<Entry<Block>> hazards = List.of(
                new Entry<>(HazardBlocks.COMPANY_ROCK, 0.05),
                new Entry<>(HazardBlocks.GAS_POCKET, 0.05),
                new Entry<>(Blocks.LAVA, 0.03),
                new Entry<>(Blocks.TORCH, 0.03));
        ZoneFillFeature feature = new ZoneFillFeature(1, 0, ores, hazards);
        ProtoChunk direct = rock(helper);
        ProtoChunk vanilla = rock(helper);
        expect(helper, ZoneFillFeature.writesDirectly(direct), "a fresh prototype chunk is not written to directly");
        RandomSource directRandom = new XoroshiroRandomSource(SEED);
        RandomSource vanillaRandom = new XoroshiroRandomSource(SEED);

        boolean placedDirect = feature.fill(direct, directRandom, MIN_Y, MIN_Y + HEIGHT - 1, true);
        boolean placedVanilla = feature.fill(vanilla, vanillaRandom, MIN_Y, MIN_Y + HEIGHT - 1, false);

        expect(helper, placedDirect && placedVanilla, "the fill placed nothing");
        expect(helper, directRandom.nextLong() == vanillaRandom.nextLong(), "the two fills drew differently");
        int lava = 0;
        int torches = 0;
        for (int y = MIN_Y; y < MIN_Y + HEIGHT; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState expected = vanilla.getBlockState(pos);
                    expect(
                            helper,
                            direct.getBlockState(pos).equals(expected),
                            "%s holds %s, the chunk's own writes gave %s",
                            pos,
                            direct.getBlockState(pos),
                            expected);
                    lava += expected.is(Blocks.LAVA) ? 1 : 0;
                    torches += expected.is(Blocks.TORCH) ? 1 : 0;
                }
            }
        }
        expect(
                helper,
                lava > 0 && torches > 0,
                "the fill placed %s lava and %s torches, expected some of each",
                lava,
                torches);
        for (Heightmap.Types type : Heightmap.Types.values()) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    expect(
                            helper,
                            direct.getHeight(type, x, z) == vanilla.getHeight(type, x, z),
                            "%s at %s, %s is %s, the chunk's own writes gave %s",
                            type,
                            x,
                            z,
                            direct.getHeight(type, x, z),
                            vanilla.getHeight(type, x, z));
                }
            }
        }
        helper.succeed();
    }

    /**
     * A prototype chunk of ground with air, another rock and bedrock mixed through it by a fixed pattern, so that some sections are
     * all ground and some are not. Its heightmaps are primed before the fill, as a generating chunk's are.
     */
    private static ProtoChunk rock(GameTestHelper helper) {
        PalettedContainerFactory factory =
                PalettedContainerFactory.create(helper.getLevel().registryAccess());
        ProtoChunk chunk = new ProtoChunk(
                new ChunkPos(0, 0), UpgradeData.EMPTY, LevelHeightAccessor.create(MIN_Y, HEIGHT), factory, null);
        for (int y = MIN_Y; y < MIN_Y + HEIGHT; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    BlockState state = LayerBlocks.PACKED_SHALE.defaultBlockState();
                    // Rows 32 to 47 are all ground, and the rest are mixed.
                    if (y < 32 || y > 47) {
                        int mix = (x * 7 + y * 13 + z * 5) % 23;
                        if (mix == 0) {
                            state = Blocks.AIR.defaultBlockState();
                        } else if (mix == 1) {
                            state = LayerBlocks.GREEN_SHALE.defaultBlockState();
                        } else if (mix == 2) {
                            state = Blocks.BEDROCK.defaultBlockState();
                        }
                    }
                    chunk.setBlockState(new BlockPos(x, y, z), state, 0);
                }
            }
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types.class));
        return chunk;
    }
}
