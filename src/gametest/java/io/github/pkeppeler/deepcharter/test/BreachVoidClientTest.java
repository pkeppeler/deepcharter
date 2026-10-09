package io.github.pkeppeler.deepcharter.test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.imageio.ImageIO;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.layer.BreachService;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;

/**
 * Client GameTest for #333: looking through a hole at the bottom of a layer shows rock and darkness, never the void behind it. The
 * void is drawn in the fog colour (and a night-vision potion lifts that colour to full brightness), and on the surface in the
 * sky's. Each of the surface, layer 1 and layer 2 gets a lit room with a 9 x 9 hole through its floor (the crust broken in a layer, a
 * bored shaft on the surface), seen from far above, straight down, from high up at the side, and from the floor level at the side,
 * with and without night vision. The pixels of the hole must be dark, and in the same frame a lit block must show where the
 * camera maths says it is, so a black or blank frame, a camera in rock, or a patch that missed the hole cannot pass. A pod that is
 * under the bottom of the world, as a crossing pod is for its last ticks, must still show over the hole.
 */
public class BreachVoidClientTest implements FabricClientGameTest {
	private static final int X = 2000;
	private static final int Z = 2000;
	private static final double EYE = 1.62;
	/** The hole is a 9 x 9 shaft three blocks deep (the crust) under a room whose floor is that many blocks above the bottom of the world. */
	private static final int SHAFT_DEPTH = 3;
	private static final int HOLE_RADIUS = 4;
	private static final int ROOM_RADIUS = 10;
	private static final int ROOM_HEIGHT = 6;
	/** A column over the hole, tall enough to look down it from more than 32 blocks (where the vanilla fade to black of the void ends). */
	private static final int COLUMN_HEIGHT = 50;
	/** Each channel of a pixel of the hole is at or under this: darkness, not a fog or sky colour. */
	private static final int DARK = 0x10;
	/** A lit block shows a pixel brighter than this in some channel. */
	private static final int LIT = 0x40;
	/** The patch of pixels read at the hole, either side of its middle. */
	private static final int PATCH = 6;
	/** Pixels of the patch that a speck of dust may light, on the surface only: the layers have none. */
	private static final int SURFACE_SPECKS = 6;
	/** A lamp (glowstone) stands on the room floor this far out from the middle, at the four compass points. */
	private static final int LAMP_OFFSET = 6;
	private static final int EDGE_MARGIN = 20;
	/** How far from its predicted pixel a lit landmark may show. */
	private static final int WINDOW = 30;

