package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonyBlocks;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.colony.ColonyTuning;
import io.github.pkeppeler.deepcharter.layer.LayerStructures;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.BlockSurvey;
import io.github.pkeppeler.deepcharter.test.support.CampaignBands;
import io.github.pkeppeler.deepcharter.test.support.CampaignBands.Band;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Server GameTests for what the campaign world holds besides terrain (#743, ADR 0059): the structure sites of each layer (ADR 0025), the
 * Prospector wreck, the Conduit, the cave stalks and the figure spawns, each in its own band. The zone fill's ore is in
 * {@code OrePlacementTest}. The world is {@code deepcharter:campaign_sample}; the band table is {@link CampaignBands}.
 */
public class CampaignContentTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 1000;
    /** A spacing cell of the sample level that no other test draws, away from the Conduit's column. */
    private static final int CELL = 3;

    /** The ordinary wreck's cell: a pod is saved with its chunk, so the test that reads it keeps a cell that no other test generates. */
    private static final int WRECK_CELL = 4;

    private static final int SPACING = LayerTuning.DEFAULT.structureSpacing();
    /** Spacing cells searched for sites: five by five. */
    private static final int CELLS = 5;
    /** The cave stalks are counted within this many chunks of the origin, a square of twice this and one on a side. */
    private static final int STALK_CHUNKS = 5;

    /**
     * What the shipped biomes hold to check: the surface stalks' height range, the cave stalks' layer rows, and a zone fill for each zone of
     * each layer. A feature a PR removes lowers it on purpose.
     */
    private static final int MIN_RANGES_CHECKED = 2 + CampaignBands.LAYERS.size() * Zones.COUNT;
    /** Placement modifiers that say nothing about the height, so a range check passes them. Any other type fails the check. */
    private static final Set<String> NEUTRAL_MODIFIERS = Set.of(
            "minecraft:count",
            "minecraft:in_square",
            "minecraft:rarity_filter",
            "minecraft:biome",
            "minecraft:offset",
            "minecraft:heightmap",
            "minecraft:environment_scan",
            "minecraft:block_predicate_filter");

    private static final String FIGURE = "deepcharter:lampless_figure";
    /** The test preset, which holds the campaign sample dimension. */
    private static final String PRESET = "/data/minecraft/worldgen/world_preset/flat_all_dimensions.json";

    private static final String UNBUILT_BIOME = "deepcharter:unbuilt_depth";

    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void structureSitesStayInsideTheirBandsMargins(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos conduit = conduit(helper);
        LayerTuning tuning = LayerTuning.DEFAULT;
        for (int number = 1; number <= CampaignBands.LAYERS.size(); number++) {
            Band band = CampaignBands.layer(number);
            for (StructureKind kind : StructureKind.inLayer(number)) {
                for (int cellX = 0; cellX < CELLS; cellX++) {
                    for (int cellZ = 0; cellZ < CELLS; cellZ++) {
                        StructureSite site = StructureSite.in(
                                level.getSeed(), kind, band.minY(), band.height(), cellX, cellZ, conduit);
                        BoundingBox box = site.bounds();
                        String where = kind + " in cell " + cellX + ", " + cellZ + " at " + box;
                        expect(
                                helper,
                                box.minX() >= cellX * SPACING
                                        && box.maxX() < (cellX + 1) * SPACING
                                        && box.minZ() >= cellZ * SPACING
                                        && box.maxZ() < (cellZ + 1) * SPACING,
                                "%s is out of its cell",
                                where);
                        if (kind == StructureKind.KELLS_SHAFT) {
                            // The shaft runs from the first block above the crust and has no margin of its own.
                            expect(
                                    helper,
                                    site.origin().getY() == band.minY() + CampaignBands.crustRows()
                                            && box.maxY() <= band.maxY() - tuning.structureCeilingMargin(),
                                    "%s is not from the crust to under the ceiling margin",
                                    where);
                            continue;
                        }
                        Zones.Span zone = Zones.span(band.minY(), band.height(), kind.zone());
                        expect(
                                helper,
                                box.minY() >= band.minY() + tuning.structureFloorMargin()
                                        && box.maxY() <= band.maxY() - tuning.structureCeilingMargin(),
                                "%s is inside the band's floor or ceiling margin",
                                where);
                        expect(
                                helper,
                                box.minY() >= zone.low() && box.maxY() <= zone.high(),
                                "%s leaves its zone %d..%d",
                                where,
                                zone.low(),
                                zone.high());
                    }
                }
            }
        }
        helper.succeed();
    }

    /**
     * A real chunk of the campaign world draws each structure of both layers, in the band of its layer: the blocks of
     * {@code LayerStructuresTest.unmet} stand in the site, judged by the band's own zones.
     */
    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void eachStructureStandsInItsBandOfTheCampaignWorld(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos conduit = conduit(helper);
        Map<StructureSite, Band> sites = new HashMap<>();
        for (int number = 1; number <= CampaignBands.LAYERS.size(); number++) {
            Band band = CampaignBands.layer(number);
            for (StructureKind kind : StructureKind.inLayer(number)) {
                StructureSite site =
                        StructureSite.in(level.getSeed(), kind, band.minY(), band.height(), CELL, CELL, conduit);
                sites.put(site, band);
                for (ChunkPos chunk : LayerStructuresTest.chunksOf(site)) {
                    level.getChunk(chunk.x(), chunk.z(), ChunkStatus.FULL);
                }
            }
        }
        FarChunks.Deadline deadline = FarChunks.deadline();
        TestTicks.each(helper, () -> {
            List<String> failing = new ArrayList<>();
            sites.forEach((site, band) -> {
                // The chunk states are read first and without loading: reading a block loads an unloaded chunk.
                String chunks = LayerStructuresTest.chunkStates(level, site);
                List<String> unmet = LayerStructuresTest.unmet(
                        level, band.minY(), band.height(), site, LayerStructuresTest.census(level, site));
                if (!unmet.isEmpty()) {
                    failing.add(String.format(
                            "%s at %s: unmet %s; chunks %s",
                            site.kind(), site.origin().toShortString(), unmet, chunks));
                }
            });
            deadline.await(
                    helper,
                    level,
                    failing.isEmpty(),
                    () -> String.format("structures did not settle in %d s: %s", FarChunks.WAIT_SECONDS, failing));
            if (failing.isEmpty()) {
                helper.succeed();
            }
        });
    }

    /**
     * The wreck site nearest the Conduit in layer 2 is PROSPECTOR-0002's: an unowned Prospector at hull 0 stands in its bay, with one lamp
     * burning, and an ordinary wreck site holds an unnamed wreck and no lamp.
     */
    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theProspectorWreckLiesAtTheWreckSiteNearestTheConduit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos conduit = conduit(helper);
        Band band = CampaignBands.LAYER_2;
        StructureSite famous = LayerStructures.nearest(
                level.getSeed(), StructureKind.WRECK, band.minY(), band.height(), conduit, conduit);
        StructureSite ordinary = StructureSite.in(
                level.getSeed(), StructureKind.WRECK, band.minY(), band.height(), WRECK_CELL, WRECK_CELL, conduit);
        expect(helper, !famous.equals(ordinary), "cell %d is the Prospector's own site", WRECK_CELL);
        expect(
                helper,
                famous.origin().getY()
                        >= Zones.span(band.minY(), band.height(), StructureKind.WRECK.zone())
                                .low(),
                "the Prospector's site at %s is outside Prospector's Run",
                famous.origin().toShortString());
        for (StructureSite site : List.of(famous, ordinary)) {
            for (ChunkPos chunk : LayerStructuresTest.chunksOf(site)) {
                level.getChunk(chunk.x(), chunk.z(), ChunkStatus.FULL);
            }
        }
        FarChunks.Deadline deadline = FarChunks.deadline();
        TestTicks.each(helper, () -> {
            List<String> unmet = new ArrayList<>();
            wreckUnmet(level, famous, true, unmet);
            wreckUnmet(level, ordinary, false, unmet);
            deadline.await(
                    helper,
                    level,
                    unmet.isEmpty(),
                    () -> String.format("the wrecks did not settle in %d s: %s", FarChunks.WAIT_SECONDS, unmet));
            if (unmet.isEmpty()) {
                helper.succeed();
            }
        });
    }

    private static void wreckUnmet(ServerLevel level, StructureSite site, boolean famous, List<String> unmet) {
        // entity-query: a test reads the pods of a bay it generated, in a chunk it forced full
        List<PodEntity> pods = level.getEntitiesOfClass(PodEntity.class, new AABB(site.origin()).inflate(3));
        String where = (famous ? "PROSPECTOR-0002's" : "an ordinary") + " bay at "
                + site.origin().toShortString();
        if (pods.size() != 1) {
            unmet.add(where + " holds " + pods.size() + " pods");
            return;
        }
        PodEntity pod = pods.getFirst();
        if (pod.getType() != PodRegistry.PROSPECTOR || !Wrecks.isWreck(pod) || pod.hull() != 0f) {
            unmet.add(where + " holds " + pod.getType() + " with hull " + pod.hull());
        }
        if (!pod.position().equals(Vec3.atBottomCenterOf(site.origin()))) {
            unmet.add(where + " has the pod at " + pod.position());
        }
        if (PodComponents.isFamousProspector(pod) != famous) {
            unmet.add(where + (famous ? " is not the famous Prospector" : " holds the famous Prospector"));
        }
        int lanterns = 0;
        BoundingBox box = site.bounds();
        for (BlockPos pos :
                BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            if (level.getBlockState(pos).is(Blocks.LANTERN)) {
                lanterns++;
            }
        }
        if (lanterns != (famous ? 1 : 0)) {
            unmet.add(where + " has " + lanterns + " lanterns");
        }
    }

    /**
     * The Conduit's casing fills its column without a gap from layer 2's floor up to the top of the colony's stack, and none lies in the
     * unbuilt depth below: the casing is set when a chunk loads (ADR 0016), as it is in the layers today.
     */
    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theConduitIsContinuousFromLayer2sFloorUpToTheColony(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ColonySite.Placed colony =
                Colony.placed(level.getServer()).orElseThrow(() -> failure(helper, "the colony was not built"));
        BlockPos centre = ColonyAnchor.CONDUIT.in(colony.anchors());
        int radius = ColonyTuning.DEFAULT.conduitRadius();
        int bottom = CampaignBands.LAYER_2.minY();
        int top = colony.groundY() + ColonyTuning.DEFAULT.conduitStack();
        for (int x = centre.getX() - radius; x <= centre.getX() + radius; x += radius) {
            for (int z = centre.getZ() - radius; z <= centre.getZ() + radius; z += radius) {
                level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL);
            }
        }
        List<String> gaps = new ArrayList<>();
        for (int x = centre.getX() - radius; x <= centre.getX() + radius; x++) {
            for (int z = centre.getZ() - radius; z <= centre.getZ() + radius; z++) {
                for (int y = bottom; y <= top; y++) {
                    if (!level.getBlockState(new BlockPos(x, y, z)).is(ColonyBlocks.CONDUIT)) {
                        gaps.add(x + " " + y + " " + z);
                    }
                }
                expect(
                        helper,
                        !level.getBlockState(new BlockPos(x, bottom - 1, z)).is(ColonyBlocks.CONDUIT),
                        "the casing goes below layer 2's floor at %d %d",
                        x,
                        z);
            }
        }
        expect(
                helper,
                gaps.isEmpty(),
                "the casing has %d gaps between %d and %d, the first at %s",
                gaps.size(),
                bottom,
                top,
                gaps.stream().limit(3).toList());
        helper.succeed();
    }

    /**
     * The height range of every placed feature of every biome of the campaign world lies inside the band the biome is in, and a fill
     * names the layer and zone of its own biome. A range anchored to the dimension's bottom or top (rather than absolute) resolves against
     * the whole world, so it is read that way.
     */
    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void everyPlacedFeaturesHeightRangeIsInsideItsBand(GameTestHelper helper) throws IOException {
        Map<String, Band> bandOfBiome = new TreeMap<>();
        Map<String, int[]> zoneOfBiome = new TreeMap<>();
        JsonObject source = read(helper, PRESET)
                .getAsJsonObject()
                .getAsJsonObject("dimensions")
                .getAsJsonObject(SAMPLE)
                .getAsJsonObject("generator")
                .getAsJsonObject("biome_source");
        source.getAsJsonObject("surface")
                .getAsJsonArray("biomes")
                .forEach(entry ->
                        bandOfBiome.put(entry.getAsJsonObject().get("biome").getAsString(), CampaignBands.SURFACE));
        bandOfBiome.put(UNBUILT_BIOME, CampaignBands.UNBUILT);
        for (int number = 1; number <= CampaignBands.LAYERS.size(); number++) {
            Band band = CampaignBands.layer(number);
            for (int zone = 0; zone < Zones.COUNT; zone++) {
                String id = Zones.of(
                                number,
                                band.minY(),
                                band.height(),
                                Zones.span(band.minY(), band.height(), zone).low())
                        .orElseThrow()
                        .id()
                        .toString();
                bandOfBiome.put(id, band);
                zoneOfBiome.put(id, new int[] {number, zone});
            }
        }
        int checked = 0;
        for (Map.Entry<String, Band> biome : bandOfBiome.entrySet()) {
            JsonObject json = read(helper, resource("biome", biome.getKey())).getAsJsonObject();
            for (JsonElement step : json.getAsJsonArray("features")) {
                for (JsonElement featureId : step.getAsJsonArray()) {
                    String id = featureId.getAsString();
                    JsonObject placed =
                            read(helper, resource("placed_feature", id)).getAsJsonObject();
                    for (JsonElement modifier : placed.getAsJsonArray("placement")) {
                        checked += checkModifier(helper, id, biome.getValue(), modifier.getAsJsonObject());
                    }
                    JsonObject feature = read(
                                    helper,
                                    resource("feature", placed.get("feature").getAsString()))
                            .getAsJsonObject();
                    if (feature.get("type").getAsString().equals("deepcharter:zone_fill")) {
                        expect(helper, feature.has("layer"), "%s names no layer", id);
                        int[] zone = Objects.requireNonNull(
                                zoneOfBiome.get(biome.getKey()),
                                () -> id + " fills " + biome.getKey() + ", which is no zone biome");
                        expect(
                                helper,
                                feature.get("layer").getAsInt() == zone[0]
                                        && feature.get("zone").getAsInt() == zone[1],
                                "%s is layer %s zone %s but stands in %s, layer %d zone %d",
                                id,
                                feature.get("layer"),
                                feature.get("zone"),
                                biome.getKey(),
                                zone[0],
                                zone[1]);
                        checked++;
                    }
                }
            }
        }
        expect(
                helper,
                checked >= MIN_RANGES_CHECKED,
                "%d placed features had a height range or a zone to check, expected at least %d",
                checked,
                MIN_RANGES_CHECKED);
        expect(
                helper,
                read(helper, resource("biome", UNBUILT_BIOME))
                        .getAsJsonObject()
                        .getAsJsonArray("features")
                        .asList()
                        .stream()
                        .allMatch(step -> step.getAsJsonArray().isEmpty()),
                "unbuilt depth places features");
        helper.succeed();
    }

    /** The number of ranges in the band that {@code modifier} gave, checked. */
    private static int checkModifier(GameTestHelper helper, String feature, Band band, JsonObject modifier) {
        String type = modifier.get("type").getAsString();
        int low;
        int high;
        switch (type) {
            case "minecraft:height_range" -> {
                JsonObject height = modifier.getAsJsonObject("height");
                low = anchor(helper, height.getAsJsonObject("min_inclusive"));
                high = anchor(helper, height.getAsJsonObject("max_inclusive"));
            }
            case "deepcharter:layer_rows" -> {
                int layer = modifier.get("layer").getAsInt();
                expect(
                        helper,
                        layer >= 1
                                && layer <= CampaignBands.LAYERS.size()
                                && CampaignBands.layer(layer).equals(band),
                        "%s places rows of layer %d in %s",
                        feature,
                        layer,
                        band.name());
                low = band.minY() + modifier.get("min").getAsInt();
                high = band.minY() + modifier.get("max").getAsInt();
            }
            default -> {
                expect(
                        helper,
                        NEUTRAL_MODIFIERS.contains(type),
                        "%s has a placement modifier %s that is not known",
                        feature,
                        type);
                return 0;
            }
        }
        expect(
                helper,
                low >= band.minY() && high <= band.maxY() && low <= high,
                "%s places %d..%d, outside %s %d..%d",
                feature,
                low,
                high,
                band.name(),
                band.minY(),
                band.maxY());
        return 1;
    }

    /** The Y of a vertical anchor in the campaign world, which spans the whole of the bands. */
    private static int anchor(GameTestHelper helper, JsonObject anchor) {
        if (anchor.has("absolute")) {
            return anchor.get("absolute").getAsInt();
        }
        if (anchor.has("above_bottom")) {
            return CampaignBands.worldMinY() + anchor.get("above_bottom").getAsInt();
        }
        if (anchor.has("below_top")) {
            return CampaignBands.worldTopExclusive()
                    - 1
                    - anchor.get("below_top").getAsInt();
        }
        throw failure(helper, "a vertical anchor %s is none of absolute, above_bottom and below_top", anchor);
    }

    /**
     * The cave stalks of Topsoil Claims, layer 1's first zone, grow there and nowhere else under the surface: the range of the
     * biome's feature is the layer's own rows, so the zone's own rows are where they stand (to a block, for the offset a stalk takes).
     */
    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void caveStalksGrowInLayer1sFirstZoneOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Band layer = CampaignBands.LAYER_1;
        Zones.Span zone = Zones.span(layer.minY(), layer.height(), 0);
        TreeSet<Integer> rows = new TreeSet<>();
        for (BlockPos stalk : BlockSurvey.find(level, STALK_CHUNKS, ours("fungal_stalk"))) {
            // The surface has stalks of its own (the surface band's features).
            if (stalk.getY() < CampaignBands.SURFACE.minY()) {
                rows.add(stalk.getY());
            }
        }
        expect(
                helper,
                !rows.isEmpty(),
                "no cave stalk grew in %d chunks of layer 1",
                (2 * STALK_CHUNKS + 1) * (2 * STALK_CHUNKS + 1));
        expect(
                helper,
                rows.first() >= zone.low() - 1 && rows.last() <= zone.high() + 1,
                "cave stalks stand at %d..%d, outside Topsoil Claims %d..%d",
                rows.first(),
                rows.last(),
                zone.low(),
                zone.high());
        helper.succeed();
    }

    private static Block ours(String path) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("deepcharter", path));
    }

    /**
     * The lampless figure's natural spawn is part of the biome data of both layers' zones (the rail-line spawns are the creature
     * feature's own), so it follows the bands: every zone biome of the layers carries it, and the surface and the unbuilt depth do not.
     */
    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theLamplessFigureSpawnsInTheZonesOfBothLayersAndNowhereElse(GameTestHelper helper) throws IOException {
        for (int number = 1; number <= CampaignBands.LAYERS.size(); number++) {
            Band band = CampaignBands.layer(number);
            for (int zone = 0; zone < Zones.COUNT; zone++) {
                String id = Zones.of(
                                number,
                                band.minY(),
                                band.height(),
                                Zones.span(band.minY(), band.height(), zone).low())
                        .orElseThrow()
                        .id()
                        .toString();
                expect(helper, spawnsFigure(helper, id), "%s lacks the lampless figure", id);
            }
        }
        expect(helper, !spawnsFigure(helper, UNBUILT_BIOME), "unbuilt depth spawns the figure");
        expect(helper, !spawnsFigure(helper, "deepcharter:regolith_plains"), "the surface spawns the figure");
        helper.succeed();
    }

    private static boolean spawnsFigure(GameTestHelper helper, String biome) throws IOException {
        JsonObject attributes =
                read(helper, resource("biome", biome)).getAsJsonObject().getAsJsonObject("attributes");
        JsonObject spawns = attributes.getAsJsonObject("minecraft:gameplay/natural_mob_spawns");
        if (spawns == null) {
            return false;
        }
        JsonObject byCategory = spawns.getAsJsonObject("argument").getAsJsonObject("spawns_by_category");
        for (var category : byCategory.entrySet()) {
            for (JsonElement entry : category.getValue().getAsJsonArray()) {
                if (entry.getAsJsonObject().get("type").getAsString().equals(FIGURE)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static BlockPos conduit(GameTestHelper helper) {
        return Colony.anchor(helper.getLevel().getServer(), ColonyAnchor.CONDUIT)
                .orElseThrow(() -> failure(helper, "the colony was not built"));
    }

    /** The resource path of the data file of {@code id} under {@code worldgen/<folder>}. */
    private static String resource(String folder, String id) {
        return "/data/" + id.replace(':', '/').replaceFirst("/", "/worldgen/" + folder + "/") + ".json";
    }

    private static JsonElement read(GameTestHelper helper, String resource) throws IOException {
        var stream = CampaignContentTest.class.getResourceAsStream(resource);
        if (stream == null) {
            throw failure(helper, "missing resource %s", resource);
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        }
    }

    @FormatMethod
    private static void expect(
            GameTestHelper helper, boolean condition, @FormatString String format, @Nullable Object... args) {
        if (!condition) {
            throw failure(helper, format, args);
        }
    }

    @FormatMethod
    private static RuntimeException failure(
            GameTestHelper helper, @FormatString String format, @Nullable Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }
}
