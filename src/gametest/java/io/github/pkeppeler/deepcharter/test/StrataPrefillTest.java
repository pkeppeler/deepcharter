package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.gen.StrataPrefill;
import io.github.pkeppeler.deepcharter.surface.SurfaceWrites;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/**
 * The campaign's noise fill writes layer 1's strata rock in advance ({@link StrataPrefill}, #1472), and the world
 * must not show it. {@code campaign_full} is the shipped generator, which prefills; {@code campaign_sample} has
 * settings of its own (air under the last layer), so it never does. A chunk of each at the same position must hold
 * the same block at every row of layer 1 and of the bands next to it, where a guess left in place or written outside
 * its band would show, and most of layer 1's material writes must find their block already there (SurfaceWrites skips
 * those). Run red: a prefill that also writes the 16 rows under the band fails the comparison; a prefill that never
 * runs fails the count.
 */
public class StrataPrefillTest {
    private static final String FULL = "campaign_full";
    private static final String SAMPLE = "campaign_sample";
    private static final List<ChunkPos> CHUNKS =
            List.of(new ChunkPos(1_000_300, 1_000_300), new ChunkPos(-1_000_311, 1_000_297));

    @GameTest(environment = CampaignEnvironments.SYNC_CHUNK_LOAD, maxTicks = 2400)
    public void thePrefilledStrataLeaveTheWorldAsTheFillWithoutThem(GameTestHelper helper) {
        ServerLevel full = level(helper, FULL);
        ServerLevel sample = level(helper, SAMPLE);
        expect(helper, StrataPrefill.of(settings(full)) != null, "the shipped campaign generator does not prefill");
        expect(helper, StrataPrefill.of(settings(sample)) == null, "campaign_sample prefills, so it is no control");
        int top = CampaignBands.SURFACE.minY() + 32;
        int bottom = CampaignBands.LAYER_2.minY();
        for (ChunkPos pos : CHUNKS) {
            // sync-load: the case counts the writes of one chunk's generation (SurfaceWrites.watch), which must
            // run on this thread inside the watch; the test has the sync-chunk-load batch to itself
            LevelChunk control = sample.getChunk(pos.x(), pos.z());
            LevelChunk prefilled;
            SurfaceWrites.watch(pos);
            try {
                // sync-load: this load is the generation the watch counts, so it runs here, in the
                // sync-chunk-load batch
                prefilled = full.getChunk(pos.x(), pos.z());
                // Layer 1 holds about 49,000 blocks a chunk, most of them rock the guess gets right.
                long skipped = SurfaceWrites.watchedSkips();
                expect(
                        helper,
                        skipped > 20_000,
                        "only %s of layer 1's writes in %s found their block there",
                        skipped,
                        pos);
            } finally {
                SurfaceWrites.watch(null);
            }
            BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = bottom; y < top; y++) {
                        at.set(pos.getBlockX(x), y, pos.getBlockZ(z));
                        expect(
                                helper,
                                prefilled.getBlockState(at) == control.getBlockState(at),
                                "%s: %s with the prefill, %s without",
                                at.immutable(),
                                prefilled.getBlockState(at),
                                control.getBlockState(at));
                    }
                }
            }
        }
        helper.succeed();
    }

    private static Holder<NoiseGeneratorSettings> settings(ServerLevel level) {
        return ((NoiseBasedChunkGenerator) level.getChunkSource().getGenerator()).generatorSettings();
    }

    private static ServerLevel level(GameTestHelper helper, String path) {
        return ClientLight.serverLevel(
                helper.getLevel().getServer(),
                ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("deepcharter", path)));
    }
}
