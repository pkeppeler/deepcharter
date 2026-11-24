package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerAtmosphere;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.layer.gen.BandBiomeSource;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.TestClocks;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeMap;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.attribute.modifier.AttributeModifier;
import net.minecraft.world.attribute.modifier.ColorModifier;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Server GameTests for #797 (campaign world plan, #214; ADR 0062): in a campaign level the layers' zone biomes are applied after the
 * surface's sky timeline and the weather, so a layer keeps its own fog, light and dust while the surface band reads exactly what the flat
 * overworld reads. Everything runs in {@code deepcharter:campaign_sample}, the test dimension of the shipped campaign type.
 */
public class CampaignAtmosphereTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final Identifier SKY = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "sky");
    private static final long SKY_DUSK = 0;
    private static final long SKY_NIGHT = 144_000;
    private static final long GAMEPLAY_NOON = 6_000;
    private static final long GAMEPLAY_MIDNIGHT = 18_000;
    /** A standing player's eye above the feet. */
    private static final double EYE_HEIGHT = 1.62;

    /**
     * What a player sees or hears underground, and that a timeline of the type drives: the biome must set each. The sky is among them: the
     * shared type draws it, and a cavern shows it wherever a line of sight runs past the render distance (#1161).
     */
    private static final List<EnvironmentAttribute<?>> UNDERGROUND = List.of(
            EnvironmentAttributes.FOG_COLOR,
            EnvironmentAttributes.FOG_START_DISTANCE,
            EnvironmentAttributes.FOG_END_DISTANCE,
            EnvironmentAttributes.SKY_LIGHT_FACTOR,
            EnvironmentAttributes.AMBIENT_PARTICLES,
            EnvironmentAttributes.SKY_COLOR,
            EnvironmentAttributes.SUNRISE_SUNSET_COLOR,
            EnvironmentAttributes.SUN_ANGLE,
            EnvironmentAttributes.MOON_ANGLE,
            EnvironmentAttributes.STAR_BRIGHTNESS);

    /**
     * What the biome layer must put back in a layer, with the ambient colour, the sky's fog end and the water's fog: the six values of #797,
     * the sky of #1161 and the water of #1187. The ambient colour, the sky's fog end and the water's fog are no timeline's, so the biome's
     * static layer already wins; they are read here to pin that it stays so.
     */
    private static final List<EnvironmentAttribute<?>> LAYER_VALUES = List.of(
            EnvironmentAttributes.FOG_COLOR,
            EnvironmentAttributes.FOG_START_DISTANCE,
            EnvironmentAttributes.FOG_END_DISTANCE,
            EnvironmentAttributes.SKY_LIGHT_FACTOR,
            EnvironmentAttributes.AMBIENT_LIGHT_COLOR,
            EnvironmentAttributes.AMBIENT_PARTICLES,
            EnvironmentAttributes.SKY_COLOR,
            EnvironmentAttributes.SUNRISE_SUNSET_COLOR,
            EnvironmentAttributes.SUN_ANGLE,
            EnvironmentAttributes.MOON_ANGLE,
            EnvironmentAttributes.STAR_BRIGHTNESS,
            EnvironmentAttributes.SKY_FOG_END_DISTANCE,
            EnvironmentAttributes.WATER_FOG_COLOR,
            EnvironmentAttributes.WATER_FOG_END_DISTANCE);

    /**
     * The angle that puts the sun and the moon straight down. Below the horizon the client draws a dark disc over them (vanilla's
     * {@code SkyRenderer.renderDarkDisc}, under y 63 in a level that is not flat), which a sky fog end of 0 turns to the fog colour.
     */
    private static final float NADIR = 180f;

    /** The brightest a channel of a layer's water fog may be: the art direction's world stays black (#1187). */
    private static final float DARKEST_WATER = 0x20 / 255f;

    /**
     * The visual and audio attributes a timeline of the type drives that no underground player can see or hear, each with why. A new
     * one in a timeline fails {@link #everyTimelineAttributeIsSeenOrAcknowledged} until it is listed here or in {@link #UNDERGROUND}.
     */
    private static final Map<EnvironmentAttribute<?>, String> NOT_UNDERGROUND = Map.ofEntries(
            Map.entry(EnvironmentAttributes.CLOUD_COLOR, "the timeline's clouds are clear, and far above every layer"),
            Map.entry(EnvironmentAttributes.STAR_ANGLE, "a layer biome's star brightness of 0 draws no star"),
            Map.entry(EnvironmentAttributes.MOON_PHASE, "a layer biome's moon angle puts the moon under the horizon"),
            Map.entry(EnvironmentAttributes.SKY_LIGHT_COLOR, "a layer's sky light factor of 0 hides it"),
            Map.entry(EnvironmentAttributes.FIREFLY_BUSH_SOUNDS, "no layer grows a firefly bush"));

    /** One setting of the clocks and the weather. */
    private record Moment(String name, long skyTicks, long gameplayTicks, float thunder) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final List<Moment> MOMENTS = List.of(
            new Moment("sky 0, noon, clear", SKY_DUSK, GAMEPLAY_NOON, 0f),
            new Moment("sky 0, midnight, clear", SKY_DUSK, GAMEPLAY_MIDNIGHT, 0f),
            new Moment("sky 144000, noon, clear", SKY_NIGHT, GAMEPLAY_NOON, 0f),
            new Moment("sky 144000, midnight, clear", SKY_NIGHT, GAMEPLAY_MIDNIGHT, 0f),
            new Moment("sky 0, noon, thunder", SKY_DUSK, GAMEPLAY_NOON, 1f),
            new Moment("sky 144000, midnight, thunder", SKY_NIGHT, GAMEPLAY_MIDNIGHT, 1f));

    /** A layer's zone, with the middle block of its rows. */
    private record Zone(int layer, Identifier biome, BlockPos middle) {}

    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE)
    public void aLayerReadsItsOwnBiomeAtEverySkyTimeGameplayTimeAndWeather(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (Zone zone : zones(helper, level)) {
            Biome biome = biome(level, zone);
            for (Moment moment : MOMENTS) {
                at(level, moment, () -> {
                    for (EnvironmentAttribute<?> attribute : LAYER_VALUES) {
                        expectEqual(
                                helper,
                                "layer %s zone %s, %s, %s"
                                        .formatted(zone.layer(), zone.biome(), moment, name(attribute)),
                                wanted(biome, attribute),
                                level.environmentAttributes().getValue(attribute, zone.middle()));
                    }
                });
            }
        }
        helper.succeed();
    }

    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE)
    public void theSurfaceBandReadsWhatTheFlatOverworldReads(GameTestHelper helper) {
        ServerLevel campaign = helper.getLevel();
        ServerLevel flat = TestLayers.level(helper, TestLayers.SURFACE);
        BlockPos inSurface = new BlockPos(0, CampaignBands.SURFACE.minY() + 100, 0);
        for (Moment moment : MOMENTS) {
            at(
                    campaign,
                    moment,
                    () -> at(flat, moment, () -> {
                        int compared = 0;
                        for (EnvironmentAttribute<?> attribute : BuiltInRegistries.ENVIRONMENT_ATTRIBUTE) {
                            if (!isVisual(attribute)) {
                                continue;
                            }
                            expectEqual(
                                    helper,
                                    "the surface band, %s, %s".formatted(moment, name(attribute)),
                                    flat.environmentAttributes().getValue(attribute, inSurface),
                                    campaign.environmentAttributes().getValue(attribute, inSurface));
                            compared++;
                        }
                        expect(helper, compared > 10, "only %s visual attributes were compared", compared);
                    }));
        }
        // The comparison would pass if the clocks and the weather moved nothing: they move the fog.
        float[] fog = new float[2];
        for (int i = 0; i < 2; i++) {
            Moment moment = MOMENTS.get(i == 0 ? 0 : 5);
            int index = i;
            at(
                    campaign,
                    moment,
                    () -> fog[index] = campaign.environmentAttributes()
                            .getValue(EnvironmentAttributes.FOG_START_DISTANCE, inSurface));
        }
        expect(
                helper,
                fog[0] != fog[1],
                "the surface fog start should move with the sky clock, was %s at both ends",
                fog[0]);
        helper.succeed();
    }

    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE)
    public void noSurfaceBiomeSetsWhatATimelineSets(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Set<EnvironmentAttribute<?>> driven = timelineAttributes(level);
        expect(helper, !driven.isEmpty(), "the type runs no timeline that sets an attribute");
        List<Holder<Biome>> surface =
                new ArrayList<>(source(level).possibleBiomes().stream().toList());
        surface.removeAll(layerBiomes(level));
        expect(helper, surface.size() >= 4, "the surface has %s biomes, wanted at least 4", surface.size());
        for (Holder<Biome> biome : surface) {
            for (EnvironmentAttribute<?> attribute : driven) {
                // Gameplay attributes are not in the mixin's layer: a biome's false patrol flag is `and`ed in (#915).
                if (!LayerAtmosphere.isVisualOrAudio(attribute)) {
                    continue;
                }
                expect(
                        helper,
                        !biome.value().getAttributes().contains(attribute),
                        "surface biome %s sets %s, which a timeline of the type sets: the biome layer would put it over the timeline in the"
                                + " surface band",
                        id(biome),
                        name(attribute));
            }
        }
        helper.succeed();
    }

    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE)
    public void everyLayerBiomeSetsEachTimelineAttributeThatCanBeSeenUnderground(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Set<EnvironmentAttribute<?>> driven = timelineAttributes(level);
        for (EnvironmentAttribute<?> attribute : UNDERGROUND) {
            expect(
                    helper,
                    driven.contains(attribute),
                    "no timeline of the type sets %s: take it off UNDERGROUND",
                    name(attribute));
        }
        List<Holder<Biome>> layerBiomes = layerBiomes(level);
        int zoneBiomes = CampaignBands.LAYERS.size() * Zones.COUNT;
        expect(
                helper,
                layerBiomes.size() == zoneBiomes,
                "the layers have %s zone biomes, wanted %s",
                layerBiomes.size(),
                zoneBiomes);
        for (Holder<Biome> biome : layerBiomes) {
            for (EnvironmentAttribute<?> attribute : UNDERGROUND) {
                expect(
                        helper,
                        biome.value().getAttributes().contains(attribute),
                        "layer biome %s does not set %s, so the surface's timeline shows through underground",
                        id(biome),
                        name(attribute));
                // The biome entry is applied twice (ADR 0062), which only an override survives.
                var entry = biome.value().getAttributes().get(attribute);
                expect(
                        helper,
                        entry != null && entry.modifier().equals(AttributeModifier.override()),
                        "layer biome %s sets %s with a modifier other than override",
                        id(biome),
                        name(attribute));
            }
        }
        helper.succeed();
    }

    /**
     * A cavern shows the sky wherever a line of sight runs past the render distance, so each layer biome turns the sky into its fog
     * (#1161): the sky's colour is the fog colour, the sky dome is all fog, no sunset glow, no stars, and the sun and the moon straight down.
     */
    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE)
    public void everyLayerBiomeTurnsTheSkyIntoItsFog(GameTestHelper helper) {
        List<Holder<Biome>> layerBiomes = layerBiomes(helper.getLevel());
        expect(helper, !layerBiomes.isEmpty(), "the sample level places no layer biome");
        for (Holder<Biome> holder : layerBiomes) {
            Biome biome = holder.value();
            Identifier id = id(holder);
            Vector3fc fog = wanted(biome, EnvironmentAttributes.FOG_COLOR);
            expect(
                    helper,
                    biome.getAttributes().contains(EnvironmentAttributes.SKY_COLOR)
                            && wanted(biome, EnvironmentAttributes.SKY_COLOR).equals(fog),
                    "layer biome %s: set minecraft:visual/sky_color to its fog colour %s, or the fog takes on the sky's colour",
                    id,
                    hex(fog));
            expect(
                    helper,
                    biome.getAttributes().contains(EnvironmentAttributes.SKY_FOG_END_DISTANCE)
                            && wanted(biome, EnvironmentAttributes.SKY_FOG_END_DISTANCE) == 0f,
                    "layer biome %s: set minecraft:visual/sky_fog_end_distance to 0.0, so the sky dome and the dark disc under the horizon"
                            + " are all fog, read %s",
                    id,
                    wanted(biome, EnvironmentAttributes.SKY_FOG_END_DISTANCE));
            expect(
                    helper,
                    biome.getAttributes().contains(EnvironmentAttributes.SUNRISE_SUNSET_COLOR)
                            && wanted(biome, EnvironmentAttributes.SUNRISE_SUNSET_COLOR)
                                            .w()
                                    == 0f,
                    "layer biome %s: set minecraft:visual/sunrise_sunset_color to \"#00000000\" (alpha 0), or the dusk glow shows",
                    id);
            expect(
                    helper,
                    biome.getAttributes().contains(EnvironmentAttributes.STAR_BRIGHTNESS)
                            && wanted(biome, EnvironmentAttributes.STAR_BRIGHTNESS) == 0f,
                    "layer biome %s: set minecraft:visual/star_brightness to 0.0, or the stars show",
                    id);
            for (EnvironmentAttribute<Float> body :
                    List.of(EnvironmentAttributes.SUN_ANGLE, EnvironmentAttributes.MOON_ANGLE)) {
                expect(
                        helper,
                        biome.getAttributes().contains(body) && wanted(biome, body) == NADIR,
                        "layer biome %s: set %s to %s, so it stands under the horizon, behind the dark disc",
                        id,
                        name(body),
                        NADIR);
            }
        }
        helper.succeed();
    }

    /**
     * Under water the client lifts the water's fog colour as the eyes adjust, to full brightness at its brightest channel, unless a channel
     * is exactly 0 (26.3 {@code FogRenderer.computeFogColor}): vanilla's {@code #050533} becomes {@code #1919ff}, and any dark tint with
     * no zero channel becomes as bright (#1187). So each layer biome sets a dark water fog with a zero channel and a fog end, and two zones
     * one above the other share a zero channel, because the camera blends their colours and a blend of two different zeros has none.
     */
    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE)
    public void everyLayerBiomeSetsADarkWaterFogTheEyesCannotLift(GameTestHelper helper) {
        List<Holder<Biome>> layerBiomes = layerBiomes(helper.getLevel());
        expect(helper, !layerBiomes.isEmpty(), "the sample level places no layer biome");
        Holder<Biome> above = null;
        for (Holder<Biome> holder : layerBiomes) {
            Biome biome = holder.value();
            Identifier id = id(holder);
            for (EnvironmentAttribute<?> attribute :
                    List.of(EnvironmentAttributes.WATER_FOG_COLOR, EnvironmentAttributes.WATER_FOG_END_DISTANCE)) {
                expect(
                        helper,
                        biome.getAttributes().contains(attribute),
                        "layer biome %s does not set %s, so its water shows vanilla's",
                        id,
                        name(attribute));
            }
            Vector3fc water = wanted(biome, EnvironmentAttributes.WATER_FOG_COLOR);
            expect(
                    helper,
                    !zeroChannels(water).isEmpty(),
                    "layer biome %s: water fog %s has no channel at 0, so the eyes adjusting lift it to full brightness",
                    id,
                    hex(water));
            expect(
                    helper,
                    Math.max(water.x(), Math.max(water.y(), water.z())) <= DARKEST_WATER,
                    "layer biome %s: water fog %s is brighter than %s on a channel, but the world stays black",
                    id,
                    hex(water),
                    hex(new Vector3f(DARKEST_WATER)));
            if (above != null) {
                Set<Integer> shared = new TreeSet<>(zeroChannels(wanted(above.value(), EnvironmentAttributes.WATER_FOG_COLOR)));
                shared.retainAll(zeroChannels(water));
                expect(
                        helper,
                        !shared.isEmpty(),
                        "the water fogs of %s and %s below it share no zero channel, so their blend is lifted to full brightness",
                        id(above),
                        id);
            }
            above = holder;
        }
        helper.succeed();
    }

    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE)
    public void everyTimelineAttributeIsSeenOrAcknowledged(GameTestHelper helper) {
        for (EnvironmentAttribute<?> attribute : timelineAttributes(helper.getLevel())) {
            if (LayerAtmosphere.isVisualOrAudio(attribute)) {
                expect(
                        helper,
                        UNDERGROUND.contains(attribute) || NOT_UNDERGROUND.containsKey(attribute),
                        "a timeline of the type sets %s: decide whether a player sees or hears it underground, and list it in UNDERGROUND"
                                + " (and in the zone biomes) or in NOT_UNDERGROUND",
                        name(attribute));
            }
        }
        helper.succeed();
    }

    /**
     * The camera blends biomes over some blocks, so the sky light factor of layer 1 is a fraction of what the surface above reads (#744,
     * art-direction section 6: 0.32 at the edge, 0 twelve blocks in). That table is a player's eye 1.62 above the feet named, with the
     * layer biome filled through the block {@code edge}, whose 4-block cell ends 4 blocks above it; the fraction is of the surface's own
     * factor, which is the sky timeline's here and was 1.0 there.
     */
    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE)
    public void theEdgeOfLayerOneBlendsAsMeasuredInTheLayerAtmosphereStudy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int edge = CampaignBands.LAYER_1.topExclusive() - 4;
        Map<Integer, Float> measured =
                Map.of(-8, 0.94f, -4, 0.70f, 0, 0.32f, 2, 0.20f, 4, 0.07f, 6, 0.03f, 8, 0.002f, 12, 0f, 16, 0f);
        Map<Integer, Float> ratio = new TreeMap<>();
        at(level, MOMENTS.getFirst(), () -> {
            float surface = probe(level, new Vec3(0.5, CampaignBands.SURFACE.minY() + 100, 0.5));
            expectNear(helper, "the surface's sky light factor at sky time 0", 0.62f, surface, 0.001f);
            for (int below : measured.keySet()) {
                ratio.put(below, probe(level, new Vec3(0.5, edge - below + EYE_HEIGHT, 0.5)) / surface);
            }
        });
        System.out.println(
                "[campaign-atmosphere] edge blend, fraction of the surface's sky light factor by feet below the edge: "
                        + ratio);
        measured.forEach((below, wanted) -> expectNear(
                helper,
                "%s blocks below the edge".formatted(below),
                wanted,
                Objects.requireNonNull(ratio.get(below)),
                0.06f));
        expectNear(helper, "12 blocks below the edge", 0f, Objects.requireNonNull(ratio.get(12)), 0.0005f);
        helper.succeed();
    }

    @GameTest
    public void aBiomeThatSetsAReaddedAttributeWithoutOverrideFailsLoud(GameTestHelper helper) {
        Identifier biome = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "multiplied");
        var multiplied = EnvironmentAttributeMap.builder()
                .modify(EnvironmentAttributes.FOG_COLOR, ColorModifier.MULTIPLY_RGB, new Vector3f(0.5f, 0.5f, 0.5f))
                .build();
        var overridden = EnvironmentAttributeMap.builder()
                .modify(EnvironmentAttributes.FOG_COLOR, AttributeModifier.override(), new Vector3f(0.5f, 0.5f, 0.5f))
                .build();
        LayerAtmosphere.requireOverride(biome, overridden, EnvironmentAttributes.FOG_COLOR);
        LayerAtmosphere.requireOverride(biome, multiplied, EnvironmentAttributes.FOG_START_DISTANCE);
        try {
            LayerAtmosphere.requireOverride(biome, multiplied, EnvironmentAttributes.FOG_COLOR);
        } catch (IllegalStateException e) {
            String message = Objects.requireNonNullElse(e.getMessage(), "");
            expect(
                    helper,
                    message.contains("deepcharter:multiplied") && message.contains("minecraft:visual/fog_color"),
                    "the failure should name the biome and the attribute, said: %s",
                    message);
            helper.succeed();
            return;
        }
        throw new AssertionError("a multiplied fog colour was accepted");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** The sky light factor the camera's probe reads at {@code position}, blending biomes as the client does. */
    private static float probe(ServerLevel level, Vec3 position) {
        EnvironmentAttributeProbe probe = new EnvironmentAttributeProbe();
        probe.tick(level, position);
        return probe.getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, 1f);
    }

    /** Runs {@code body} with both clocks pinned and the level's weather set, and puts them back on the same tick. */
    private static void at(ServerLevel level, Moment moment, Runnable body) {
        var server = level.getServer();
        Holder<WorldClock> sky = server.registryAccess()
                .lookupOrThrow(Registries.WORLD_CLOCK)
                .getOrThrow(ResourceKey.create(Registries.WORLD_CLOCK, SKY));
        Holder<WorldClock> gameplay = level.dimensionType().defaultClock().orElseThrow();
        float rain = level.getRainLevel(1f);
        float thunder = level.getThunderLevel(1f);
        try {
            level.setRainLevel(moment.thunder());
            level.setThunderLevel(moment.thunder());
            TestClocks.paused(
                    server,
                    sky,
                    moment.skyTicks(),
                    () -> TestClocks.paused(server, gameplay, moment.gameplayTicks(), () -> {
                        level.environmentAttributes().invalidateTickCache();
                        body.run();
                    }));
        } finally {
            level.setRainLevel(rain);
            level.setThunderLevel(thunder);
            level.environmentAttributes().invalidateTickCache();
        }
    }

    /** The zones of both layers, each with the middle of its rows; the biome is the one the zone table names, which the level must hold. */
    private static List<Zone> zones(GameTestHelper helper, ServerLevel level) {
        List<Zone> zones = new ArrayList<>();
        for (int layer = 1; layer <= CampaignBands.LAYERS.size(); layer++) {
            CampaignBands.Band band = CampaignBands.layer(layer);
            List<Holder<Biome>> named = source(level).layers().get(layer - 1).biomes();
            for (int index = 0; index < Zones.COUNT; index++) {
                Zones.Span span = Zones.span(band.minY(), band.height(), index);
                BlockPos middle = new BlockPos(0, (span.low() + span.high()) / 2, 0);
                Identifier wanted = id(named.get(index));
                expect(
                        helper,
                        id(level.getBiome(middle)).equals(wanted),
                        "the middle of layer %s zone %s should be in %s",
                        layer,
                        index,
                        wanted);
                zones.add(new Zone(layer, wanted, middle));
            }
        }
        return zones;
    }

    private static Biome biome(ServerLevel level, Zone zone) {
        return level.registryAccess()
                .lookupOrThrow(Registries.BIOME)
                .getValueOrThrow(ResourceKey.create(Registries.BIOME, zone.biome()));
    }

    /** What a biome says about an attribute: its own value, or the attribute's default when it sets none. */
    private static <Value> Value wanted(Biome biome, EnvironmentAttribute<Value> attribute) {
        return biome.getAttributes().applyModifier(attribute, attribute.defaultValue());
    }

    private static Set<EnvironmentAttribute<?>> timelineAttributes(ServerLevel level) {
        Set<EnvironmentAttribute<?>> attributes = new TreeSet<>(Comparator.comparing(CampaignAtmosphereTest::name));
        level.dimensionType()
                .timelines()
                .forEach(timeline -> attributes.addAll(timeline.value().attributes()));
        return attributes;
    }

    private static BandBiomeSource source(ServerLevel level) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        return (BandBiomeSource) Objects.requireNonNull(
                generator.getBiomeSource() instanceof BandBiomeSource ? generator.getBiomeSource() : null,
                "the sample level has no band biome source");
    }

    private static List<Holder<Biome>> layerBiomes(ServerLevel level) {
        return source(level).layers().stream()
                .flatMap(layer -> layer.biomes().stream())
                .toList();
    }

    private static Identifier id(Holder<Biome> biome) {
        return biome.unwrapKey().orElseThrow().identifier();
    }

    /** The audio is not compared: the flat overworld's biome has its own music and sounds. {@link #noSurfaceBiomeSetsWhatATimelineSets} covers audio. */
    private static boolean isVisual(EnvironmentAttribute<?> attribute) {
        return name(attribute).startsWith("minecraft:visual/");
    }

    /** The channels (0 red, 1 green, 2 blue) of {@code colour} that are exactly 0, the only ones the client's brightening skips. */
    private static Set<Integer> zeroChannels(Vector3fc colour) {
        Set<Integer> zeros = new TreeSet<>();
        for (int c = 0; c < 3; c++) {
            if (colour.get(c) == 0f) {
                zeros.add(c);
            }
        }
        return zeros;
    }

    private static String hex(Vector3fc colour) {
        return "#%02x%02x%02x"
                .formatted(Math.round(colour.x() * 255), Math.round(colour.y() * 255), Math.round(colour.z() * 255));
    }

    private static String name(EnvironmentAttribute<?> attribute) {
        return String.valueOf(BuiltInRegistries.ENVIRONMENT_ATTRIBUTE.getKey(attribute));
    }

    private static void expectEqual(GameTestHelper helper, String what, Object expected, Object actual) {
        expect(helper, Objects.equals(expected, actual), "%s should read %s, read %s", what, expected, actual);
    }

    private static void expectNear(GameTestHelper helper, String what, float expected, float actual, float tolerance) {
        expect(
                helper,
                Math.abs(expected - actual) <= tolerance,
                "%s: wanted %s within %s, was %s",
                what,
                expected,
                tolerance,
                actual);
    }
}