	private record View(String name, Vec3 eye, boolean bothLightings) {
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		List<String> failures = new ArrayList<>();
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			ClientWait.until(context, "the client in the world", client -> client.player != null && client.level != null);
			// F1: no crosshair over the middle of the picture, where the hole is. The client is shared with the next test, so the HUD is put back.
			boolean hudWasHidden = context.computeOnClient(client -> client.gui.hud.isHidden());
			if (!hudWasHidden) {
				context.runOnClient(client -> client.gui.hud.toggle());
			}
			try {
				singleplayer.getServer().runOnServer(server -> {
					ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
					player.setGameMode(GameType.CREATIVE);
					player.setNoGravity(true);
					player.getAbilities().flying = true;
					player.onUpdateAbilities();
				});
				for (int layer : new int[] {LayerChain.SURFACE, 1, 2}) {
					int minY = singleplayer.getServer().computeOnServer(server -> openHole(server.getLevel(LayerChain.dimension(layer))));
					Vec3 hole = new Vec3(X + 0.5, minY + 0.5, Z + 0.5);
					int specks = layer == LayerChain.SURFACE ? SURFACE_SPECKS : 0;
					View[] views = {
							new View("far-above", new Vec3(X + 0.5, minY + SHAFT_DEPTH + COLUMN_HEIGHT - 4, Z + 0.5), false),
							new View("down", new Vec3(X + 0.5, minY + SHAFT_DEPTH + 8, Z + 0.5), true),
							new View("aslant", new Vec3(X - 2.5, minY + SHAFT_DEPTH + 5.5, Z + 0.5), true),
							new View("shallow", new Vec3(X - 6.5, minY + SHAFT_DEPTH + 1.7, Z + 0.5), true)};
					for (boolean nightVision : new boolean[] {false, true}) {
						singleplayer.getServer().runOnServer(server -> {
							ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
							player.removeAllEffects();
							if (nightVision) {
								player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
							}
						});
						for (View view : views) {
							if (nightVision && !view.bothLightings()) {
								continue;
							}
							String name = "breach-void-layer-" + layer + "-" + view.name() + (nightVision ? "-night-vision" : "");
							Path shot = lookUntilLit(context, singleplayer, layer, view.eye(), hole, minY, name);
							holeProblem(read(shot), specks, name).ifPresent(failures::add);
						}
					}
					if (layer == 1) {
						podUnderTheBottom(context, singleplayer, layer, minY).ifPresent(failures::add);
					}
				}
			} finally {
				context.runOnClient(client -> {
					if (client.gui.hud.isHidden() != hudWasHidden) {
						client.gui.hud.toggle();
					}
				});
			}
		}
		// Every view is taken first, so one run names all the views that show the void.
		if (!failures.isEmpty()) {
			throw new AssertionError(String.join("\n", failures));
		}
	}

	/**
	 * Carves a lit room, and a column over its middle, with a 9 x 9 hole through the floor to the void. Four glowstone lamps stand on the
	 * floor round it. Returns the bottom Y.
	 */
	private static int openHole(ServerLevel level) {
		int minY = level.getMinY();
		RoomCarver.carve(level, new BlockPos(X - ROOM_RADIUS, minY + SHAFT_DEPTH, Z - ROOM_RADIUS),
				new BlockPos(X + ROOM_RADIUS, minY + SHAFT_DEPTH + ROOM_HEIGHT, Z + ROOM_RADIUS), Blocks.AIR.defaultBlockState());
		RoomCarver.carve(level, new BlockPos(X - HOLE_RADIUS, minY + SHAFT_DEPTH, Z - HOLE_RADIUS),
				new BlockPos(X + HOLE_RADIUS, minY + SHAFT_DEPTH + COLUMN_HEIGHT, Z + HOLE_RADIUS), Blocks.AIR.defaultBlockState());
		BlockPos low = new BlockPos(X - HOLE_RADIUS, minY, Z - HOLE_RADIUS);
		BlockPos high = new BlockPos(X + HOLE_RADIUS, minY + SHAFT_DEPTH - 1, Z + HOLE_RADIUS);
		if (level.dimension().equals(LayerChain.dimension(LayerChain.SURFACE))) {
			// The surface has no crust: its floor is open, and the hole is a shaft bored to the bottom.
			RoomCarver.carve(level, low, high, Blocks.AIR.defaultBlockState());
		} else {
			for (BlockPos pos : BlockPos.betweenClosed(low, high)) {
				if (!BreachService.breakCrust(level, pos)) {
					throw new AssertionError("The floor of " + level.dimension().identifier() + " at " + pos + " holds no breach crust");
				}
			}
		}
		for (BlockPos lamp : lamps(minY)) {
			level.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), 3);
		}
		level.setBlock(holeLamp(minY), Blocks.GLOWSTONE.defaultBlockState(), 3);
		level.setBlock(wallLamp(minY), Blocks.GLOWSTONE.defaultBlockState(), 3);
		return minY;
	}

	private static List<BlockPos> lamps(int minY) {
		int y = minY + SHAFT_DEPTH;
		return List.of(new BlockPos(X + LAMP_OFFSET, y, Z), new BlockPos(X - LAMP_OFFSET, y, Z + 3),
				new BlockPos(X, y, Z + LAMP_OFFSET), new BlockPos(X, y, Z - LAMP_OFFSET));
	}

	/** A lamp in the east wall of the hole, for a camera that looks steeply down and sees no floor lamp. */
	private static BlockPos holeLamp(int minY) {
		return new BlockPos(X + HOLE_RADIUS + 1, minY + 1, Z);
	}

	/** A lamp in the wall of the column, for a camera high in it, where the floor lamps are out of sight under the room's roof. */
	private static BlockPos wallLamp(int minY) {
		return new BlockPos(X + HOLE_RADIUS + 1, minY + SHAFT_DEPTH + COLUMN_HEIGHT - 12, Z);
	}

	/** Points that are lit when seen: the tops of the floor lamps, and the faces of the two wall lamps that look into the hole and the column. */
	private static List<Vec3> landmarks(int minY) {
		List<Vec3> points = new ArrayList<>();
		for (BlockPos lamp : lamps(minY)) {
			points.add(new Vec3(lamp.getX() + 0.5, lamp.getY() + 1, lamp.getZ() + 0.5));
		}
		for (BlockPos lamp : List.of(holeLamp(minY), wallLamp(minY))) {
			points.add(new Vec3(lamp.getX(), lamp.getY() + 0.5, lamp.getZ() + 0.5));
		}
		return points;
	}

	/** Puts the camera at {@code eye} looking at {@code target}, waits until it has arrived and the view has rendered, and takes the screenshot. */
	private static Path look(ClientGameTestContext context, TestSingleplayerContext singleplayer, int layer, Vec3 eye, Vec3 target, String name) {
		float[] angles = anglesTo(eye, target);
		singleplayer.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst()
				.teleportTo(server.getLevel(LayerChain.dimension(layer)), eye.x, eye.y - EYE, eye.z, Set.of(), angles[0], angles[1], true));
		ClientWait.until(context, "the camera at " + name, client -> client.level.dimension().equals(LayerChain.dimension(layer))
				&& client.player.distanceToSqr(eye.x, eye.y - EYE, eye.z) < 0.0001
				&& Math.abs(Mth.wrapDegrees(client.player.getYRot() - angles[0])) < 0.01f
				&& Math.abs(client.player.getXRot() - angles[1]) < 0.01f);
		ClientWait.until(context, "the view " + name + " rendered and lit",
				() -> context.computeOnClient(client -> client.gui.screen() == null && client.levelRenderer.hasRenderedAllSections())
						&& singleplayer.getServer().computeOnServer(server -> !server.getLevel(LayerChain.dimension(layer)).getLightEngine().hasLightWork()),
				() -> "chunks still rendering or light still settling");
		// tick-wait: the fog colour is computed per frame, and a few frames must pass after the last chunk section is built
		context.waitTicks(10);
		return context.takeScreenshot(name);
	}

	/** Yaw and pitch, in degrees, of a camera at {@code eye} that looks at {@code target}. */
	private static float[] anglesTo(Vec3 eye, Vec3 target) {
		Vec3 d = target.subtract(eye);
		return new float[] {(float) Math.toDegrees(Math.atan2(-d.x, d.z)), (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)))};
	}

	/**
	 * Takes the view until the frame is a lit picture of the room. The client reports its sections rendered and the light settled
	 * before the pictures of a freshly carved room are true (the first frames can be black, from stale meshes or light), so a frame
	 * with no lit landmark where the camera maths puts one is taken again, up to the wall-clock deadline of {@link ClientWait}. The
	 * hole is judged only on a frame that passed.
	 */
	private static Path lookUntilLit(ClientGameTestContext context, TestSingleplayerContext singleplayer, int layer, Vec3 eye, Vec3 target, int minY,
			String name) {
		FarChunks.Deadline deadline = FarChunks.deadline();
		while (true) {
			Path shot = look(context, singleplayer, layer, eye, target, name);
			Optional<String> problem = litProblem(context, read(shot), eye, target, minY, name);
			if (problem.isEmpty()) {
				return shot;
			}
			if (deadline.expired()) {
				throw new AssertionError("Timed out after " + FarChunks.WAIT_SECONDS + " s: " + problem.get());
			}
			// tick-wait: a few more frames for the client's meshes and light to catch up with the carved room
			context.waitTicks(10);
		}
	}

	/**
	 * Why the frame is not a lit picture of the room, or empty. A landmark (a lamp, or a lit wall block) that the camera maths puts on
	 * the screen must show a lit pixel within {@link #WINDOW} pixels of where it should be, so a black or blank frame or a camera in
	 * rock cannot pass. The window is wide because the maths is a pinhole's, and a block face has size.
	 */
	private static Optional<String> litProblem(ClientGameTestContext context, BufferedImage image, Vec3 eye, Vec3 target, int minY, String name) {
		float[] angles = anglesTo(eye, target);
		double fov = context.computeOnClient(client -> (double) client.options.fov().get());
		boolean onScreen = false;
		for (Vec3 landmark : landmarks(minY)) {
			Optional<int[]> pixel = project(image, eye, angles[0], angles[1], fov, landmark);
			if (pixel.isPresent()) {
				onScreen = true;
				if (litNear(image, pixel.get()[0], pixel.get()[1])) {
					return Optional.empty();
				}
			}
		}
		return Optional.of(onScreen
				? name + ": the frame is dark where a lamp should show: a black or blank frame, or a camera in rock"
				: name + ": no lamp is on the screen by the camera maths, so the frame has nothing to prove it is a picture of the room");
	}

	private static boolean litNear(BufferedImage image, int px, int py) {
		for (int y = Math.max(0, py - WINDOW); y <= Math.min(image.getHeight() - 1, py + WINDOW); y++) {
			for (int x = Math.max(0, px - WINDOW); x <= Math.min(image.getWidth() - 1, px + WINDOW); x++) {
				if (brightest(image.getRGB(x, y)) > LIT) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The camera looks at the middle of the hole, so the patch of pixels at the middle of the screen holds only darkness: at most
	 * {@code specks} pixels brighter than {@link #DARK} in any channel.
	 */
	private static Optional<String> holeProblem(BufferedImage image, int specks, String name) {
		List<Integer> patch = centrePatch(image);
		int bright = (int) patch.stream().filter(channel -> channel > DARK).count();
		int brightest = patch.stream().max(Integer::compare).orElseThrow();
		if (bright > specks) {
			return Optional.of("%s: the hole shows the void, not darkness: %d pixels of its middle are brighter than %d in a channel, up to %d"
					.formatted(name, bright, DARK, brightest));
		}
		return Optional.empty();
	}

	/** The pixel where {@code point} shows for a camera at {@code eye}, or empty when it is off the picture or behind the camera. */
	private static Optional<int[]> project(BufferedImage image, Vec3 eye, float yawDegrees, float pitchDegrees, double fovDegrees, Vec3 point) {
		double yaw = Math.toRadians(yawDegrees);
		double pitch = Math.toRadians(pitchDegrees);
		Vec3 forward = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
		Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
		Vec3 up = right.cross(forward);
		Vec3 d = point.subtract(eye);
		double depth = d.dot(forward);
		if (depth <= 0) {
			return Optional.empty();
		}
		double tan = Math.tan(Math.toRadians(fovDegrees) / 2);
		double aspect = image.getWidth() / (double) image.getHeight();
		int x = (int) Math.round(image.getWidth() / 2.0 * (1 + d.dot(right) / depth / (tan * aspect)));
		int y = (int) Math.round(image.getHeight() / 2.0 * (1 - d.dot(up) / depth / tan));
		if (x < EDGE_MARGIN || y < EDGE_MARGIN || x >= image.getWidth() - EDGE_MARGIN || y >= image.getHeight() - EDGE_MARGIN) {
			return Optional.empty();
		}
		return Optional.of(new int[] {x, y});
	}

	/**
	 * A pod 1 block under the bottom of the world, where a crossing pod is for its last ticks, seen from over the hole. The cover must
	 * lie under it, not through it, so the pod's top shows lit and is not black.
	 */
	private static Optional<String> podUnderTheBottom(ClientGameTestContext context, TestSingleplayerContext singleplayer, int layer, int minY) {
		singleplayer.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst()
				.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false)));
		int[] podId = new int[1];
		singleplayer.getServer().runOnServer(server -> {
			ServerLevel level = server.getLevel(LayerChain.dimension(layer));
			PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
			pod.setNoGravity(true);
			pod.setPos(X + 0.5, minY - 1.0, Z + 0.5);
			level.addFreshEntity(pod);
			podId[0] = pod.getId();
		});
		try {
			Vec3 eye = new Vec3(X + 0.5, minY + SHAFT_DEPTH + 6, Z + 0.5);
			Vec3 podTop = new Vec3(X + 0.5, minY + 0.5, Z + 0.5);
			String name = "breach-void-layer-" + layer + "-pod-under-the-bottom";
			ClientWait.until(context, "the pod under the bottom on the client", client -> client.level.getEntity(podId[0]) != null);
			Path shot = look(context, singleplayer, layer, eye, podTop, name);
			BufferedImage image = read(shot);
			List<Integer> patch = centrePatch(image);
			int lit = (int) patch.stream().filter(channel -> channel > DARK).count();
			int pixels = patch.size();
			return lit * 2 < pixels ? Optional.of(name + ": the pod under the bottom is hidden: only " + lit + " of " + pixels + " pixels where it is are brighter than " + DARK) : Optional.empty();
		} finally {
			singleplayer.getServer().runOnServer(server -> server.getLevel(LayerChain.dimension(layer)).getEntity(podId[0]).discard());
		}
	}

	/** The brightest channel of each pixel of the {@link #PATCH} square at the middle of the picture. */
	private static List<Integer> centrePatch(BufferedImage image) {
		List<Integer> channels = new ArrayList<>();
		for (int y = image.getHeight() / 2 - PATCH; y <= image.getHeight() / 2 + PATCH; y++) {
			for (int x = image.getWidth() / 2 - PATCH; x <= image.getWidth() / 2 + PATCH; x++) {
				channels.add(brightest(image.getRGB(x, y)));
			}
		}
		return channels;
	}

	private static int brightest(int rgb) {
		return Math.max((rgb >> 16) & 0xFF, Math.max((rgb >> 8) & 0xFF, rgb & 0xFF));
	}

	private static BufferedImage read(Path shot) {
		try {
			return ImageIO.read(shot.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
