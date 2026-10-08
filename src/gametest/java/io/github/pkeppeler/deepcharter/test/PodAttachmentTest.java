package io.github.pkeppeler.deepcharter.test;

import com.mojang.serialization.Dynamic;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
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

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.RoomSeal;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestAttachments;
import io.github.pkeppeler.deepcharter.test.support.TestAttachments.Example;
import io.github.pkeppeler.deepcharter.test.support.TestAttachments.Other;

/** Server GameTests for the versioned pod attachment pattern: defaults, save/load, unreadable versions and a breach crossing. */
public class PodAttachmentTest {
	private static final String ATTACHMENTS_KEY = "fabric:attachments";
	/** Ticks the pod needs, once its chunk ticks, to fall through the shaft and cross. */
	private static final int CROSSING_TICKS = 200;

	@GameTest
	public void aNewPodHasTheDefaultAttachment(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		try {
			if (!Versioned.orThrow(pod, TestAttachments.EXAMPLE).equals(Example.DEFAULT)) {
				throw failure(helper, "a new pod should carry %s", Example.DEFAULT);
			}
			helper.succeed();
		} finally {
			pod.discard();
		}
	}

	@GameTest
	public void theAttachmentSurvivesSaveAndLoad(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		Versioned.modifyOrThrow(pod, TestAttachments.EXAMPLE, example -> new Example(42));
		Entity loaded = reload(helper, pod, null);
		try {
			if (!new Example(42).equals(Versioned.orThrow(loaded, TestAttachments.EXAMPLE))) {
				throw failure(helper, "the attachment did not survive save and load, got %s", loaded.getAttached(TestAttachments.EXAMPLE));
			}
			helper.succeed();
		} finally {
			loaded.discard();
		}
	}

	/**
	 * The real load path. Fabric reads all of an entity's attachments as one map and drops the whole
	 * map if it fails to decode, so a bad entry must not fail: it must load as Unreadable, leave the
	 * valid attachment intact, fail loud when a feature reads it, and be written back unchanged.
	 */
	@GameTest
	public void anUnreadableVersionLoadsKeepsItsDataAndCostsOtherAttachmentsNothing(GameTestHelper helper) {
		String exampleKey = TestAttachments.EXAMPLE.identifier().toString();
		String otherKey = TestAttachments.OTHER.identifier().toString();
		CompoundTag future = new CompoundTag();
		future.putInt("version", 99);
		future.putInt("counter", 5);
		future.putString("added-in-v99", "kept");
		CompoundTag other = new CompoundTag();
		other.putInt("version", TestAttachments.VERSION);
		other.putString("text", "hi");
		CompoundTag attachments = new CompoundTag();
		attachments.put(exampleKey, future);
		attachments.put(otherKey, other);

		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		Entity loaded = reload(helper, pod, attachments);
		try {
			if (!new Other("hi").equals(Versioned.orThrow(loaded, TestAttachments.OTHER))) {
				throw failure(helper, "the valid attachment was lost: %s", loaded.getAttached(TestAttachments.OTHER));
			}
			if (!(loaded.getAttached(TestAttachments.EXAMPLE) instanceof Versioned.Unreadable<Example> unreadable)) {
				throw failure(helper, "version 99 should load as Unreadable, got %s", loaded.getAttached(TestAttachments.EXAMPLE));
			}
			if (!"99".equals(unreadable.version())) {
				throw failure(helper, "the unreadable value should report version 99, got %s", unreadable.version());
			}
			try {
				Versioned.orThrow(loaded, TestAttachments.EXAMPLE);
				throw failure(helper, "reading an unreadable attachment must fail loud");
			} catch (IllegalStateException expected) {
				if (!expected.getMessage().contains(exampleKey) || !expected.getMessage().contains("99")) {
					throw failure(helper, "the failure should name the attachment and version, got: %s", expected.getMessage());
				}
			}
			try {
				Versioned.modifyOrThrow(loaded, TestAttachments.EXAMPLE, example -> new Example(1));
				throw failure(helper, "modifying an unreadable attachment must fail rather than overwrite it");
			} catch (IllegalStateException expected) {
				// Expected.
			}

			TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
			loaded.saveWithoutId(output);
			CompoundTag saved = output.buildResult().getCompound(ATTACHMENTS_KEY).orElseThrow(() -> failure(helper, "the pod saved no attachments"));
			if (!future.equals(saved.get(exampleKey))) {
				throw failure(helper, "the unreadable data was not written back unchanged: %s", saved.get(exampleKey));
			}
			if (!other.equals(saved.get(otherKey))) {
				throw failure(helper, "the valid attachment was not saved intact: %s", saved.get(otherKey));
			}
			helper.succeed();
		} finally {
			loaded.discard();
		}
	}

