package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.biome.Biome;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Server GameTests for #744 (campaign world plan, #214): a layer's fog, ambient light colour, sky light factor of 0, music and bed rules
 * come from its zone biomes and not from its dimension type, which has to be one type for every band of the campaign world (ADR 0059). A
 * biome value wins over a dimension value, so the game reads the same either way: these tests pin where the values live. The three
 * things a biome cannot set stay on the type and are listed in ADR 0059.
 */
public class LayerAtmosphereTest {
    /** The fog and light of each layer, written out here, not read from the data. */
    private record Atmosphere(float fogStart, float fogEnd, int ambientColour) {}

    private static final Map<Integer, Atmosphere> ATMOSPHERE = Map.of(
            1, new Atmosphere(8f, 64f, 0x1c1814),
            2, new Atmosphere(2f, 32f, 0x0c0a09));

    /** What the zone biomes set, and the type must not. */
    private static final List<EnvironmentAttribute<?>> MOVED = List.of(
            EnvironmentAttributes.FOG_START_DISTANCE,
            EnvironmentAttributes.FOG_END_DISTANCE,
            EnvironmentAttributes.AMBIENT_LIGHT_COLOR,
            EnvironmentAttributes.SKY_LIGHT_FACTOR,
            EnvironmentAttributes.BACKGROUND_MUSIC,
            EnvironmentAttributes.BED_RULE,
            EnvironmentAttributes.STRAW_BED_RULE);

    /** The biome at a quart in each third of the layer, by name: the zones top to bottom. */
    private static Map<Identifier, BlockPos> zones(ServerLevel level, int layer) {
        Map<Identifier, BlockPos> zones = new LinkedHashMap<>();
        int minY = level.getMinY();
        for (int y = minY; y < minY + level.getHeight(); y += 8) {
            BlockPos pos = new BlockPos(0, y, 0);
            Holder<Biome> biome = level.getBiome(pos);
            zones.putIfAbsent(biome.unwrapKey().orElseThrow().identifier(), pos);
        }
        expect(zones.size() == 3, "layer %s has zones %s, wanted 3", layer, zones.keySet());
        return zones;
    }

    @GameTest
    public void aLayerTypeSetsNoneOfTheValuesTheZoneBiomesSet(GameTestHelper helper) {
        for (int layer = 1; layer <= LayerChain.count(helper.getLevel().registryAccess()); layer++) {
            var attributes = TestLayers.level(helper, layer).dimensionType().attributes();
            for (EnvironmentAttribute<?> attribute : MOVED) {
                expect(
                        !attributes.contains(attribute),
                        "the dimension type of layer %s still sets %s; the zone biomes do",
                        layer,
                        attribute);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void everyZoneBiomeSetsItsLayersFogLightAndBedRules(GameTestHelper helper) {
        for (int layer = 1; layer <= LayerChain.count(helper.getLevel().registryAccess()); layer++) {
            Atmosphere wanted = Objects.requireNonNull(ATMOSPHERE.get(layer), "a layer has no atmosphere in this test");
            ServerLevel level = TestLayers.level(helper, layer);
            zones(level, layer).forEach((zone, pos) -> {
                var biome = level.getBiome(pos).value().getAttributes();
                expectEqual(
                        zone + " fog start",
                        wanted.fogStart(),
                        biome.applyModifier(EnvironmentAttributes.FOG_START_DISTANCE, -1f));
                expectEqual(
                        zone + " fog end",
                        wanted.fogEnd(),
                        biome.applyModifier(EnvironmentAttributes.FOG_END_DISTANCE, -1f));
                expectEqual(
                        zone + " sky light factor",
                        0f,
                        biome.applyModifier(EnvironmentAttributes.SKY_LIGHT_FACTOR, -1f));
                expect(
                        biome.contains(EnvironmentAttributes.FOG_COLOR),
                        "the zone %s sets its own fog colour (it did before #744)",
                        zone);
                Vector3fc ambient = biome.applyModifier(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, new Vector3f());
                expect(
                        sameColour(ambient, wanted.ambientColour()),
                        "the zone %s ambient light colour is %s, wanted #%06x",
                        zone,
                        ambient,
                        wanted.ambientColour());
                for (EnvironmentAttribute<BedRule> rule :
                        List.of(EnvironmentAttributes.BED_RULE, EnvironmentAttributes.STRAW_BED_RULE)) {
                    BedRule bed = biome.applyModifier(rule, BedRule.CAN_SLEEP_WHEN_DARK);
                    expect(
                            bed.canSleep() == BedRule.Rule.NEVER && bed.canSetSpawn() == BedRule.Rule.NEVER,
                            "the zone %s bed rule %s lets a player sleep or set a spawn: %s",
                            zone,
                            rule,
                            bed);
                }
            });
        }
        helper.succeed();
    }

    /** The value a player's client and the server read at a place in the layer is the biome's, through the level's attribute system. */
    @GameTest
    public void theLevelReadsTheBiomeValueAtEachZone(GameTestHelper helper) {
        for (int layer = 1; layer <= LayerChain.count(helper.getLevel().registryAccess()); layer++) {
            Atmosphere wanted = Objects.requireNonNull(ATMOSPHERE.get(layer), "a layer has no atmosphere in this test");
            ServerLevel level = TestLayers.level(helper, layer);
            zones(level, layer).forEach((zone, pos) -> {
                var read = level.environmentAttributes();
                expectEqual(
                        zone + " fog end at its place",
                        wanted.fogEnd(),
                        read.getValue(EnvironmentAttributes.FOG_END_DISTANCE, pos));
                expectEqual(
                        zone + " sky light factor at its place",
                        0f,
                        read.getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, pos));
                expect(
                        sameColour(
                                read.getValue(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, pos), wanted.ambientColour()),
                        "the zone %s reads ambient light colour %s",
                        zone,
                        read.getValue(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, pos));
            });
        }
        helper.succeed();
    }

    /** The three things a biome cannot set stay on the type; the cutover (#214) has to carry them another way. */
    @GameTest
    public void theThreeThingsABiomeCannotSetStayOnTheLayerType(GameTestHelper helper) {
        for (int layer = 1; layer <= LayerChain.count(helper.getLevel().registryAccess()); layer++) {
            var type = TestLayers.level(helper, layer).dimensionType();
            expect(type.ambientLight() > 0f, "layer %s type has no ambient_light", layer);
            expect(!type.hasSkyLight(), "layer %s type has skylight", layer);
            expect(
                    type.cardinalLightType() == CardinalLighting.Type.NETHER,
                    "layer %s type is not nether cardinal light",
                    layer);
        }
        helper.succeed();
    }

    private static boolean sameColour(Vector3fc colour, int rgb) {
        return Math.abs(colour.x() - ((rgb >> 16) & 0xFF) / 255f) < 0.002f
                && Math.abs(colour.y() - ((rgb >> 8) & 0xFF) / 255f) < 0.002f
                && Math.abs(colour.z() - (rgb & 0xFF) / 255f) < 0.002f;
    }

    private static void expectEqual(String what, Object expected, Object actual) {
        expect(expected.equals(actual), "%s should be %s, was %s", what, expected, actual);
    }

    @FormatMethod
    private static void expect(boolean condition, @FormatString String format, Object... args) {
        if (!condition) {
            throw new AssertionError(String.format(format, args));
        }
    }
}
