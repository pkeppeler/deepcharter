package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodData;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/** Server GameTests for pod spawn, mount, dismount, seats and save/load. */
public class PodShellTest {
	private static final Input SNEAK = new Input(false, false, false, false, false, true, false);

	@GameTest
	public void podSpawnsWithMoleHitboxAndFullGauges(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		try {
			if (!pod.isAlive()) {
				throw helper.assertionException("the pod is not alive after spawning");
			}
			if (pod.chassis() != Chassis.MOLE) {
				throw helper.assertionException("a new pod should be a Mole, got %s", pod.chassis());
			}
			AABB box = pod.getBoundingBox();
			if (Math.abs(box.getXsize() - 1.9) > 1e-4 || Math.abs(box.getYsize() - 1.9) > 1e-4
					|| Math.abs(box.getZsize() - 1.9) > 1e-4) {
				throw helper.assertionException("the Mole hitbox should be 1.9 x 1.9 x 1.9, got %s", box);
			}
			if (pod.hull() != PodTuning.DEFAULT.shell().fullHull() || pod.fuel() != PodTuning.DEFAULT.shell().fullFuel()) {
				throw helper.assertionException("a new pod should have full hull and fuel, got %s and %s", pod.hull(), pod.fuel());
			}
			helper.succeed();
		} finally {
			pod.discard();
		}
	}

	@GameTest
	public void useMountsTheRider(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		MockPlayer mock = MockPlayers.join(helper.getLevel().getServer(), "pod-rider");
		try {
			InteractionResult result = pod.interact(mock.player(), InteractionHand.MAIN_HAND, Vec3.ZERO);
			if (!result.consumesAction()) {
				throw helper.assertionException("using the pod should consume the action, got %s", result);
			}
			if (mock.player().getVehicle() != pod || !pod.getPassengers().contains(mock.player())) {
				throw helper.assertionException("the player should be riding the pod");
			}
			if (!pod.getBoundingBox().inflate(0.5).contains(mock.player().position())) {
				throw helper.assertionException("the seat should be inside the pod, rider at %s, pod box %s",
						mock.player().position(), pod.getBoundingBox());
			}
			helper.succeed();
		} finally {
			mock.leave();
			pod.discard();
		}
	}

	@GameTest
	public void sneakDismounts(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		MockPlayer mock = MockPlayers.join(helper.getLevel().getServer(), "pod-dismounter");
		pod.interact(mock.player(), InteractionHand.MAIN_HAND, Vec3.ZERO);
		if (mock.player().getVehicle() != pod) {
			mock.leave();
			pod.discard();
			throw helper.assertionException("the player did not mount before the dismount check");
		}
		mock.setInput(SNEAK);
		helper.runAfterDelay(5, () -> {
			try {
				if (mock.player().getVehicle() != null || !pod.getPassengers().isEmpty()) {
					throw helper.assertionException("sneaking should dismount the rider");
				}
			} finally {
				mock.leave();
				pod.discard();
			}
			helper.succeed();
		});
	}

	@GameTest
	public void moleHasOneSeat(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer first = MockPlayers.join(server, "pod-first");
		MockPlayer second = MockPlayers.join(server, "pod-second");
		try {
			pod.interact(first.player(), InteractionHand.MAIN_HAND, Vec3.ZERO);
			InteractionResult result = pod.interact(second.player(), InteractionHand.MAIN_HAND, Vec3.ZERO);
			if (second.player().getVehicle() != null) {
				throw helper.assertionException("a second player must not fit in a one-seat pod");
			}
			if (result.consumesAction()) {
				throw helper.assertionException("using a full pod should not consume the action, got %s", result);
			}
			if (second.player().startRiding(pod)) {
				throw helper.assertionException("startRiding should refuse a second rider");
			}
			if (pod.getPassengers().size() != 1 || pod.getPassengers().getFirst() != first.player()) {
				throw helper.assertionException("the pod should carry only the first rider, got %s", pod.getPassengers());
			}
			helper.succeed();
		} finally {
			first.leave();
			second.leave();
			pod.discard();
		}
	}

	@GameTest
	public void saveAndLoadKeepsPodData(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		pod.setHull(37.5f);
		pod.setFuel(12.25f);
		pod.setStranded(true);
		pod.setCargoUsed(7);
		pod.setCargoMass(41.5f);
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		pod.saveWithoutId(output);
		pod.discard();

		Entity loaded = EntityType.create(
				PodRegistry.POD,
				TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), output.buildResult()),
				level,
				EntitySpawnReason.LOAD).orElseThrow(() -> helper.assertionException("the saved pod did not load"));
		try {
			if (!(loaded instanceof PodEntity copy)) {
				throw helper.assertionException("loaded a %s instead of a pod", loaded);
			}
			if (copy.chassis() != Chassis.MOLE || copy.hull() != 37.5f || copy.fuel() != 12.25f || !copy.stranded()
					|| copy.cargoUsed() != 7 || copy.cargoMass() != 41.5f) {
				throw helper.assertionException("pod data did not survive save and load: hull %s fuel %s stranded %s cargo %s/%s",
						copy.hull(), copy.fuel(), copy.stranded(), copy.cargoUsed(), copy.cargoMass());
			}
			helper.succeed();
		} finally {
			loaded.discard();
		}
	}

	@GameTest
	public void everyDataFieldIsDeclaredInPodData(GameTestHelper helper) {
		// Data ids are assigned in class-init order: one class, so client and server cannot disagree.
		for (var field : PodEntity.class.getDeclaredFields()) {
			if (field.getType() == EntityDataAccessor.class) {
				throw helper.assertionException("PodEntity declares synced data %s; declare it in PodData", field.getName());
			}
		}
		helper.succeed();
	}

	@GameTest
	public void spawnCommandCreatesAPodAtTheSource(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Vec3 at = helper.absoluteVec(new Vec3(3, 2, 3));
		var source = level.getServer().createCommandSourceStack().withLevel(level).withPosition(at);
		level.getServer().getCommands().performPrefixedCommand(source, "deepcharter pod spawn");
		var pods = level.getEntities(PodRegistry.POD, new AABB(at, at).inflate(1), pod -> true);
		try {
			if (pods.size() != 1) {
				throw helper.assertionException("/deepcharter pod spawn should create one pod at the source, found %d", pods.size());
			}
			helper.succeed();
		} finally {
			pods.forEach(Entity::discard);
		}
	}
}
