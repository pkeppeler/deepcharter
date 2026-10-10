package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/**
 * Evidence scenario "pilot-in-canopy" for #382 and #400 (the Mole is 2.9 tall, so the pilot sits upright), in a stone hall that light blocks fill with full light. For each pod: two close stills
 * from outside (a three-quarter view from the front and one from the side), where a mock pilot, and for the Prospector a mock navigator,
 * show through the panes and not over the roof; then the real player pilots the pod, and first person looks ahead, down and up, which
 * frames the canopy, the sill and the cutter, and never looks from inside the hull.
 */
public class PilotInCanopyScenario extends EvidenceScenario {
	private static final int X = 1500;
	private static final int Z = 1500;
	private static final int FLOOR_Y = 200;
	private static final int HALL_RADIUS = 8;
	private static final int HALL_HEIGHT = 7;
	private static final int LIGHT_STEP = 3;
	private static final double EYE = 1.62;
	private static final int SETTLE_TICKS = 20;
	private static final float POD_YAW = 0f;
	private static final float LEVEL = 0f;
	private static final float LOOK_DOWN = 55f;
	private static final float LOOK_UP = -55f;

	/** A camera outside the pod: where it stands from the pod's feet (x to the pod's left, z ahead), and the cab point it looks at. */
	private record Shot(String name, Vec3 camera, Vec3 target) {
	}

	private record Subject(String id, EntityType<PodEntity> type, List<Shot> shots) {
	}

	private static final List<Subject> SUBJECTS = List.of(
			new Subject("mole", PodRegistry.POD, List.of(
					new Shot("front-three-quarter", new Vec3(2.6, 2.6, 1.6), new Vec3(0, 1.45, -0.4)),
					new Shot("side-close", new Vec3(2.6, 2.0, -0.4), new Vec3(0, 1.6, -0.4)))),
			new Subject("prospector", PodRegistry.PROSPECTOR, List.of(
					new Shot("front-three-quarter", new Vec3(3.4, 3.2, 1.9), new Vec3(0, 1.9, -0.55)),
					new Shot("side-close", new Vec3(3.4, 2.5, -0.55), new Vec3(0, 2.0, -0.55)))));

