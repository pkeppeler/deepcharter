package io.github.pkeppeler.deepcharter.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.pkeppeler.deepcharter.test.support.ColumnCost;
import io.github.pkeppeler.deepcharter.test.support.ColumnCost.Measurement;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Measures what generating the surface costs a new column of the tall campaign world (ADR 0029): CPU and heap per column of a
 * 2352 block world (the surface above the layers) against the layers alone at 2048, whose ratio the ADR bars at 1.15. By default it
 * generates a few columns of each world and checks they exist. With the number of rounds in the environment, for example
 * {@code DEEPCHARTER_SURFACE_COST=3 tools/gametest.sh 'surface_cost_test*'}, it generates {@value #MEASURED_RADIUS} chunks to each side of
 * a fresh centre per world and round (after a warm-up), and prints the lines that start with {@code [surface-cost]}: per world the
 * median over the rounds, and the ratio of each surface world to the layers-only world with the same biome source. The heap of a round
 * is only clean for the first world a JVM measures (chunks of the worlds before it linger in caches), so {@value #ONLY_ENV}=<world>
 * measures that one world alone: run once per world and compare the first round of each.
 *
 * <p>The worlds are test dimensions in the test mod's preset. With the layers' zone biomes: {@code tall_baseline} (layers only) and
 * {@code tall_surface} (our surface over the layers). With the surface's own biome source over the whole height, which costs a noise
 * lookup per quart cell and has no zone features: {@code tall_baseline_biomes} and {@code tall_surface_biomes}.
 *
 * <p>The surface's features (#245: the salvage crates and the fungal stalks) are measured by three more worlds. {@code tall_surface_bare} is
 * {@code tall_surface_biomes} with every surface biome's features taken out, so the ratio of the two is what the stalks cost. The yard is
 * the one biome that holds crates, and it lies within 120 blocks of the origin, where no measured region is, so {@code tall_surface_yard}
 * puts every column in it (its noise router's temperature is 1), the worst case for the crates, and {@code tall_surface_yard_bare} is the
 * same world with no features. Each of those four worlds is checked against the shipped files by
 * {@code theTallFixturesAreTheShippedSurfaceWithTheBandCutOff}.
 */
public class SurfaceCostTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(SurfaceCostTest.class);
    static final String ROUNDS_ENV = "DEEPCHARTER_SURFACE_COST";
    private static final String REQUESTED_ROUNDS = System.getenv(ROUNDS_ENV);
    /** One world name: measure only that world, so that its heap is read in a JVM that held no other measured world. */
    static final String ONLY_ENV = "DEEPCHARTER_SURFACE_COST_ONLY";

    private static final String REQUESTED_WORLD = System.getenv(ONLY_ENV);
    private static final List<String> ALL_WORLDS = List.of(
            "tall_baseline",
            "tall_surface",
            "tall_baseline_biomes",
            "tall_surface_biomes",
            "tall_surface_bare",
            "tall_surface_yard",
            "tall_surface_yard_bare");
    private static final List<String> WORLDS = REQUESTED_WORLD == null ? ALL_WORLDS : List.of(REQUESTED_WORLD);
    /** Each surface world, and the layers-only world it is compared with. */
    private static final Map<String, String> BASELINES =
            Map.of("tall_surface", "tall_baseline", "tall_surface_biomes", "tall_baseline_biomes");
    /** Each world whose biomes place features, and the same world with the features taken out: the ratio is what the features alone cost (#245). */
    private static final Map<String, String> WITHOUT_FEATURES =
            Map.of("tall_surface_biomes", "tall_surface_bare", "tall_surface_yard", "tall_surface_yard_bare");

    private static final int MAX_TICKS = 200_000;

    @GameTest(maxTicks = MAX_TICKS)
    public void everyTallWorldGeneratesColumns(GameTestHelper helper) {
        int rounds = REQUESTED_ROUNDS == null ? 0 : Integer.parseInt(REQUESTED_ROUNDS);
        ColumnCost.run(helper, WORLDS, ALL_WORLDS, rounds, SurfaceCostTest::report);
    }

    /** The fixtures are the shipped surface with the band cut off: their rule and biome sources are the shipped ones, not copies that can drift. */
    @GameTest
    public void theTallFixturesAreTheShippedSurfaceWithTheBandCutOff(GameTestHelper helper) throws IOException {
        JsonObject shippedRule = read(helper, "/data/deepcharter/worldgen/material_rule/surface.json")
                .getAsJsonObject();
        JsonObject tallRule = read(helper, "/data/deepcharter/worldgen/material_rule/tall_surface.json")
                .getAsJsonObject();
        JsonArray sequence = tallRule.getAsJsonArray("sequence");
        JsonObject gated = sequence.get(0).getAsJsonObject();
        if (!gated.get("if_true").equals(shippedRule.get("if_true"))
                || !gated.getAsJsonObject("then_run").get("then_run").equals(shippedRule.get("then_run"))
                || !sequence.get(1).getAsString().equals("deepcharter:layer")
                || sequence.size() != 2) {
            throw helper.assertionException(
                    Component.literal(
                            "tall_surface's material rule is not the shipped surface rule under a band gate, then the layers' rule"));
        }
        String tallDensity = read(helper, "/data/deepcharter/worldgen/noise_settings/tall_surface.json")
                .toString();
        String baselineDensity = read(helper, "/data/deepcharter/worldgen/noise_settings/tall_baseline.json")
                .toString();
        if (!tallDensity.contains("\"deepcharter:surface/density\"")
                || baselineDensity.contains("deepcharter:surface")) {
            throw helper.assertionException(
                    Component.literal("tall_surface must use the shipped surface density and tall_baseline must not"));
        }
        JsonObject dimensions = read(helper, "/data/minecraft/worldgen/world_preset/flat_all_dimensions.json")
                .getAsJsonObject()
                .getAsJsonObject("dimensions");
        JsonElement shippedBiomes = read(helper, "/data/minecraft/dimension/overworld.json")
                .getAsJsonObject()
                .getAsJsonObject("generator")
                .get("biome_source");
        for (String world : List.of("tall_surface_biomes", "tall_baseline_biomes")) {
            if (!dimensions
                    .getAsJsonObject("deepcharter:" + world)
                    .getAsJsonObject("generator")
                    .get("biome_source")
                    .equals(shippedBiomes)) {
                throw helper.assertionException(
                        Component.literal(world + " does not use the shipped overworld's biome source"));
            }
        }
        for (String biome : List.of("regolith_plains", "mesa_country", "basalt_field", "salvage_yard")) {
            JsonObject shipped = read(helper, "/data/deepcharter/worldgen/biome/" + biome + ".json")
                    .getAsJsonObject();
            JsonObject bare = read(helper, "/data/deepcharter-test/worldgen/biome/bare_" + biome + ".json")
                    .getAsJsonObject();
            JsonArray noFeatures = new JsonArray();
            shipped.getAsJsonArray("features").forEach(step -> noFeatures.add(new JsonArray()));
            shipped.add("features", noFeatures);
            if (!shipped.equals(bare)) {
                throw helper.assertionException(
                        Component.literal("bare_" + biome + " is not the shipped biome with its features taken out"));
            }
        }
        JsonObject yardSettings = read(helper, "/data/deepcharter/worldgen/noise_settings/tall_surface_yard.json")
                .getAsJsonObject();
        JsonObject surfaceSettings = read(helper, "/data/deepcharter/worldgen/noise_settings/tall_surface.json")
                .getAsJsonObject();
        surfaceSettings.getAsJsonObject("noise_router").addProperty("temperature", 1.0);
        if (!yardSettings.equals(surfaceSettings)) {
            throw helper.assertionException(
                    Component.literal(
                            "tall_surface_yard is not tall_surface with the temperature at 1.0, which puts every column in the yard"));
        }
        for (String world : List.of("tall_surface_yard", "tall_surface_bare", "tall_surface_yard_bare")) {
            JsonObject generator =
                    dimensions.getAsJsonObject("deepcharter:" + world).getAsJsonObject("generator");
            JsonElement source = generator.get("biome_source");
            boolean bareWorld = world.endsWith("bare");
            JsonElement expected = shippedBiomes.deepCopy();
            if (bareWorld) {
                expected.getAsJsonObject().getAsJsonArray("biomes").forEach(entry -> {
                    String id = entry.getAsJsonObject().get("biome").getAsString();
                    entry.getAsJsonObject()
                            .addProperty("biome", "deepcharter-test:bare_" + id.substring(id.indexOf(':') + 1));
                });
            }
            if (!source.equals(expected)) {
                throw helper.assertionException(Component.literal(
                        world + " does not use the shipped biome source" + (bareWorld ? " with its biomes bare" : "")));
            }
        }
        helper.succeed();
    }

    private static JsonElement read(GameTestHelper helper, String resource) throws IOException {
        try (var stream = SurfaceCostTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw helper.assertionException(Component.literal("missing resource " + resource));
            }
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }

    private static void report(Map<String, List<Measurement>> results) {
        LOGGER.info(
                "[surface-cost] {} columns per world and round, the median of {} rounds; ADR 0029 bar: 1.15x the layers-only world",
                ColumnCost.measuredColumns(),
                Objects.requireNonNull(results.get(WORLDS.getFirst()), "results.get(WORLDS.getFirst()) is absent")
                        .size());
        results.forEach((world, all) -> {
            Measurement m = ColumnCost.median(all);
            String against = results.containsKey(BASELINES.get(world))
                    ? "; vs " + BASELINES.get(world) + ": CPU "
                            + ColumnCost.format(m.cpuMsPerColumn()
                                    / ColumnCost.median(results.get(BASELINES.get(world)))
                                            .cpuMsPerColumn())
                            + "x, heap "
                            + ColumnCost.format(m.heapMb()
                                    / ColumnCost.median(results.get(BASELINES.get(world)))
                                            .heapMb())
                            + "x"
                    : "";
            String bare = WITHOUT_FEATURES.get(world);
            String features = results.containsKey(bare)
                    ? "; vs " + bare + " (the features alone): CPU "
                            + ColumnCost.format(m.cpuMsPerColumn()
                                    / ColumnCost.median(results.get(bare)).cpuMsPerColumn())
                            + "x, heap "
                            + ColumnCost.format(m.heapMb()
                                    / ColumnCost.median(results.get(bare)).heapMb()) + "x"
                    : "";
            LOGGER.info(
                    "[surface-cost] {}: {} CPU ms per column (rounds {}), {} MB heap (rounds {}), {} wall ms per column{}{}",
                    world,
                    ColumnCost.format(m.cpuMsPerColumn()),
                    all.stream().map(a -> ColumnCost.format(a.cpuMsPerColumn())).toList(),
                    Math.round(m.heapMb()),
                    all.stream().map(a -> Math.round(a.heapMb())).toList(),
                    ColumnCost.format(m.wallMsPerColumn()),
                    against,
                    features);
        });
    }
}
