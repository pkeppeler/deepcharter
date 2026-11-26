package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.gen.NarrowCaveFunction;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityBufferArena;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The layer 1 caves' narrow terms skip the cells they cannot open ({@link NarrowCaveFunction}, #1472) and must stay the
 * same density, float for float, as the vanilla expression they replaced, kept in the test pack as
 * {@code test/claim_caves_vanilla}. The shipped {@code claim_caves} and that copy are sampled with the campaign
 * world's own seed over a chunk of every band (a volume whose cells line up with the chunk, as the noise fill's
 * do), over volumes that start and end inside a cell, over a volume of every other block (the path that hands off to
 * vanilla) and at single points. Run red: dropping {@code SLACK} to a negative value, or swapping the order of the z
 * lerps, makes blocks differ.
 */
public class NarrowCaveFunctionTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(NarrowCaveFunctionTest.class);
    private static final String CAMPAIGN = "campaign_full";
    private static final ResourceKey<DensityFunction> SHIPPED = key("claim_caves");
    private static final ResourceKey<DensityFunction> VANILLA = key("test/claim_caves_vanilla");
    /** Chunks across the claim, far apart, so the cells hold shut and open terms alike. */
    private static final List<int[]> CHUNKS = List.of(
            new int[] {0, 0}, new int[] {37, -12}, new int[] {-81, 54}, new int[] {610, 605}, new int[] {-300, -1200});

    @GameTest
    public void theNarrowCavesAreTheirVanillaExpressionFloatForFloat(GameTestHelper helper) {
        ServerLevel level = ClientLight.serverLevel(
                helper.getLevel().getServer(),
                ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("deepcharter", CAMPAIGN)));
        DensityFunction shipped = function(level, SHIPPED);
        DensityFunction vanilla = function(level, VANILLA);
        expect(
                helper,
                shipped.toString().contains(NarrowCaveFunction.class.getSimpleName()),
                "the shipped claim_caves has no narrow_cave term, so this compares vanilla with itself: %s",
                shipped);
        RandomState random = level.getChunkSource().randomState();
        DensitySamplerSet samplers = random.samplersWithContext(SamplerContext.builder()
                .useBufferArena(DensityBufferArena.GLOBAL)
                .build());
        int[] tally = new int[2];
        for (int[] chunk : CHUNKS) {
            for (CampaignBands.Band band :
                    List.of(CampaignBands.SURFACE, CampaignBands.LAYER_1, CampaignBands.LAYER_2)) {
                compare(
                        helper,
                        samplers,
                        shipped,
                        vanilla,
                        tally,
                        new DensityVolume(16, band.height(), 16, chunk[0] * 16, band.minY(), chunk[1] * 16));
            }
            // Starts and ends inside a cell on every axis.
            compare(
                    helper,
                    samplers,
                    shipped,
                    vanilla,
                    tally,
                    new DensityVolume(
                            23, 61, 11, chunk[0] * 16 + 3, CampaignBands.LAYER_1.minY() + 5, chunk[1] * 16 - 2));
            // Every other block: not a block grid, so the sampler hands it to vanilla.
            compare(
                    helper,
                    samplers,
                    shipped,
                    vanilla,
                    tally,
                    new DensityVolume(8, 40, 8, chunk[0] * 16, CampaignBands.LAYER_1.minY(), chunk[1] * 16, 2, 2, 2));
            for (int y = CampaignBands.LAYER_1.minY();
                    y < CampaignBands.LAYER_1.minY() + CampaignBands.LAYER_1.height();
                    y += 7) {
                int x = chunk[0] * 16 + 5;
                int z = chunk[1] * 16 + 9;
                float got = samplers.sampleValue(shipped, x, y, z);
                float want = samplers.sampleValue(vanilla, x, y, z);
                expect(
                        helper,
                        Float.floatToRawIntBits(got) == Float.floatToRawIntBits(want),
                        "claim_caves at %s %s %s is %s, vanilla %s",
                        x,
                        y,
                        z,
                        got,
                        want);
            }
        }
        LOGGER.info("[narrow-cave] {} blocks compared, {} of them in a cave", tally[0], tally[1]);
        // A control: equal everywhere means nothing if the caves never open.
        expect(helper, tally[1] > 1000, "only %s of %s blocks compared are in a cave", tally[1], tally[0]);
        helper.succeed();
    }

    private static void compare(
            GameTestHelper helper,
            DensitySamplerSet samplers,
            DensityFunction shipped,
            DensityFunction vanilla,
            int[] tally,
            DensityVolume volume) {
        try (ScopedDensityBuffer got = samplers.get(shipped).sampleVolume(volume);
                ScopedDensityBuffer want = samplers.get(vanilla).sampleVolume(volume)) {
            for (int i = 0; i < volume.size(); i++) {
                expect(
                        helper,
                        Float.floatToRawIntBits(got.get(i)) == Float.floatToRawIntBits(want.get(i)),
                        "claim_caves differs from vanilla at index %s of %s: %s against %s",
                        i,
                        volume,
                        got.get(i),
                        want.get(i));
                tally[1] += want.get(i) < 0.0f ? 1 : 0;
            }
            tally[0] += volume.size();
        }
    }

    private static DensityFunction function(ServerLevel level, ResourceKey<DensityFunction> key) {
        return level.registryAccess()
                .lookupOrThrow(Registries.DENSITY_FUNCTION)
                .getOrThrow(key)
                .value();
    }

    private static ResourceKey<DensityFunction> key(String path) {
        return ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.fromNamespaceAndPath("deepcharter", path));
    }
}
