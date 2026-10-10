package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.client.pod.BoneRole;
import io.github.pkeppeler.deepcharter.client.pod.GeoModel;
import io.github.pkeppeler.deepcharter.client.pod.PodGeoRenderer;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/**
 * Client GameTest for #382: the pilot sits inside a canopy. It pins the seat offsets of each chassis to literal values (the Prospector
 * has two, in tandem), seats real riders in each pod and holds them against the cab of the pod's own model: a rider's head is inside
 * the cab's window band and touches no cube of the model, the camera at the pilot's eye keeps clear of every cube (so no pitch looks
 * from inside a hull face), and the pilot looks ahead through a pane at the cutter.
 */
public class PodSeatsClientTest implements FabricClientGameTest {
	private static final double TOLERANCE = 1e-6;
	private static final double PIXELS_PER_BLOCK = 16;
	/**
	 * How near a cube may come to the eye, in blocks. The camera's near plane is 0.05 away, and its corner, at the widest field of view
	 * the game offers (110 degrees) on a 21:9 window, is 0.19 from the eye, so a cube outside 0.2 never shows an inside face.
	 */
	private static final double NEAR_CLEARANCE = 0.2;
	/** The player's head, in blocks: the head cube of the model spans these heights above the feet, and is 7.5 pixels wide and deep. */
	private static final double HEAD_BOTTOM = 1.406;
	private static final double HEAD_TOP = 1.875;
	private static final double HEAD_HALF_WIDTH = 3.75;
	/** A pane is a sheet: a cube no thicker than this and at least {@link #PANE_MIN_SIDE} across the other two ways. */
	private static final double PANE_THICKNESS = 1;
	private static final double PANE_MIN_SIDE = 8;