	@Override
	protected String name() {
		return "pilot-in-canopy";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			EvidenceWorld.pin(context, singleplayer);
			context.runOnClient(client -> {
				if (!client.gui.hud.isHidden()) {
					client.gui.hud.toggle();
				}
			});
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.setGameMode(GameType.CREATIVE);
				player.setPermanentlyInvulnerable(true);
				player.teleportTo(server.overworld(), X + 0.5, FLOOR_Y + 1, Z + 0.5, Set.of(), POD_YAW, 0f, true);
				buildHall(server.overworld());
			});
			ClientWait.until(context, "the hall on the client", client -> client.level.getBlockState(new BlockPos(X + HALL_RADIUS, FLOOR_Y + 1, Z)).is(Blocks.STONE_BRICKS)
					&& client.level.getBlockState(new BlockPos(X, FLOOR_Y + HALL_HEIGHT, Z)).is(Blocks.STONE_BRICKS));
			for (Subject subject : SUBJECTS) {
				fromOutside(context, singleplayer, subject);
				fromInside(context, singleplayer, subject);
			}
		}
	}

	/** A mock pilot (and navigator) sit in the pod; the real player flies close to it and looks at the cab. */
	private void fromOutside(ClientGameTestContext context, TestSingleplayerContext singleplayer, Subject subject) {
		PodEntity[] made = {null};
		MockPlayer[] riders = {null, null};
		singleplayer.getServer().runOnServer(server -> {
			ServerLevel level = server.overworld();
			ServerPlayer camera = server.getPlayerList().getPlayers().getFirst();
			camera.getAbilities().mayfly = true;
			camera.getAbilities().flying = true;
			camera.onUpdateAbilities();
			camera.setNoGravity(true);
			PodEntity pod = pod(level, subject);
			riders[0] = board(server, pod, "Pilot");
			if (PodRegistry.chassisOf(subject.type()).seats() > 1) {
				riders[1] = board(server, pod, "Navigator");
			}
			made[0] = pod;
		});
		PodEntity pod = made[0];
		ClientWait.until(context, "the " + subject.id() + " with its riders on the client", client -> client.level.getEntity(pod.getId()) instanceof PodEntity shown
				&& shown.getPassengers().size() == PodRegistry.chassisOf(subject.type()).seats());
		context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
		Vec3 feet = new Vec3(X + 0.5, FLOOR_Y, Z + 0.5);
		for (Shot shot : subject.shots()) {
			lookAt(singleplayer, feet.add(shot.camera()), feet.add(shot.target()));
			settle(context);
			screenshot(context, subject.id() + "-" + shot.name());
			frame(context);
		}
		singleplayer.getServer().runOnServer(server -> {
			for (MockPlayer rider : riders) {
				if (rider != null) {
					rider.player().stopRiding();
					rider.leave();
				}
			}
			pod.discard();
		});
		ClientWait.until(context, "the pod gone from the client", client -> client.level.getEntity(pod.getId()) == null);
	}

	/** The real player pilots the pod, and the camera is the pilot's eye: level, down and up. */
	private void fromInside(ClientGameTestContext context, TestSingleplayerContext singleplayer, Subject subject) {
		PodEntity[] made = {null};
		singleplayer.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			player.teleportTo(server.overworld(), X + 0.5, FLOOR_Y, Z + 0.5, Set.of(), POD_YAW, 0f, true);
			PodEntity pod = pod(server.overworld(), subject);
			if (!player.startRiding(pod)) {
				throw new AssertionError("the pilot could not board the " + subject.id());
			}
			made[0] = pod;
		});
		PodEntity pod = made[0];
		ClientWait.until(context, "the pilot aboard the " + subject.id() + " on the client", client -> client.level.getEntity(pod.getId()) instanceof PodEntity shown
				&& client.player.getVehicle() == shown);
		for (float pitch : new float[] {LEVEL, LOOK_DOWN, LOOK_UP}) {
			context.runOnClient(client -> {
				client.options.setCameraType(CameraType.FIRST_PERSON);
				client.player.setYRot(POD_YAW);
				client.player.yRotO = POD_YAW;
				client.player.setXRot(pitch);
				client.player.xRotO = pitch;
				client.gui.toastManager().clear();
			});
			settle(context);
			screenshot(context, subject.id() + "-first-person" + (pitch == LEVEL ? "" : pitch > 0 ? "-down" : "-up"));
			frame(context);
		}
		singleplayer.getServer().runOnServer(server -> {
			server.getPlayerList().getPlayers().getFirst().stopRiding();
			pod.discard();
		});
		ClientWait.until(context, "the pilot off the pod", client -> client.level.getEntity(pod.getId()) == null && client.player.getVehicle() == null);
	}

	private static PodEntity pod(ServerLevel level, Subject subject) {
		PodEntity pod = subject.type().create(level, EntitySpawnReason.COMMAND);
		pod.setPos(X + 0.5, FLOOR_Y, Z + 0.5);
		pod.setYRot(POD_YAW);
		pod.setFuel(100f);
		level.addFreshEntity(pod);
		return pod;
	}

	private static MockPlayer board(MinecraftServer server, PodEntity pod, String name) {
		MockPlayer rider = MockPlayers.join(server, name);
		rider.teleportTo(server.overworld(), pod.position(), POD_YAW, 0f);
		if (!rider.player().startRiding(pod)) {
			throw new AssertionError(name + " could not board " + pod);
		}
		// Bright armour, for the stills only, so a reader finds the head and shoulders in the cab at a glance.
		boolean pilot = name.equals("Pilot");
		rider.player().setItemSlot(EquipmentSlot.HEAD, new ItemStack(pilot ? Items.GOLDEN_HELMET : Items.DIAMOND_HELMET));
		rider.player().setItemSlot(EquipmentSlot.CHEST, new ItemStack(pilot ? Items.GOLDEN_CHESTPLATE : Items.DIAMOND_CHESTPLATE));
		return rider;
	}

	/** Puts the flying camera player at {@code eye}, looking at {@code target}. */
	private static void lookAt(TestSingleplayerContext singleplayer, Vec3 eye, Vec3 target) {
		Vec3 d = target.subtract(eye);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
		singleplayer.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst()
				.teleportTo(server.overworld(), eye.x, eye.y - EYE, eye.z, Set.of(), yaw, pitch, true));
	}

	private static void settle(ClientGameTestContext context) {
		ClientWait.until(context, "every section in view rendered", client -> client.levelRenderer.hasRenderedAllSections());
		context.waitTicks(SETTLE_TICKS); // tick-wait: the pod's pose and the sections settle for a few frames
	}

	/** A stone-brick hall with a floor, walls and a ceiling, and an invisible full-light block every few cells, so the pod and its riders are lit. */
	private static void buildHall(ServerLevel level) {
		for (int x = X - HALL_RADIUS; x <= X + HALL_RADIUS; x++) {
			for (int z = Z - HALL_RADIUS; z <= Z + HALL_RADIUS; z++) {
				boolean wall = Math.abs(x - X) == HALL_RADIUS || Math.abs(z - Z) == HALL_RADIUS;
				for (int y = FLOOR_Y - 1; y <= FLOOR_Y + HALL_HEIGHT; y++) {
					Block block = y == FLOOR_Y - 1 || y == FLOOR_Y + HALL_HEIGHT || wall ? Blocks.STONE_BRICKS : Blocks.AIR;
					// room-carver: a hall built in the overworld, not layer rock
					level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 2);
				}
			}
		}
		for (int x = X - HALL_RADIUS + 2; x < X + HALL_RADIUS; x += LIGHT_STEP) {
			for (int z = Z - HALL_RADIUS + 2; z < Z + HALL_RADIUS; z += LIGHT_STEP) {
				level.setBlock(new BlockPos(x, FLOOR_Y + HALL_HEIGHT - 2, z), Blocks.LIGHT.defaultBlockState(), 2);
			}
		}
	}
}
