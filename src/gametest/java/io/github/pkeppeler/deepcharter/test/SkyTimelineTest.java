package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.AmbientParticle;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.clock.ClockInstance;
import net.minecraft.world.clock.ClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.timeline.Timeline;
import org.joml.Vector3fc;

/**
 * Server GameTests for #239 and #1606 (art-direction.md section 2): the overworld sky is a visual timeline on its own
 * world clock, held at the deep night with the dusk's fog distances, and it does not touch the gameplay clock or the
 * layers. The timeline is evaluated at a sky-clock tick of the test's choosing through a {@link ClockManager} that only
 * the test holds, so no world clock is written and a test running beside this one is not affected. The expected colours
 * are written out here, not read from the timeline.
 */
public class SkyTimelineTest {
    private static final String CAMPAIGN = "deepcharter:campaign_sample";
    private static final Identifier SKY = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "sky");
    private static final long NIGHT = 144_000;
    private static final long PERIOD = 288_000;
    private static final float EPSILON = 0.002f;

    /** Sky-clock ticks across the whole period, the playtest's 80993 among them: the sky must read the same at each. */
    private static final long[] ACROSS_THE_PERIOD = {0, 40_000, 80_993, NIGHT, 200_000, PERIOD - 1};

    /**
     * #1606: the surface holds the deep night's colours (sky, fog, sky light, stars) at every tick of the sky clock,
     * and the fog distances stay at the dusk values, so the dark does not close in. The clock still runs; only what it
     * reads stands still.
     */
    @GameTest
    public void theSkyHoldsTheDeepNightWithDuskFogDistances(GameTestHelper helper) {
        Timeline timeline = timeline(helper);
        for (long ticks : ACROSS_THE_PERIOD) {
            ClockManager clocks = clock -> new FixedClock(ticks);
            expectColour(helper, ticks, "sky", sample(timeline, EnvironmentAttributes.SKY_COLOR, clocks), 0x07050A);
            expectColour(helper, ticks, "fog", sample(timeline, EnvironmentAttributes.FOG_COLOR, clocks), 0x160C0A);
            expectColour(
                    helper,
                    ticks,
                    "sky light",
                    sample(timeline, EnvironmentAttributes.SKY_LIGHT_COLOR, clocks),
                    0x6A70B0);
            expectNear(
                    helper,
                    ticks,
                    "star brightness",
                    sample(timeline, EnvironmentAttributes.STAR_BRIGHTNESS, clocks),
                    1f);
            expectNear(
                    helper,
                    ticks,
                    "sky light factor",
                    sample(timeline, EnvironmentAttributes.SKY_LIGHT_FACTOR, clocks),
                    0.16f);
            expectNear(
                    helper,
                    ticks,
                    "fog start",
                    sample(timeline, EnvironmentAttributes.FOG_START_DISTANCE, clocks),
                    20f);
            expectNear(
                    helper, ticks, "fog end", sample(timeline, EnvironmentAttributes.FOG_END_DISTANCE, clocks), 150f);
            List<AmbientParticle> dust = timeline.createTrackSampler(EnvironmentAttributes.AMBIENT_PARTICLES, clocks)
                    .applyTimeBased(List.of(), 0);
            expectNear(helper, ticks, "dust", dust.getFirst().probability(), 0.02f);
        }
        helper.succeed();
    }

    /** The timeline names visual attributes only, and its own clock: gameplay (beds, spawning, light level) stays with the day. */
    @GameTest
    public void theTimelineIsVisualOnlyAndOnItsOwnClock(GameTestHelper helper) {
        Timeline timeline = timeline(helper);
        for (EnvironmentAttribute<?> attribute : timeline.attributes()) {
            Identifier id = Objects.requireNonNull(
                    BuiltInRegistries.ENVIRONMENT_ATTRIBUTE.getKey(attribute), "a registered attribute has a key");
            if (!id.getPath().startsWith("visual/")) {
                throw helper.assertionException(
                        "the sky timeline should set visual attributes only, but it sets %s", id);
            }
        }
        if (!timeline.clock().equals(skyClock(helper))) {
            throw helper.assertionException("the sky timeline should run on deepcharter:sky, not %s", timeline.clock());
        }
        ServerLevel overworld = helper.getLevel();
        if (timeline.clock().equals(overworld.dimensionType().defaultClock().orElseThrow())) {
            throw helper.assertionException("the sky clock and the gameplay clock should be two clocks");
        }
        if (!overworld.dimensionType().timelines().stream().anyMatch(held -> held.value() == timeline)) {
            throw helper.assertionException("the overworld should run the sky timeline");
        }
        helper.succeed();
    }

    /**
     * A layer has no sky: in the campaign level, which runs the sky timeline for its surface band, a position in a layer band reads a sky
     * light factor of 0 from its zone biome (#744, ADR 0062), so the sky does not light it.
     */
    @GameTest(environment = CampaignEnvironments.C, dimension = CAMPAIGN)
    public void aLayerBandIsNotTouchedByTheSky(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Timeline timeline = timeline(helper);
        if (level.dimensionType().timelines().stream().noneMatch(held -> held.value() == timeline)) {
            throw helper.assertionException("the campaign level should run the sky timeline for its surface band");
        }
        for (int layer = 1; layer <= 2; layer++) {
            expectEqual(
                    helper,
                    "layer " + layer + " sky light factor in a zone biome",
                    0.0f,
                    level.environmentAttributes()
                            .getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, TestLayers.pos(layer, 0, 20, 0)));
        }
        helper.succeed();
    }

    private static <V> V sample(Timeline timeline, EnvironmentAttribute<V> attribute, ClockManager clocks) {
        return timeline.createTrackSampler(attribute, clocks).applyTimeBased(attribute.defaultValue(), 0);
    }

    private static Timeline timeline(GameTestHelper helper) {
        return helper.getLevel()
                .registryAccess()
                .lookupOrThrow(Registries.TIMELINE)
                .getOrThrow(ResourceKey.create(Registries.TIMELINE, SKY))
                .value();
    }

    private static Holder<WorldClock> skyClock(GameTestHelper helper) {
        return helper.getLevel()
                .registryAccess()
                .lookupOrThrow(Registries.WORLD_CLOCK)
                .getOrThrow(ResourceKey.create(Registries.WORLD_CLOCK, SKY));
    }

    private static void expectColour(GameTestHelper helper, long ticks, String what, Vector3fc colour, int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        if (Math.abs(colour.x() - red / 255f) > EPSILON
                || Math.abs(colour.y() - green / 255f) > EPSILON
                || Math.abs(colour.z() - blue / 255f) > EPSILON) {
            throw helper.assertionException(
                    "at sky tick %s the %s colour should be #%06X, was (%s, %s, %s) of 1",
                    ticks, what, rgb, colour.x(), colour.y(), colour.z());
        }
    }

    private static void expectNear(GameTestHelper helper, long ticks, String what, float actual, float expected) {
        if (Math.abs(actual - expected) > EPSILON) {
            throw helper.assertionException(
                    "at sky tick %s the %s should be %s, was %s", ticks, what, expected, actual);
        }
    }

    private static void expectEqual(GameTestHelper helper, String what, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw helper.assertionException("%s should be %s, was %s", what, expected, actual);
        }
    }

    /** A clock that stands still at one tick. */
    private record FixedClock(long totalTicks) implements ClockInstance {
        @Override
        public float partialTick() {
            return 0;
        }

        @Override
        public float rate() {
            return 1;
        }

        @Override
        public boolean isPaused() {
            return true;
        }
    }
}
