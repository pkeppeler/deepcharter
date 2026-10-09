package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import org.joml.Vector3fc;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.AmbientParticle;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * Server GameTest for #239 (art-direction.md section 1): the overworld sky is a visual timeline on its own world clock, dusk at its
 * brightest and near-black at its darkest, and it does not touch the gameplay clock. The expected colours are written out here, not
 * read from the timeline, so a changed look fails.
 */
public class SkyTimelineTest {
	private static final long DUSK = 0;
	private static final long NIGHT = 144_000;
	private static final long PERIOD = 288_000;
	private static final long GAMEPLAY_NOON = 6_000;
	private static final long GAMEPLAY_MIDNIGHT = 18_000;
	/** Ticks between setting a clock and reading the sky: the attribute system caches its values for the tick. */
	private static final int SETTLE_TICKS = 2;
	private static final float EPSILON = 0.002f;

	/** A moment to read: where the sky clock and the gameplay clock stand. */
	private record Moment(long skyTicks, long gameplayTicks) {
	}

	private record Sample(Moment moment, float red, float green, float blue, float dustProbability, float gameplaySkyLight, long gameplayClock) {
		float luminance() {
			return 0.2126f * red + 0.7152f * green + 0.0722f * blue;
		}
	}

	private record Clocks(ServerClockManager manager, Holder<WorldClock> sky, Holder<WorldClock> gameplay) {
	}

