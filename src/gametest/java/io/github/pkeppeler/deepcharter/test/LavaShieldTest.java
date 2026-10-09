package io.github.pkeppeler.deepcharter.test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/**
 * Server GameTests for #288: a seated pilot is shielded from lava, so the hull is what lava takes; a player outside a pod burns
 * by vanilla's rules. Vanilla lava kills a survival player in about 2.6 s, so a few seconds in lava tell the two apart.
 */
public class LavaShieldTest {
	/** Longer than the 52 ticks in which vanilla lava kills a player at full health. */
	private static final int LAVA_TICKS = 80;
	private static final float EPSILON = 1e-3f;
	private static final Map<UUID, Integer> POD_TICKS = new ConcurrentHashMap<>();

	/** Pilots seen on fire at the end of a server tick: what a client would draw. A check inside a test runs mid-tick and misses it. */
	private static final Set<UUID> SEEN_BURNING = ConcurrentHashMap.newKeySet();

	static {
		PodEvents.AFTER_TICK.register(pod -> POD_TICKS.computeIfPresent(pod.getUUID(), (id, ticks) -> ticks + 1));
		ServerTickEvents.END_SERVER_TICK.register(server -> server.getPlayerList().getPlayers().stream()
				.filter(player -> player.getVehicle() instanceof PodEntity && player.getRemainingFireTicks() > 0)
				.forEach(player -> SEEN_BURNING.add(player.getUUID())));
	}

	@GameTest(maxTicks = LAVA_TICKS + 20)
	public void aSeatedPilotInLavaSurvivesAndIsNotOnFire(GameTestHelper helper) {
		Seated seated = seatedInLava(helper);
		helper.runAfterDelay(LAVA_TICKS, () -> {
			ServerPlayer player = seated.pilot.player();
			if (!player.isAlive() || player.getHealth() < player.getMaxHealth()) {
				throw failure(helper, "a seated pilot should take no lava damage, health is %s of %s, alive %s",
						player.getHealth(), player.getMaxHealth(), player.isAlive());
			}
			if (SEEN_BURNING.contains(player.getUUID())) {
				throw failure(helper, "a seated pilot in lava should never be on fire at the end of a tick");
			}
			seated.pod.discard();
			helper.succeed();
		});
	}

	@GameTest(maxTicks = LAVA_TICKS + 20)
	public void lavaDrainsTheHullOfAPodWithAPilotAtTheTunedRate(GameTestHelper helper) {
		Seated seated = seatedInLava(helper);
		helper.runAfterDelay(LAVA_TICKS, () -> {
			int ticks = POD_TICKS.get(seated.pod.getUUID());
			float expected = seated.pod.maxHull() - ticks * LayerTuning.DEFAULT.lavaHullPerSecond() / 20f;
			if (ticks < LAVA_TICKS || Math.abs(seated.pod.hull() - expected) > EPSILON) {
				throw failure(helper, "after %d ticks the hull should be %s, found %s", ticks, expected, seated.pod.hull());
			}
			if (seated.pilot.player().getVehicle() != seated.pod) {
				throw failure(helper, "the pilot should still be seated after %d ticks in lava", ticks);
			}
			seated.pod.discard();
			POD_TICKS.remove(seated.pod.getUUID());
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 60)
	public void thePodFlagsItsHullAsBurningOnlyWhileLavaTouchesIt(GameTestHelper helper) {
		floor(helper);
		BlockPos lava = new BlockPos(3, 2, 3);
		PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(3.5, 2, 3.5));
		helper.runAfterDelay(5, () -> {
			if (pod.hullBurning()) {
				throw failure(helper, "a pod clear of lava should not be burning");
			}
			helper.setBlock(lava, Blocks.LAVA);
		});
		helper.runAfterDelay(15, () -> {
			if (!pod.hullBurning()) {
				throw failure(helper, "a pod in lava should be burning");
			}
			// room-carver: removes a block this test placed itself in the overworld test structure, not layer rock
			helper.setBlock(lava, Blocks.AIR);
		});
		helper.runAfterDelay(25, () -> {
			if (pod.hullBurning()) {
				throw failure(helper, "a pod whose lava is gone should stop burning");
			}
			pod.discard();
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 40)
	public void aWreckInLavaIsNotBurningAndStaysSilent(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(new BlockPos(3, 2, 3), Blocks.LAVA);
		PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(3.5, 2, 3.5));
		pod.setHull(0f);
		helper.runAfterDelay(30, () -> {
			if (!Wrecks.isWreck(pod) || pod.hullBurning()) {
				throw failure(helper, "a wreck in lava should be a wreck and not flag a burning hull, wreck %s burning %s", Wrecks.isWreck(pod), pod.hullBurning());
			}
			pod.discard();
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 20)
	public void thePodIsFireImmune(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(3.5, 2, 3.5));
		if (!pod.fireImmune()) {
			throw failure(helper, "the pod should be fire-immune so it shows no flames");
		}
		pod.discard();
		helper.succeed();
	}

	@GameTest(maxTicks = LAVA_TICKS + 20)
	public void aPlayerOutsideAPodStillBurnsInLava(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(new BlockPos(3, 2, 3), Blocks.LAVA);
		MockPlayer walker = MockPlayers.join(helper, "Lava walker");
		walker.player().setGameMode(GameType.SURVIVAL);
		walker.teleportTo(helper.getLevel(), helper.absoluteVec(new Vec3(3.5, 2, 3.5)), 0f, 0f);
		helper.runAfterDelay(LAVA_TICKS, () -> {
			ServerPlayer player = walker.player();
			if (player.isAlive() && player.getHealth() >= player.getMaxHealth()) {
				throw failure(helper, "a player in lava with no pod should be hurt, health is %s of %s", player.getHealth(), player.getMaxHealth());
			}
			helper.succeed();
		});
	}

	private record Seated(PodEntity pod, MockPlayer pilot) {
	}

	/** A pod on a stone floor with lava in its own cell, and a survival pilot seated in it. */
	private static Seated seatedInLava(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(new BlockPos(3, 2, 3), Blocks.LAVA);
		PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(3.5, 2, 3.5));
		POD_TICKS.put(pod.getUUID(), 0);
		MockPlayer pilot = MockPlayers.join(helper, "Lava pilot");
		pilot.player().setGameMode(GameType.SURVIVAL);
		pilot.teleportTo(helper.getLevel(), pod.position(), 0f, 0f);
		if (!pilot.player().startRiding(pod, true, false)) {
			throw failure(helper, "the pilot could not board the pod");
		}
		return new Seated(pod, pilot);
	}

	private static void floor(GameTestHelper helper) {
		for (int x = 0; x <= 8; x++) {
			for (int z = 0; z <= 6; z++) {
				helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
			}
		}
	}

	// assertionException(String, Object...) leaves the placeholders unfilled in the report.
	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}
}