	/**
	 * The seats of each chassis, pilot first, in blocks from the pod's feet with +z the way the pod faces: the bottom of the seat. A
	 * literal, so a change to a seat is a change to this test. The Mole sits 6 pixels up and 5 back of its nose; the Prospector's two
	 * sit 8 up, 3.5 and 14.5 back, the pilot in front.
	 */
	private static final Map<Chassis, List<Vec3>> SEATS = Map.of(
			Chassis.MOLE, List.of(new Vec3(0, 0.375, -0.3125)),
			Chassis.PROSPECTOR, List.of(new Vec3(0, 0.5, -0.21875), new Vec3(0, 0.5, -0.90625)));

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			for (Chassis chassis : Chassis.all()) {
				seatAndCheck(context, singleplayer, chassis);
			}
		}
	}

	private static void seatAndCheck(ClientGameTestContext context, TestSingleplayerContext singleplayer, Chassis chassis) {
		List<Vec3> seats = SEATS.get(chassis);
		require(seats != null && seats.size() == chassis.seats(), chassis.id() + " should have " + chassis.seats() + " pinned seats, has " + seats);
		require(chassis != Chassis.PROSPECTOR || seats.get(0).z > seats.get(1).z, "the Prospector's pilot sits ahead of its navigator: " + seats);
		require(seats.equals(PodRegistry.seatsOf(chassis)), chassis.id() + "'s seats are " + PodRegistry.seatsOf(chassis) + ", not the pinned " + seats);

		int[] pod = {0};
		MockPlayer[] navigator = {null};
		singleplayer.getServer().runOnServer(server -> pod[0] = board(server, chassis, navigator));
		ClientWait.until(context, "the " + chassis.id() + " with " + chassis.seats() + " riders on the client", client -> client.level.getEntity(pod[0]) instanceof PodEntity shown
				&& shown.getPassengers().size() == chassis.seats() && client.player.getVehicle() == shown,
				client -> client.level.getEntity(pod[0]) instanceof PodEntity shown ? shown.getPassengers().size() + " riders" : "no pod");
		// A rider is drawn where the pod places it, which the client does a tick or two after the passenger list syncs.
		ClientWait.until(context, "every rider of the " + chassis.id() + " in its seat", client -> client.level.getEntity(pod[0]) instanceof PodEntity shown
				&& seated(shown), client -> client.level.getEntity(pod[0]) instanceof PodEntity shown ? riders(shown) : "no pod");
		context.runOnClient(client -> check(client, (PodEntity) client.level.getEntity(pod[0]), seats));

		singleplayer.getServer().runOnServer(server -> {
			server.getPlayerList().getPlayers().getFirst().stopRiding();
			if (navigator[0] != null) {
				navigator[0].player().stopRiding();
				navigator[0].leave();
			}
			server.overworld().getEntity(pod[0]).discard();
		});
		ClientWait.until(context, "the " + chassis.id() + " gone", client -> client.level.getEntity(pod[0]) == null && client.player.getVehicle() == null);
	}

	/** A pod of {@code chassis} facing +z beside the player, with the player aboard and, for a second seat, a mock navigator: the pod's id. */
	private static int board(MinecraftServer server, Chassis chassis, MockPlayer[] navigator) {
		ServerLevel level = server.overworld();
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		PodEntity pod = PodRegistry.typeOf(chassis).create(level, EntitySpawnReason.COMMAND);
		pod.setPos(player.position());
		pod.setYRot(0f);
		level.addFreshEntity(pod);
		require(player.startRiding(pod), "the player could not board the " + chassis.id());
		if (chassis.seats() > 1) {
			navigator[0] = MockPlayers.join(server, "Navigator");
			navigator[0].teleportTo(level, pod.position(), 0f, 0f);
			require(navigator[0].player().startRiding(pod), "the navigator could not board the " + chassis.id());
		}
		return pod.getId();
	}

	/** True when every rider's position is the one the pod gives it, which is where {@link #check} reads it. */
	private static boolean seated(PodEntity pod) {
		return pod.getPassengers().stream().allMatch(rider -> rider.position().distanceTo(placed(pod, rider)) < TOLERANCE);
	}

	/** Where the pod puts {@code rider}: its seat less the rider's own attachment to a vehicle. */
	private static Vec3 placed(PodEntity pod, Entity rider) {
		return pod.getPassengerRidingPosition(rider).subtract(rider.getVehicleAttachmentPoint(pod));
	}

	private static String riders(PodEntity pod) {
		return pod.getPassengers().stream().map(rider -> rider.position() + " for " + placed(pod, rider)).toList().toString();
	}

	private static void check(Minecraft client, PodEntity pod, List<Vec3> seats) {
		Chassis chassis = pod.chassis();
		// The engine seats each rider at the offset: the entity type's passenger attachments are the pinned seats.
		for (int i = 0; i < seats.size(); i++) {
			Vec3 attachment = pod.getType().getDimensions().attachments().getClamped(EntityAttachment.PASSENGER, i, 0f);
			require(attachment.distanceTo(seats.get(i)) < TOLERANCE, chassis.id() + "'s passenger attachment " + i + " is " + attachment + ", not the pinned seat " + seats.get(i));
			Entity rider = pod.getPassengers().get(i);
			Vec3 seated = pod.getPassengerRidingPosition(rider).subtract(pod.position());
			require(seated.distanceTo(seats.get(i)) < TOLERANCE, chassis.id() + "'s rider " + i + " sits at " + seated + ", not the pinned seat " + seats.get(i));
		}
		PodGeoRenderer renderer = (PodGeoRenderer) client.getEntityRenderDispatcher().getRenderer(pod);
		GeoModel geo = renderer.geometry();
		List<GeoModel.RestCube> cab = geo.restCubes(bone -> !geo.inCutter(bone, null) && bone.role() != BoneRole.DRILL_MOUNT);
		double[] windows = geo.restBounds(bone -> bone.name().equals("canopy"));
		for (int i = 0; i < seats.size(); i++) {
			Entity rider = pod.getPassengers().get(i);
			String who = chassis.id() + "'s " + (i == 0 ? "pilot" : "navigator");
			Vec3 eye = model(pod, rider.getEyePosition());
			double[] head = box(model(pod, rider.position().add(0, HEAD_BOTTOM, 0)), model(pod, rider.position().add(0, HEAD_TOP, 0)), HEAD_HALF_WIDTH);
			// The head is in the window band, so the pilot shows through the windows and not over the roof.
			require(head[1] >= windows[1] && head[4] <= windows[4], who + "'s head spans y " + head[1] + " to " + head[4] + ", not inside the window band " + windows[1] + " to " + windows[4]);
			for (GeoModel.RestCube cube : cab) {
				require(!overlap(head, cube.box(), 0), who + "'s head, " + show(head) + ", is inside a cube of the bone " + cube.bone().name() + ": " + show(cube.box()));
				// The camera sits at the eye, and a pitch does not move it: so a cube clear of the eye by the near plane's reach is never seen from inside.
				double[] near = {eye.x, eye.y, eye.z, eye.x, eye.y, eye.z};
				require(!overlap(near, cube.box(), NEAR_CLEARANCE * PIXELS_PER_BLOCK), who + "'s eye " + eye + " is within " + NEAR_CLEARANCE
						+ " blocks of a cube of the bone " + cube.bone().name() + ": " + show(cube.box()));
			}
			// The pilot looking ahead, and at the cutter, has a pane first in the way. (The navigator looks at the pilot's seat back.)
			if (i > 0) {
				continue;
			}
			Vec3 cutter = centre(geo.restBounds(bone -> geo.inCutter(bone, "tricone")));
			for (Vec3 target : List.of(eye.add(0, 0, -1), cutter)) {
				GeoModel.RestCube first = firstHit(cab, eye, target.subtract(eye));
				require(first != null && first.bone().name().equals("canopy") && isPane(first.box()), who + " should see "
						+ (target == cutter ? "the cutter" : "ahead") + " through a pane, but the first cube in the way is " + (first == null ? "nothing" : first.bone().name() + " " + show(first.box())));
			}
		}
	}

	/** {@code world} as a point of the model: pixels, y up, the pod facing -z at heading 0, which is how the pod is placed (yaw 0). */
	private static Vec3 model(PodEntity pod, Vec3 world) {
		Vec3 d = world.subtract(pod.position()).scale(PIXELS_PER_BLOCK);
		return new Vec3(d.x, d.y, -d.z);
	}

	/** The box between the model points {@code bottom} and {@code top}'s heights, {@code half} pixels either side of them in x and z. */
	private static double[] box(Vec3 bottom, Vec3 top, double half) {
		return new double[] {bottom.x - half, bottom.y, bottom.z - half, top.x + half, top.y, top.z + half};
	}

	private static boolean overlap(double[] a, double[] b, double margin) {
		for (int i = 0; i < 3; i++) {
			if (a[i + 3] + margin <= b[i] || a[i] - margin >= b[i + 3]) {
				return false;
			}
		}
		return true;
	}

	private static Vec3 centre(double[] b) {
		return new Vec3((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2);
	}

	private static boolean isPane(double[] b) {
		List<Double> sides = new ArrayList<>(List.of(b[3] - b[0], b[4] - b[1], b[5] - b[2]));
		sides.sort(Double::compare);
		return sides.get(0) <= PANE_THICKNESS + TOLERANCE && sides.get(1) >= PANE_MIN_SIDE;
	}

	/** The cube that the ray from {@code from} along {@code direction} enters first, or null. */
	private static GeoModel.RestCube firstHit(List<GeoModel.RestCube> cubes, Vec3 from, Vec3 direction) {
		GeoModel.RestCube first = null;
		double nearest = Double.MAX_VALUE;
		double[] origin = {from.x, from.y, from.z};
		double[] d = {direction.x, direction.y, direction.z};
		for (GeoModel.RestCube cube : cubes) {
			double in = 0;
			double out = Double.MAX_VALUE;
			for (int i = 0; i < 3; i++) {
				if (Math.abs(d[i]) < TOLERANCE) {
					if (origin[i] < cube.box()[i] || origin[i] > cube.box()[i + 3]) {
						out = -1;
					}
					continue;
				}
				double t0 = (cube.box()[i] - origin[i]) / d[i];
				double t1 = (cube.box()[i + 3] - origin[i]) / d[i];
				in = Math.max(in, Math.min(t0, t1));
				out = Math.min(out, Math.max(t0, t1));
			}
			if (in < out && in > 0 && in < nearest) {
				nearest = in;
				first = cube;
			}
		}
		return first;
	}

	private static String show(double[] b) {
		return String.format("x %.1f..%.1f y %.1f..%.1f z %.1f..%.1f", b[0], b[3], b[1], b[4], b[2], b[5]);
	}
}