	@GameTest
	public void theSkySwingsFromDuskToNearBlackAndBackWithoutTouchingTheGameplayClock(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		Clocks clocks = new Clocks(server.clockManager(),
				server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK)
						.getOrThrow(ResourceKey.create(Registries.WORLD_CLOCK, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "sky"))),
				server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD));

		long skyBefore = clocks.manager().getInstance(clocks.sky()).totalTicks();
		boolean skyPausedBefore = clocks.manager().getInstance(clocks.sky()).isPaused();
		long gameplayBefore = clocks.manager().getInstance(clocks.gameplay()).totalTicks();
		boolean gameplayPausedBefore = clocks.manager().getInstance(clocks.gameplay()).isPaused();
		clocks.manager().setPaused(clocks.sky(), true);
		clocks.manager().setPaused(clocks.gameplay(), true);

		List<Moment> moments = List.of(
				new Moment(DUSK, GAMEPLAY_NOON), new Moment(NIGHT / 4, GAMEPLAY_NOON), new Moment(NIGHT / 2, GAMEPLAY_NOON),
				new Moment(NIGHT * 3 / 4, GAMEPLAY_NOON), new Moment(NIGHT, GAMEPLAY_NOON), new Moment(NIGHT + NIGHT / 2, GAMEPLAY_NOON),
				new Moment(PERIOD - 1, GAMEPLAY_NOON), new Moment(DUSK, GAMEPLAY_MIDNIGHT), new Moment(NIGHT, GAMEPLAY_MIDNIGHT));
		sampleNext(helper, level, clocks, moments, new ArrayList<>(), () -> {
			clocks.manager().setPaused(clocks.sky(), skyPausedBefore);
			clocks.manager().setPaused(clocks.gameplay(), gameplayPausedBefore);
			clocks.manager().setTotalTicks(clocks.sky(), skyBefore);
			clocks.manager().setTotalTicks(clocks.gameplay(), gameplayBefore);
		});
	}

	private static void sampleNext(GameTestHelper helper, ServerLevel level, Clocks clocks, List<Moment> moments, List<Sample> samples,
			Runnable restore) {
		if (samples.size() == moments.size()) {
			try {
				check(helper, samples);
			} finally {
				restore.run();
			}
			helper.succeed();
			return;
		}
		Moment moment = moments.get(samples.size());
		clocks.manager().setTotalTicks(clocks.sky(), moment.skyTicks());
		clocks.manager().setTotalTicks(clocks.gameplay(), moment.gameplayTicks());
		helper.runAfterDelay(SETTLE_TICKS, () -> {
			try {
				Vector3fc colour = level.environmentAttributes().getDimensionValue(EnvironmentAttributes.SKY_COLOR);
				List<AmbientParticle> dust = level.environmentAttributes().getDimensionValue(EnvironmentAttributes.AMBIENT_PARTICLES);
				samples.add(new Sample(moment, colour.x(), colour.y(), colour.z(), dust.getFirst().probability(),
						level.environmentAttributes().getDimensionValue(EnvironmentAttributes.SKY_LIGHT_LEVEL),
						clocks.manager().getInstance(clocks.gameplay()).totalTicks()));
			} catch (RuntimeException | AssertionError e) {
				restore.run();
				throw e;
			}
			sampleNext(helper, level, clocks, moments, samples, restore);
		});
	}

	private static void check(GameTestHelper helper, List<Sample> samples) {
		Sample dusk = samples.get(0);
		Sample night = samples.get(4);
		expectColour(helper, "dusk sky", dusk, 0x4A, 0x28, 0x20);
		expectColour(helper, "night sky", night, 0x07, 0x05, 0x0A);
		if (Math.abs(dusk.dustProbability() - 0.05f) > EPSILON || Math.abs(night.dustProbability() - 0.02f) > EPSILON) {
			throw helper.assertionException("the dust should thin from 0.05 at dusk to 0.02 at night, was %s and %s", dusk.dustProbability(), night.dustProbability());
		}
		for (int i = 1; i <= 4; i++) {
			if (samples.get(i).luminance() >= samples.get(i - 1).luminance()) {
				throw helper.assertionException("the sky should darken all the way to night, but at %s it is %s after %s at %s",
						samples.get(i).moment().skyTicks(), samples.get(i).luminance(), samples.get(i - 1).luminance(), samples.get(i - 1).moment().skyTicks());
			}
		}
		for (int i = 5; i <= 6; i++) {
			if (samples.get(i).luminance() <= samples.get(i - 1).luminance()) {
				throw helper.assertionException("the sky should brighten from night back to dusk, but at %s it is %s after %s at %s",
						samples.get(i).moment().skyTicks(), samples.get(i).luminance(), samples.get(i - 1).luminance(), samples.get(i - 1).moment().skyTicks());
			}
		}
		Sample last = samples.get(6);
		if (Math.abs(last.luminance() - dusk.luminance()) > EPSILON) {
			throw helper.assertionException("the swing should close: the last tick of the period is %s, the first is %s", last.luminance(), dusk.luminance());
		}
		for (Sample sample : samples) {
			if (sample.gameplayClock() != sample.moment().gameplayTicks()) {
				throw helper.assertionException("the gameplay clock must not follow the sky: set to %s, read %s", sample.moment().gameplayTicks(), sample.gameplayClock());
			}
		}
		// The gameplay light level follows the gameplay clock alone: the same at both sky extremes, and darker at gameplay midnight.
		Sample noonAtDusk = samples.get(0);
		Sample noonAtNight = samples.get(4);
		Sample midnightAtDusk = samples.get(7);
		Sample midnightAtNight = samples.get(8);
		if (noonAtDusk.gameplaySkyLight() != noonAtNight.gameplaySkyLight() || midnightAtDusk.gameplaySkyLight() != midnightAtNight.gameplaySkyLight()) {
			throw helper.assertionException("the gameplay sky light level must not follow the sky: noon %s and %s, midnight %s and %s",
					noonAtDusk.gameplaySkyLight(), noonAtNight.gameplaySkyLight(), midnightAtDusk.gameplaySkyLight(), midnightAtNight.gameplaySkyLight());
		}
		if (midnightAtDusk.gameplaySkyLight() >= noonAtDusk.gameplaySkyLight()) {
			throw helper.assertionException("the gameplay day must still darken at night (beds, spawning): noon %s, midnight %s",
					noonAtDusk.gameplaySkyLight(), midnightAtDusk.gameplaySkyLight());
		}
	}

	private static void expectColour(GameTestHelper helper, String what, Sample sample, int red, int green, int blue) {
		if (Math.abs(sample.red() - red / 255f) > EPSILON || Math.abs(sample.green() - green / 255f) > EPSILON
				|| Math.abs(sample.blue() - blue / 255f) > EPSILON) {
			throw helper.assertionException("%s should be #%02X%02X%02X, was (%s, %s, %s) of 1", what, red, green, blue, sample.red(), sample.green(), sample.blue());
		}
	}
}