	@GameTest
	public void theDiskCodecNeverFails(GameTestHelper helper) {
		CompoundTag noVersion = new CompoundTag();
		noVersion.putInt("counter", 1);
		CompoundTag badBody = new CompoundTag();
		badBody.putInt("version", TestAttachments.VERSION);
		badBody.putString("counter", "not a number");
		CompoundTag current = new CompoundTag();
		current.putInt("version", TestAttachments.VERSION);
		current.putInt("counter", 3);
		var codec = TestAttachments.EXAMPLE.persistenceCodec();
		for (CompoundTag bad : new CompoundTag[] {noVersion, badBody}) {
			Versioned<Example> read = codec.parse(NbtOps.INSTANCE, bad).getOrThrow();
			if (!(read instanceof Versioned.Unreadable<Example> unreadable) || !bad.equals(unreadable.raw())) {
				throw failure(helper, "%s should decode to Unreadable holding it, got %s", bad, read);
			}
			if (!bad.equals(codec.encodeStart(NbtOps.INSTANCE, read).getOrThrow())) {
				throw failure(helper, "%s was not re-encoded unchanged", bad);
			}
		}
		if (!Versioned.of(new Example(3)).equals(codec.parse(new Dynamic<>(NbtOps.INSTANCE, current)).getOrThrow())) {
			throw failure(helper, "the current version should decode");
		}
		helper.succeed();
	}

	@GameTest
	public void theWireCarriesAnUnreadableValueAsAMarkerAndRefusesAForeignVersion(GameTestHelper helper) {
		var codec = Versioned.streamCodec(TestAttachments.VERSION, Example.STREAM);
		ByteBuf buf = Unpooled.buffer();
		codec.encode(buf, Versioned.of(new Example(8)));
		if (!Versioned.of(new Example(8)).equals(codec.decode(buf))) {
			throw failure(helper, "a readable value should round-trip on the wire");
		}
		codec.encode(buf, new Versioned.Unreadable<>(new CompoundTag()));
		if (!(codec.decode(buf) instanceof Versioned.Unreadable<Example>)) {
			throw failure(helper, "an unreadable value should arrive as Unreadable");
		}
		ByteBuf foreign = Unpooled.buffer();
		ByteBufCodecs.VAR_INT.encode(foreign, TestAttachments.VERSION + 1);
		try {
			codec.decode(foreign);
		} catch (IllegalStateException expected) {
			helper.succeed();
			return;
		}
		throw failure(helper, "a foreign wire version must throw");
	}

	/** M1's crossing, with a piloted pod: the arriving pod is a new entity, and the attachment must be on it. */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
	public void theAttachmentSurvivesABreachCrossing(GameTestHelper helper) {
		double x = 2000.5;
		double z = 2000.5;
		ServerLevel one = layer(helper, 1);
		openShaft(one, x, z);
		MockPlayer mock = MockPlayers.join(helper, "attachment-crossing");
		mock.teleportTo(one, new Vec3(x, 8, z), 0, 0);
		PodEntity[] pod = {null};
		FarChunks.awaitEntityTicking(helper, one, BlockPos.containing(x, 8, z), () -> {
			pod[0] = PodRegistry.POD.create(one, EntitySpawnReason.COMMAND);
			pod[0].setPos(x, 8, z);
			one.addFreshEntity(pod[0]);
			Versioned.modifyOrThrow(pod[0], TestAttachments.EXAMPLE, example -> new Example(7));
			if (!mock.player().startRiding(pod[0], true, false)) {
				throw failure(helper, "the mock could not board the pod");
			}
		});
		// The pod falls by itself (PodMovement brings its own gravity) and leaves layer_1 through the open floor.
		helper.succeedWhen(() -> {
			if (pod[0] == null) {
				throw failure(helper, "waiting for the chunk at %s to tick entities", BlockPos.containing(x, 8, z));
			}
			ServerPlayer player = mock.player();
			if (!player.level().dimension().equals(LayerChain.dimension(2))) {
				throw failure(helper, "the rider is still in %s (pod ticked %s times)", player.level().dimension(), pod[0].tickCount);
			}
			if (!(player.getVehicle() instanceof PodEntity arrived)) {
				throw failure(helper, "the rider is on %s after crossing, not a pod", player.getVehicle());
			}
			if (arrived == pod[0] || !pod[0].isRemoved()) {
				throw failure(helper, "expected a new pod instance after the crossing and the old one removed");
			}
			if (!new Example(7).equals(Versioned.orThrow(arrived, TestAttachments.EXAMPLE))) {
				throw failure(helper, "the attachment did not cross with the pod, got %s", arrived.getAttached(TestAttachments.EXAMPLE));
			}
		});
	}

	/** Saves {@code pod} (replacing its attachments with {@code attachments} if given), discards it and loads a new pod from the data. */
	private static Entity reload(GameTestHelper helper, PodEntity pod, CompoundTag attachments) {
		ServerLevel level = helper.getLevel();
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		pod.saveWithoutId(output);
		pod.discard();
		CompoundTag tag = output.buildResult();
		if (attachments != null) {
			tag.put(ATTACHMENTS_KEY, attachments);
		}
		return EntityType.create(PodRegistry.POD,
				TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag),
				level, EntitySpawnReason.LOAD).orElseThrow(() -> failure(helper, "the saved pod did not load"));
	}

	/** Clear a 3x3 shaft through the floor of {@code level}, wide enough for the pod's 1.9-block hull. */
	private static void openShaft(ServerLevel level, double x, double z) {
		BlockPos column = BlockPos.containing(x, 0, z);
		RoomSeal.seal(level, column.offset(-1, 0, -1).atY(level.getMinY()), column.offset(1, 0, 1).atY(level.getMinY() + 10));
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
