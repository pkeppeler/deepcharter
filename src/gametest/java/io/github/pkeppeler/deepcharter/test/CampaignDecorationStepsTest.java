package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import com.google.common.base.Splitter;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A placed feature's random is seeded by its decoration step and its index in that step, counted across every biome of the generator
 * (ADR 0015, amended by #972). A feature added where it shifts the index of one that exists reseeds that one, and the ore, lava and growth
 * of its band move in every world generated after. This holds every placed feature of the campaign generator to the step and index in
 * {@value #GOLDEN}, which is committed: a feature that moved fails, saying why; one that is new fails, asking for its line.
 */
public class CampaignDecorationStepsTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(CampaignDecorationStepsTest.class);
    private static final String FULL = "deepcharter:campaign_full";
    private static final String GOLDEN = "/campaign_decoration_steps.tsv";

    @GameTest(environment = CampaignEnvironments.D, dimension = FULL)
    public void noFeatureMovesInItsDecorationStep(GameTestHelper helper) throws IOException {
        Map<String, String> actual =
                placements(helper.getLevel().getChunkSource().getGenerator(), helper);
        Map<String, String> golden = golden();
        List<String> moved = new ArrayList<>();
        List<String> added = new ArrayList<>();
        golden.forEach((feature, place) -> {
            String now = actual.get(feature);
            if (!place.equals(now)) {
                moved.add(feature + " at " + place + " is now " + (now == null ? "gone" : "at " + now));
            }
        });
        actual.forEach((feature, place) -> {
            if (!golden.containsKey(feature)) {
                added.add(place.replace(' ', '\t') + "\t" + feature);
            }
        });
        if (moved.isEmpty() && added.isEmpty()) {
            helper.succeed();
            return;
        }
        StringBuilder list = new StringBuilder();
        actual.forEach((feature, place) ->
                list.append('\n').append(place.replace(' ', '\t')).append('\t').append(feature));
        LOGGER.info("[decoration-steps] the generator's steps and indices now:{}", list);
        if (!moved.isEmpty()) {
            throw failure(
                    helper,
                    "%s placed features moved in their decoration step: %s. A feature's random is seeded by its step and its index"
                            + " in that step across every biome, so each of these now grows differently and its band's ore, lava"
                            + " or growth moves. Put a new feature in a step no other band uses, or where it sorts after every"
                            + " feature already there (ADR 0015, #972). If the reseed is meant, copy the list in the log into"
                            + " src/gametest/resources%s and say so in the PR.",
                    moved.size(),
                    String.join("; ", moved),
                    GOLDEN);
        }
        throw failure(
                helper,
                "new placed features, and none of the old moved: add these lines to src/gametest/resources%s:%n%s",
                GOLDEN,
                String.join("\n", added));
    }

    /** Each placed feature of {@code generator}'s biomes, by id, with its step and index as "step index", in step then index order. */
    private static Map<String, String> placements(ChunkGenerator generator, GameTestHelper helper) {
        List<Holder<Biome>> biomes = List.copyOf(generator.getBiomeSource().possibleBiomes());
        // The generator sorts the same list the same way (ChunkGenerator's featuresPerStep).
        List<FeatureSorter.StepFeatureData> steps = FeatureSorter.buildFeaturesPerStep(
                biomes, biome -> biome.value().getGenerationSettings().features(), true);
        Registry<PlacedFeature> registry = helper.getLevel().registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
        Map<String, String> out = new LinkedHashMap<>();
        for (int step = 0; step < steps.size(); step++) {
            FeatureSorter.StepFeatureData data = steps.get(step);
            for (PlacedFeature feature : data.features()) {
                String id = registry.getResourceKey(feature)
                        .map(key -> key.identifier().toString())
                        .orElseThrow(() -> new IllegalStateException("a placed feature of the campaign has no id"));
                out.put(id, step + " " + data.indexMapping().applyAsInt(feature));
            }
        }
        return out;
    }

    /** The committed steps and indices, by feature id, from lines of "step, index, feature" split by tabs; # starts a comment. */
    private static Map<String, String> golden() throws IOException {
        InputStream stream = CampaignDecorationStepsTest.class.getResourceAsStream(GOLDEN);
        if (stream == null) {
            throw new IllegalStateException("no resource " + GOLDEN);
        }
        Map<String, String> out = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                List<String> fields = Splitter.on('\t').splitToList(line);
                if (fields.size() != 3) {
                    throw new IllegalStateException(GOLDEN + " has a line that is not step, index, feature: " + line);
                }
                out.put(fields.get(2), fields.get(0) + " " + fields.get(1));
            }
        }
        return out;
    }
}
