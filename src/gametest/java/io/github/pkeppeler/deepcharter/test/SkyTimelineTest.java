package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import org.joml.Vector3fc;

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

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * Server GameTests for #239 (art-direction.md section 1): the overworld sky is a visual timeline on its own world clock, dusk at its
 * brightest and near-black at its darkest, and it does not touch the gameplay clock or the layers. The timeline is evaluated at a
 * sky-clock tick of the test's choosing through a {@link ClockManager} that only the test holds, so no world clock is written and a
 * test running beside this one is not affected. The expected colours are written out here, not read from the timeline.
 */
public class SkyTimelineTest {
	private static final Identifier SKY = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "sky");
	private static final long DUSK = 0;
	private static final long NIGHT = 144_000;
	private static final long PERIOD = 288_000;
	private static final float EPSILON = 0.002f;

	/** One reading of the timeline at a sky-clock tick. */
	private record Sample(long skyTicks, float red, float green, float blue, float dustProbability) {
		float luminance() {
			return 0.2126f * red + 0.7152f * green + 0.0722f * blue;
		}
	}

	@GameTest
	public void theSkySwingsFromDuskToNearBlackAndBack(GameTestHelper helper) {
		Timeline timeline = timeline(helper);
		List<Sample> up = new ArrayList<>();
		for (long ticks : new long[] {DUSK, NIGHT / 4, NIGHT / 2, NIGHT * 3 / 4, NIGHT}) {
			up.add(sample(timeline, ticks));
		}
		List<Sample> back = new ArrayList<>(List.of(up.getLast()));
		for (long ticks : new long[] {NIGHT + NIGHT / 2, PERIOD - 1}) {
			back.add(sample(timeline, ticks));
		}
		Sample dusk = up.getFirst();
		Sample night = up.getLast();
		expectColour(helper, "dusk sky", dusk, 0x4A, 0x28, 0x20);
		expectColour(helper, "night sky", night, 0x07, 0x05, 0x0A);
		if (Math.abs(dusk.dustProbability() - 0.05f) > EPSILON || Math.abs(night.dustProbability() - 0.02f) > EPSILON) {
			throw helper.assertionException("the dust should thin from 0.05 at dusk to 0.02 at night, was %s and %s", dusk.dustProbability(), night.dustProbability());
		}
		expectMonotonic(helper, "darken all the way to night", up, -1);
		expectMonotonic(helper, "brighten from night back to dusk", back, 1);
		Sample last = back.getLast();
		if (Math.abs(last.luminance() - dusk.luminance()) > EPSILON) {
			throw helper.assertionException("the swing should close: the last tick of the period is %s, the first is %s", last.luminance(), dusk.luminance());
		}
		helper.succeed();
	}

	/** The timeline names visual attributes only, and its own clock: gameplay (beds, spawning, light level) stays with the day. */
	@GameTest
	public void theTimelineIsVisualOnlyAndOnItsOwnClock(GameTestHelper helper) {
		Timeline timeline = timeline(helper);
		for (EnvironmentAttribute<?> attribute : timeline.attributes()) {
			Identifier id = BuiltInRegistries.ENVIRONMENT_ATTRIBUTE.getKey(attribute);
			if (!id.getPath().startsWith("visual/")) {
				throw helper.assertionException("the sky timeline should set visual attributes only, but it sets %s", id);
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

	/** A layer has no sky: it does not run the timeline, and still reads its own fog, sky colour and a sky light factor of 0. */
	@GameTest(dimension = "deepcharter:layer_1")
	public void aLayerIsNotTouchedByTheSky(GameTestHelper helper) {
		ServerLevel layer = helper.getLevel();
		Timeline timeline = timeline(helper);
		if (layer.dimensionType().timelines().stream().anyMatch(held -> held.value() == timeline)) {
			throw helper.assertionException("layer_1 should not run the sky timeline");
		}
		expectEqual(helper, "layer_1 sky colour", EnvironmentAttributes.SKY_COLOR.defaultValue(), layer.environmentAttributes().getDimensionValue(EnvironmentAttributes.SKY_COLOR));
		expectEqual(helper, "layer_1 fog colour", EnvironmentAttributes.FOG_COLOR.defaultValue(), layer.environmentAttributes().getDimensionValue(EnvironmentAttributes.FOG_COLOR));
		expectEqual(helper, "layer_1 sky light factor", 0.0f, layer.environmentAttributes().getDimensionValue(EnvironmentAttributes.SKY_LIGHT_FACTOR));
		expectEqual(helper, "layer_1 gameplay sky light level", 4.0f, layer.environmentAttributes().getDimensionValue(EnvironmentAttributes.SKY_LIGHT_LEVEL));
		helper.succeed();
	}

	private static Sample sample(Timeline timeline, long skyTicks) {
		ClockManager clocks = clock -> new FixedClock(skyTicks);
		Vector3fc colour = timeline.createTrackSampler(EnvironmentAttributes.SKY_COLOR, clocks).applyTimeBased(EnvironmentAttributes.SKY_COLOR.defaultValue(), 0);
		List<AmbientParticle> dust = timeline.createTrackSampler(EnvironmentAttributes.AMBIENT_PARTICLES, clocks).applyTimeBased(List.of(), 0);
		return new Sample(skyTicks, colour.x(), colour.y(), colour.z(), dust.getFirst().probability());
	}

	private static Timeline timeline(GameTestHelper helper) {
		return helper.getLevel().registryAccess().lookupOrThrow(Registries.TIMELINE).getOrThrow(ResourceKey.create(Registries.TIMELINE, SKY)).value();
	}

	private static Holder<WorldClock> skyClock(GameTestHelper helper) {
		return helper.getLevel().registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(ResourceKey.create(Registries.WORLD_CLOCK, SKY));
	}

	private static void expectMonotonic(GameTestHelper helper, String what, List<Sample> samples, int direction) {
		for (int i = 1; i < samples.size(); i++) {
			if (Integer.signum(Float.compare(samples.get(i).luminance(), samples.get(i - 1).luminance())) != direction) {
				throw helper.assertionException("the sky should %s, but at %s it is %s after %s at %s", what,
						samples.get(i).skyTicks(), samples.get(i).luminance(), samples.get(i - 1).luminance(), samples.get(i - 1).skyTicks());
			}
		}
	}

	private static void expectColour(GameTestHelper helper, String what, Sample sample, int red, int green, int blue) {
		if (Math.abs(sample.red() - red / 255f) > EPSILON || Math.abs(sample.green() - green / 255f) > EPSILON
				|| Math.abs(sample.blue() - blue / 255f) > EPSILON) {
			throw helper.assertionException("%s should be #%02X%02X%02X, was (%s, %s, %s) of 1", what, red, green, blue, sample.red(), sample.green(), sample.blue());
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
