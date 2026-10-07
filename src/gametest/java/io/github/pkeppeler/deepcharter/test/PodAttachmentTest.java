package io.github.pkeppeler.deepcharter.test;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodAttachments;
import io.github.pkeppeler.deepcharter.pod.PodAttachments.Example;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/** Server GameTests for the pod attachment pattern: defaults, versioning, save/load and a breach crossing. */
public class PodAttachmentTest {
	@GameTest
	public void aNewPodHasTheDefaultAttachment(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		try {
			if (!pod.getAttachedOrCreate(PodAttachments.EXAMPLE).equals(Example.DEFAULT)) {
				throw failure(helper, "a new pod should carry %s, got %s", Example.DEFAULT, pod.getAttached(PodAttachments.EXAMPLE));
			}
			helper.succeed();
		} finally {
			pod.discard();
		}
	}

	@GameTest
	public void theAttachmentSurvivesSaveAndLoad(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		pod.setAttached(PodAttachments.EXAMPLE, new Example(Example.CURRENT_VERSION, 42));
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		pod.saveWithoutId(output);
		pod.discard();

		Entity loaded = EntityType.create(PodRegistry.POD,
				TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), output.buildResult()),
				level, EntitySpawnReason.LOAD).orElseThrow(() -> failure(helper, "the saved pod did not load"));
		try {
			Example example = loaded.getAttached(PodAttachments.EXAMPLE);
			if (example == null || example.counter() != 42 || example.version() != Example.CURRENT_VERSION) {
				throw failure(helper, "the attachment did not survive save and load, got %s", example);
			}
			helper.succeed();
		} finally {
			loaded.discard();
		}
	}

	@GameTest
	public void aVersionTheBuildDoesNotKnowIsRefused(GameTestHelper helper) {
		JsonObject future = new JsonObject();
		future.addProperty("version", Example.CURRENT_VERSION + 1);
		future.addProperty("counter", 1);
		if (!Example.CODEC.parse(JsonOps.INSTANCE, future).isError()) {
			throw failure(helper, "a newer attachment version must fail to decode");
		}
		JsonObject unversioned = new JsonObject();
		unversioned.addProperty("counter", 1);
		if (!Example.CODEC.parse(JsonOps.INSTANCE, unversioned).isError()) {
			throw failure(helper, "an attachment with no version must fail to decode");
		}
		JsonObject current = new JsonObject();
		current.addProperty("version", Example.CURRENT_VERSION);
		current.addProperty("counter", 3);
		Example read = Example.CODEC.parse(JsonOps.INSTANCE, current).getOrThrow();
		if (!read.equals(new Example(Example.CURRENT_VERSION, 3))) {
			throw failure(helper, "the current version should decode, got %s", read);
		}
		helper.succeed();
	}

	/** M1's crossing, with a piloted pod: the arriving pod is a new entity, and the attachment must be on it. */
	@GameTest(maxTicks = 300)
	public void theAttachmentSurvivesABreachCrossing(GameTestHelper helper) {
		double x = 2000.5;
		double z = 2000.5;
		ServerLevel one = layer(helper, 1);
		openShaft(one, x, z);
		PodEntity pod = PodRegistry.POD.create(one, EntitySpawnReason.COMMAND);
		pod.setPos(x, 8, z);
		one.addFreshEntity(pod);
		pod.setAttached(PodAttachments.EXAMPLE, new Example(Example.CURRENT_VERSION, 7));
		MockPlayer mock = MockPlayers.join(helper, "attachment-crossing");
		mock.teleportTo(one, new Vec3(x, 8, z), 0, 0);
		if (!mock.player().startRiding(pod, true, false)) {
			throw failure(helper, "the mock could not board the pod");
		}
		// The pod falls by itself (PodMovement brings its own gravity) and leaves layer_1 through the open floor.
		helper.succeedWhen(() -> {
			ServerPlayer player = mock.player();
			if (!player.level().dimension().equals(LayerChain.dimension(2))) {
				throw failure(helper, "the rider is still in %s", player.level().dimension());
			}
			if (!(player.getVehicle() instanceof PodEntity arrived)) {
				throw failure(helper, "the rider is on %s after crossing, not a pod", player.getVehicle());
			}
			if (arrived == pod || !pod.isRemoved()) {
				throw failure(helper, "expected a new pod instance after the crossing and the old one removed");
			}
			Example example = arrived.getAttached(PodAttachments.EXAMPLE);
			if (!new Example(Example.CURRENT_VERSION, 7).equals(example)) {
				throw failure(helper, "the attachment did not cross with the pod, got %s", example);
			}
		});
	}

	/** Clear a 3x3 shaft through the floor of {@code level}, wide enough for the pod's 1.9-block hull. */
	private static void openShaft(ServerLevel level, double x, double z) {
		BlockPos column = BlockPos.containing(x, 0, z);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int y = level.getMinY(); y <= level.getMinY() + 10; y++) {
					level.setBlock(column.offset(dx, 0, dz).atY(y), Blocks.AIR.defaultBlockState(), 3);
				}
			}
		}
	}

	private static ServerLevel layer(GameTestHelper helper, int layer) {
		ServerLevel level = helper.getLevel().getServer().getLevel(LayerChain.dimension(layer));
		if (level == null) {
			throw failure(helper, "dimension %s did not load", LayerChain.dimension(layer));
		}
		return level;
	}

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}
}
