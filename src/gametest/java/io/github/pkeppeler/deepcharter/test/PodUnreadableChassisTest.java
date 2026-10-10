package io.github.pkeppeler.deepcharter.test;

import java.util.List;
import java.util.Objects;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.ore.GasHazard;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.wreck.WreckRegistry;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/**
 * Server GameTests for #405: a pod saved with a chassis id this build does not know loads as an inert placeholder that keeps its saved
 * data, instead of crashing the load, and the code that looks for pods skips it.
 */
public class PodUnreadableChassisTest {
	private static final String GONE = "deepcharter_gone:vanished";
	private static final List<String> POD_KEYS = List.of("chassis", "hull", "fuel", "stranded", "cargo", "cargo_version");

	/** The saved data of a normal pod, with distinctive values, and its chassis id replaced by one nothing registers. */
	private static CompoundTag savedWithUnknownChassis(ServerLevel level) {
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.setHull(11.5f);
		pod.setFuel(3.25f);
		pod.setStranded(true);
		CompoundTag saved = save(level, pod);
		saved.putString("chassis", GONE);
		return saved;
	}

	private static CompoundTag save(ServerLevel level, PodEntity pod) {
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		pod.saveWithoutId(output);
		return output.buildResult();
	}

	private static PodEntity load(ServerLevel level, CompoundTag saved) {
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), saved));
		return pod;
	}

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}

	@GameTest
	public void aPodSavedWithAnUnknownChassisLoadsAndKeepsItsData(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		CompoundTag saved = savedWithUnknownChassis(level);
		PodEntity loaded = load(level, saved);
		if (!loaded.isUnreadable()) {
			throw failure(helper, "a pod with the chassis %s must load as unreadable", GONE);
		}
		CompoundTag again = save(level, loaded);
		for (String key : POD_KEYS) {
			if (!Objects.equals(saved.get(key), again.get(key))) {
				throw failure(helper, "'%s' must be written back as it was read: was %s, now %s", key, saved.get(key), again.get(key));
			}
		}
		// A second round trip is stable too, and a normal pod is not touched by any of this.
		PodEntity twice = load(level, again);
		if (!twice.isUnreadable() || !save(level, twice).get("chassis").equals(saved.get("chassis"))) {
			throw failure(helper, "the unreadable pod must stay unreadable and keep its chassis id across a second round trip");
		}
		if (load(level, save(level, PodRegistry.POD.create(level, EntitySpawnReason.COMMAND))).isUnreadable()) {
			throw failure(helper, "a pod with a known chassis must load normally");
		}
		helper.succeed();
	}

	@GameTest
	public void aPodWithNoChassisIdIsKeptToo(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		CompoundTag saved = savedWithUnknownChassis(level);
		saved.remove("chassis");
		PodEntity loaded = load(level, saved);
		if (!loaded.isUnreadable() || save(level, loaded).contains("chassis")) {
			throw failure(helper, "a pod saved with no chassis id must load as unreadable and be written back without one");
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 60)
	public void anUnreadablePodInTheWorldDoesNotTickOrBecomeAWreck(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		CompoundTag saved = savedWithUnknownChassis(level);
		// A hull of 0 would make the load hook turn a normal pod into a wreck; the placeholder is left alone.
		saved.putFloat("hull", 0f);
		PodEntity pod = load(level, saved);
		Vec3 start = helper.absoluteVec(new Vec3(2.5, 6, 2.5));
		pod.setPos(start);
		level.addFreshEntity(pod);
		PodEntity normal = helper.spawn(PodRegistry.POD, 4, 6, 4);
		Vec3 normalStart = normal.position();
		helper.runAfterDelay(30, () -> {
			try {
				if (!pod.position().equals(start) || !pod.getDeltaMovement().equals(Vec3.ZERO)) {
					throw failure(helper, "an unreadable pod must not tick: it moved from %s to %s", start, pod.position());
				}
				if (normal.position().equals(normalStart)) {
					throw failure(helper, "the control pod should have fallen: the test would not notice a pod that ticks");
				}
				if (pod.getAttached(WreckRegistry.STATE) != null) {
					throw failure(helper, "an unreadable pod must not be made a wreck on load");
				}
				if (helper.makeMockPlayer(GameType.SURVIVAL).startRiding(pod)) {
					throw failure(helper, "an unreadable pod must take no rider");
				}
				helper.succeed();
			} finally {
				pod.discard();
				normal.discard();
			}
		});
	}

	@GameTest
	public void codeThatLooksForPodsSkipsAnUnreadableOne(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos spot = helper.absolutePos(new BlockPos(2, 2, 2));
		PodEntity unreadable = load(level, savedWithUnknownChassis(level));
		unreadable.setPos(Vec3.atBottomCenterOf(spot));
		level.addFreshEntity(unreadable);
		PodEntity normal = helper.spawn(PodRegistry.POD, 3, 2, 2);
		try {
			List<PodEntity> parked = Terminals.parkedPods(level, spot);
			if (parked.contains(unreadable) || !parked.contains(normal)) {
				throw failure(helper, "the terminal must list the normal pod and not the unreadable one, got %s", parked);
			}
			if (Wrecks.isWreck(unreadable)) {
				throw failure(helper, "an unreadable pod is not a wreck, so no hangar or handbook code takes it for one");
			}
			if (PodTowing.refusal(normal, unreadable).orElse(null) != PodTowing.Refusal.UNREADABLE
					|| PodTowing.refusal(unreadable, normal).orElse(null) != PodTowing.Refusal.UNREADABLE) {
				throw failure(helper, "towing must refuse an unreadable pod either way round");
			}
			float hull = unreadable.hull();
			float normalHull = normal.hull();
			GasHazard.vent(level, spot);
			if (normal.hull() == normalHull) {
				throw failure(helper, "the gas vent should have hurt the control pod: the test would not notice a pod it hurts");
			}
			if (unreadable.hull() != hull) {
				throw failure(helper, "a gas vent must not touch an unreadable pod");
			}
			helper.succeed();
		} finally {
			unreadable.discard();
			normal.discard();
		}
	}
}
