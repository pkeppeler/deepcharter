package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/**
 * Evidence scenario "pilot-in-canopy" for #382. The real player pilots a Mole and then a Prospector (a mock navigator takes the
 * Prospector's second seat) on a flat slab. For each pod: third person from the side and from behind and above, where the pilot shows through
 * the windows and not over the roof; and first person looking ahead, down and up, which frames the canopy and the cutter ahead and
 * never looks from inside the hull.
 */
public class PilotInCanopyScenario extends EvidenceScenario {
	private static final int X = 1500;
	private static final int Z = 1500;
	private static final int FLOOR_Y = 200;
	private static final int SLAB_RADIUS = 12;
	private static final int SLAB_DEPTH = 6;
	private static final int SETTLE_TICKS = 20;
	private static final float POD_YAW = 0f;
	private static final float SIDE_ON = 90f;
	private static final float REAR_QUARTER = 60f;
	private static final float LEVEL = 0f;
	private static final float LOOK_DOWN = 55f;
	private static final float LOOK_UP = -55f;

	private record Subject(String id, EntityType<PodEntity> type, Chassis chassis) {
	}

	private static final List<Subject> SUBJECTS = List.of(
			new Subject("mole", PodRegistry.POD, Chassis.MOLE),
			new Subject("prospector", PodRegistry.PROSPECTOR, Chassis.PROSPECTOR));

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
				buildSlab(server.overworld());
			});
			ClientWait.until(context, "the slab on the client", client -> client.level.getBlockState(new BlockPos(X, FLOOR_Y - 1, Z)).is(Blocks.STONE));
			for (Subject subject : SUBJECTS) {
				show(context, singleplayer, subject);
			}
		}
	}

	private void show(ClientGameTestContext context, TestSingleplayerContext singleplayer, Subject subject) {
		PodEntity[] made = {null};
		MockPlayer[] navigator = {null};
		singleplayer.getServer().runOnServer(server -> {
			ServerLevel level = server.overworld();
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			PodEntity pod = subject.type().create(level, EntitySpawnReason.COMMAND);
			pod.setPos(X + 0.5, FLOOR_Y, Z + 0.5);
			pod.setYRot(POD_YAW);
			pod.setFuel(100f);
			level.addFreshEntity(pod);
			if (!player.startRiding(pod)) {
				throw new AssertionError("the pilot could not board the " + subject.id());
			}
			if (subject.chassis().seats() > 1) {
				navigator[0] = MockPlayers.join(server, "Navigator");
				navigator[0].teleportTo(level, new Vec3(X + 0.5, FLOOR_Y, Z + 0.5), POD_YAW, 0f);
				if (!navigator[0].player().startRiding(pod)) {
					throw new AssertionError("the navigator could not board the " + subject.id());
				}
			}
			made[0] = pod;
		});
		PodEntity pod = made[0];
		ClientWait.until(context, "the " + subject.id() + " ridden on the client", client -> client.level.getEntity(pod.getId()) instanceof PodEntity shown
				&& shown.getPassengers().size() == subject.chassis().seats() && client.player.getVehicle() == shown);

		look(context, CameraType.THIRD_PERSON_BACK, SIDE_ON, 10f);
		settle(context);
		screenshot(context, subject.id() + "-third-person-side");
		frame(context);
		look(context, CameraType.THIRD_PERSON_BACK, REAR_QUARTER, 28f);
		settle(context);
		screenshot(context, subject.id() + "-third-person-above");
		frame(context);
		look(context, CameraType.FIRST_PERSON, POD_YAW, LEVEL);
		settle(context);
		screenshot(context, subject.id() + "-first-person");
		frame(context);
		look(context, CameraType.FIRST_PERSON, POD_YAW, LOOK_DOWN);
		settle(context);
		screenshot(context, subject.id() + "-first-person-down");
		frame(context);
		look(context, CameraType.FIRST_PERSON, POD_YAW, LOOK_UP);
		settle(context);
		screenshot(context, subject.id() + "-first-person-up");
		frame(context);

		singleplayer.getServer().runOnServer(server -> {
			server.getPlayerList().getPlayers().getFirst().stopRiding();
			if (navigator[0] != null) {
				navigator[0].player().stopRiding();
				navigator[0].leave();
			}
			pod.discard();
		});
		ClientWait.until(context, "the pod gone from the client", client -> client.level.getEntity(pod.getId()) == null && client.player.getVehicle() == null);
	}

	/** The camera of the riding player: which view, and where it looks. */
	private static void look(ClientGameTestContext context, CameraType view, float yaw, float pitch) {
		context.runOnClient(client -> {
			client.options.setCameraType(view);
			client.player.setYRot(yaw);
			client.player.yRotO = yaw;
			client.player.setXRot(pitch);
			client.player.xRotO = pitch;
			client.gui.toastManager().clear();
		});
	}

	private static void settle(ClientGameTestContext context) {
		ClientWait.until(context, "every section in view rendered", client -> client.levelRenderer.hasRenderedAllSections());
		context.waitTicks(SETTLE_TICKS); // tick-wait: the pod's pose and the sections settle for a few frames
	}

	private static void buildSlab(ServerLevel level) {
		for (int x = X - SLAB_RADIUS; x <= X + SLAB_RADIUS; x++) {
			for (int z = Z - SLAB_RADIUS; z <= Z + SLAB_RADIUS; z++) {
				for (int y = FLOOR_Y - SLAB_DEPTH; y < FLOOR_Y; y++) {
					level.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), 2);
				}
				for (int y = FLOOR_Y; y <= FLOOR_Y + 12; y++) {
					// room-carver: a slab built in the overworld, not layer rock
					level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
				}
			}
		}
	}
}
