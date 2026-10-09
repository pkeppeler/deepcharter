package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.pod.PodConcept;
import io.github.pkeppeler.deepcharter.client.pod.PodGeoRenderer;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Evidence scenario "pod-concepts" (#334): each Mole concept in turn, picked with the dev switch and a resource reload.
 *
 * <p>Per concept, in a lit stone studio: a turntable of the parked pod (front, side, back), the camera rising to look down, and
 * stills {@code <id>-front}, {@code -side}, {@code -back}, {@code -above} and {@code -unlit} (parked, lamps off). Then in a dark
 * cave, with an invisible pilot aboard and a lights part fitted: the still {@code <id>-lit}, and a clip of the pod driving into a
 * wall and boring it, boring the floor, and flying up out of its hole.
 *
 * <p>Every concept takes the same {@value #FRAMES_PER_CONCEPT} frames: {@value #TURNTABLE_FRAMES} of turntable, then
 * {@value #CLIP_FRAMES} of clip. So concept n (from 0, in {@link PodConcept} order) is frames {@code n * 113 + 1} to
 * {@code n * 113 + 45} (turntable) and {@code n * 113 + 46} to {@code n * 113 + 113} (clip), which is how the per-concept GIFs are cut.
 */
public class PodConceptsScenario extends EvidenceScenario {
	private static final int X = 6000;
	private static final int Z = 6000;
	private static final int FLOOR = 4;
	/** The cave is this far east of the studio. */
	private static final int CAVE_OFFSET = 24;
	private static final int ROOM_HALF = 6;
	private static final int ROOM_HEIGHT = 5;
	private static final double EYE = 1.62;
	private static final int SETTLE_POLLS = 5;

	private static final int TURN_FRAMES = 36;
	private static final int RISE_FRAMES = 9;
	private static final int TURNTABLE_FRAMES = TURN_FRAMES + RISE_FRAMES;
	private static final int WALL_FRAMES = 30;
	private static final int FLOOR_FRAMES = 22;
	private static final int FLY_FRAMES = 16;
	private static final int CLIP_FRAMES = WALL_FRAMES + FLOOR_FRAMES + FLY_FRAMES;
	private static final int FRAMES_PER_CONCEPT = TURNTABLE_FRAMES + CLIP_FRAMES;
	private static final int TICKS_PER_CLIP_FRAME = 3;

	private static final double TURNTABLE_DISTANCE = 3.4;
	private static final double TURNTABLE_PITCH = 18;
	private static final double ABOVE_PITCH = 86;
	/** The middle of the Mole's hull, which the camera looks at. */
	private static final double POD_MIDDLE = 0.9;

	private static final Input DRIVE = new Input(true, false, false, false, false, false, false);
	private static final Input BORE_DOWN = new Input(false, false, false, false, false, false, true);
	private static final Input LIFT = new Input(false, false, false, false, true, false, false);

	private ClientGameTestContext ctx;
	private TestSingleplayerContext world;
	private PodEntity parked;
	private PodEntity piloted;
	private MockPlayer pilot;
	private int framesTaken;

	@Override
	protected String name() {
		return "pod-concepts";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		ctx = context;
		if (System.getProperty(PodConcept.PROPERTY) != null) {
			throw new AssertionError("the dev switch " + PodConcept.PROPERTY + " is already set; this scenario sets it itself");
		}
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			world = singleplayer;
			setUp();
			try {
				for (PodConcept concept : PodConcept.values()) {
					select(concept);
					rebuildCave();
					turntable(concept);
					cave(concept);
					if (framesTaken != (concept.ordinal() + 1) * FRAMES_PER_CONCEPT) {
						throw new AssertionError("concept " + concept.id() + " ended at frame " + framesTaken + ", not " + (concept.ordinal() + 1) * FRAMES_PER_CONCEPT
								+ ": the GIFs are cut at fixed frames");
					}
				}
			} finally {
				System.clearProperty(PodConcept.PROPERTY);
				reloadAndWait("the Mole back on its shipping renderer", client -> !(moleRenderer(client) instanceof PodGeoRenderer));
			}
		}
	}

	/** A creative camera in layer 1 with the HUD hidden, no mobs, a parked Mole in the studio and a piloted one in the cave. */
	private void setUp() {
		serverDo(server -> {
			GameRules rules = server.getGameRules();
			rules.set(GameRules.SPAWN_MOBS, false, server);
			rules.set(GameRules.SPAWN_MONSTERS, false, server);
			rules.set(GameRules.RANDOM_TICK_SPEED, 0, server);
			ServerLevel one = layerOne(server);
			ServerPlayer camera = server.getPlayerList().getPlayers().getFirst();
			camera.setGameMode(GameType.CREATIVE);
			camera.getAbilities().mayfly = true;
			camera.getAbilities().flying = true;
			camera.onUpdateAbilities();
			camera.setNoGravity(true);
			camera.setPermanentlyInvulnerable(true);
			camera.teleportTo(one, X, FLOOR + 1, Z - 4, Set.of(), 0f, 0f, true);
		});
		ClientWait.until(ctx, "the camera in layer 1", client -> client.player != null && client.level.dimension().equals(LayerChain.dimension(1)));
		serverDo(server -> {
			ServerLevel one = layerOne(server);
			buildStudio(one);
			buildCave(one);
			parked = PodRegistry.POD.create(one, EntitySpawnReason.COMMAND);
			parked.setPos(X + 0.5, FLOOR, Z + 0.5);
			one.addFreshEntity(parked);
			pilot = MockPlayers.join(server, "Pilot");
			pilot.teleportTo(one, new Vec3(X + CAVE_OFFSET, FLOOR, Z), -90f, 0f);
			pilot.player().setInvisible(true);
			pilot.player().setPermanentlyInvulnerable(true);
			// An invisible player still shows what it holds, and the handbook it always carries sits in the first slot: hold the last.
			pilot.player().getInventory().setSelectedSlot(8);
			if (Charters.found(server, pilot.player().getUUID(), "Concept Works").isPresent()) {
				throw new AssertionError("founding the pilot's charter should succeed");
			}
			CharterId charter = Charters.charterOfOrThrow(server, pilot.player().getUUID()).orElseThrow().id();
			piloted = PodRegistry.POD.create(one, EntitySpawnReason.COMMAND);
			piloted.setPos(X + CAVE_OFFSET + 0.5, FLOOR, Z + 0.5);
			piloted.setFuel(100f);
			one.addFreshEntity(piloted);
			PodComponents.register(piloted, charter);
			PodComponents.install(piloted, ComponentItems.mint(server, ComponentTrack.LIGHTS, 2, charter));
			if (!pilot.player().startRiding(piloted)) {
				throw new AssertionError("the pilot could not board the cave's Mole");
			}
		});
		ctx.runOnClient(client -> {
			client.options.setCameraType(CameraType.FIRST_PERSON);
			if (!client.gui.hud.isHidden()) {
				client.gui.hud.toggle();
			}
		});
		ClientWait.until(ctx, "both Moles and the pilot aboard on the client", client -> client.level.getEntity(parked.getId()) instanceof PodEntity
				&& client.level.getEntity(piloted.getId()) instanceof PodEntity pod && pod.getControllingPassenger() != null);
	}

	/** Sets the dev switch to {@code concept} and reloads the resources, which rebuilds the renderers. */
	private void select(PodConcept concept) {
		System.setProperty(PodConcept.PROPERTY, concept.id());
		reloadAndWait("the Mole drawn as " + concept.id(), client -> moleRenderer(client) instanceof PodGeoRenderer geo && geo.concept() == concept);
	}

	private void reloadAndWait(String what, Predicate<Minecraft> drawn) {
		ctx.runOnClient(Minecraft::reloadResourcePacks);
		ClientWait.until(ctx, what, client -> drawn.test(client) && client.gui.overlay() == null);
	}

	/** The renderer the client draws the parked Mole with, or null before the client has the Mole. */
	private Object moleRenderer(Minecraft client) {
		var mole = client.level == null ? null : client.level.getEntity(parked.getId());
		return mole == null ? null : client.getEntityRenderDispatcher().getRenderer(mole);
	}

	// ------------------------------------------------------------------------------------------------ the studio

	private void turntable(PodConcept concept) {
		serverDo(server -> parked.setPos(X + 0.5, FLOOR, Z + 0.5));
		Vec3 pod = new Vec3(X + 0.5, FLOOR, Z + 0.5);
		Vec3 front = orbit(pod, 0, TURNTABLE_PITCH);
		look(front, pod);
		settle();
		for (int i = 0; i < TURN_FRAMES; i++) {
			float yaw = i * 360f / TURN_FRAMES;
			turnParked(yaw);
			ctx.waitTick();
			frame();
			if (i == 0) {
				shot(concept, "front");
			} else if (i == TURN_FRAMES / 4) {
				shot(concept, "side");
			} else if (i == TURN_FRAMES / 2) {
				shot(concept, "back");
			}
		}
		turnParked(0);
		for (int i = 1; i <= RISE_FRAMES; i++) {
			double pitch = TURNTABLE_PITCH + (ABOVE_PITCH - TURNTABLE_PITCH) * i / RISE_FRAMES;
			look(orbit(pod, 0, pitch), pod);
			ctx.waitTick();
			frame();
		}
		settle();
		shot(concept, "above");
		turnParked(-35f);
		look(orbit(pod, 0, 24), pod);
		settle();
		shot(concept, "unlit");
	}

	/** The parked pod faces {@code yaw}: on the server, so a position sync carries the same turn, and on the client at once. */
	private void turnParked(float yaw) {
		serverDo(server -> parked.setYRot(yaw));
		ctx.runOnClient(client -> {
			var pod = client.level.getEntity(parked.getId());
			pod.setYRot(yaw);
			pod.yRotO = yaw;
		});
	}

	// ------------------------------------------------------------------------------------------------ the cave

	private void cave(PodConcept concept) {
		// The pod stands facing +z (south), where it was set down; the camera is in front of it, a little to its left.
		Vec3 start = new Vec3(X + CAVE_OFFSET - 2.5, FLOOR, Z + 0.5);
		placePiloted(start, -90f);
		look(start.add(1.3, EYE - 0.1, 3.1), start);
		settle();
		shot(concept, "lit");
		// Drive east into the wall and bore it, seen from the south-west so the camera stays in the cave.
		serverDo(server -> pilot.setInput(DRIVE));
		for (int i = 0; i < WALL_FRAMES; i++) {
			clipFrame(new Vec3(-1.4, 1.2, 3.3));
			if (i == WALL_FRAMES - 6) {
				shot(concept, "boring-the-wall");
			}
		}
		// Back to the middle of the cave, then bore the floor.
		releasePilot();
		placePiloted(new Vec3(X + CAVE_OFFSET - 1.5, FLOOR, Z + 0.5), -90f);
		serverDo(server -> pilot.setInput(BORE_DOWN));
		for (int i = 0; i < FLOOR_FRAMES; i++) {
			clipFrame(new Vec3(1.9, 1.6, 3.0));
		}
		// And fly up out of the hole.
		serverDo(server -> pilot.setInput(LIFT));
		for (int i = 0; i < FLY_FRAMES; i++) {
			clipFrame(new Vec3(1.9, 0.6, 3.4));
			if (i == FLY_FRAMES / 2) {
				shot(concept, "flying");
			}
		}
		releasePilot();
	}

	/** One frame of a clip: the camera keeps {@code offset} from the pod, so it rides along, but stays between the cave's floor and roof. */
	private void clipFrame(Vec3 offset) {
		Vec3 pod = serverGet(server -> piloted.position());
		Vec3 eye = pod.add(offset);
		look(new Vec3(eye.x, Mth.clamp(eye.y, FLOOR + 1.0, FLOOR + ROOM_HEIGHT - 0.4), eye.z), pod);
		ctx.waitTicks(TICKS_PER_CLIP_FRAME); // tick-wait: the clip is cut at fixed frames
		frame();
	}

	/** Sets the cave's Mole down at {@code at}, full of fuel and whole, with the pilot aboard looking {@code pilotYaw}. */
	private void placePiloted(Vec3 at, float pilotYaw) {
		serverDo(server -> {
			piloted.setDeltaMovement(Vec3.ZERO);
			piloted.setPos(at);
			piloted.setFuel(100f);
			piloted.setHull(piloted.maxHull());
			piloted.setStranded(false);
			if (pilot.player().getVehicle() != piloted && !pilot.player().startRiding(piloted)) {
				throw new AssertionError("the pilot could not board the cave's Mole again");
			}
			pilot.player().setYRot(pilotYaw);
		});
		ClientWait.until(ctx, "the pilot aboard the cave's Mole on the client",
				client -> client.level.getEntity(piloted.getId()) instanceof PodEntity pod && pod.getControllingPassenger() != null);
	}

	private void releasePilot() {
		serverDo(server -> pilot.releaseInput());
	}

	// ------------------------------------------------------------------------------------------------ rooms

	/** The cave again, whole: the last concept bored its wall and floor. */
	private void rebuildCave() {
		serverDo(server -> buildCave(layerOne(server)));
		settle();
	}

	/** A solid stone block carved hollow, so the walls are plain stone and not the layer's rock, with stone left under the floor and on the east side. */
	private static void carveRoom(ServerLevel level, int centre) {
		RoomCarver.carve(level, centre - ROOM_HALF - 1, centre + ROOM_HALF + 4, FLOOR - 6, FLOOR + ROOM_HEIGHT + 1, Z - ROOM_HALF - 1, Z + ROOM_HALF + 1,
				Blocks.STONE);
		RoomCarver.carve(level, new BlockPos(centre - ROOM_HALF, FLOOR, Z - ROOM_HALF), new BlockPos(centre + ROOM_HALF - 2, FLOOR + ROOM_HEIGHT, Z + ROOM_HALF),
				Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
	}

	/** The studio, lit by glowstone in its roof and round its walls. Built once: nothing bores it. */
	private static void buildStudio(ServerLevel level) {
		carveRoom(level, X);
		for (int dx = -5; dx <= 3; dx += 2) {
			for (int dz = -5; dz <= 5; dz += 2) {
				level.setBlock(new BlockPos(X + dx, FLOOR + ROOM_HEIGHT + 1, Z + dz), Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
		for (int d = -5; d <= 5; d += 2) {
			level.setBlock(new BlockPos(X - ROOM_HALF - 1, FLOOR + 1, Z + d), Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_ALL);
			level.setBlock(new BlockPos(X + ROOM_HALF - 1, FLOOR + 1, Z + d), Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_ALL);
			level.setBlock(new BlockPos(X + d, FLOOR + 1, Z + ROOM_HALF + 1), Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_ALL);
			level.setBlock(new BlockPos(X + d, FLOOR + 1, Z - ROOM_HALF - 1), Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_ALL);
		}
	}

	/** The cave: dark but for a faint light of 5 in two corners of its roof, so a clip shows the pod's shape and not only its lamps. */
	private static void buildCave(ServerLevel level) {
		carveRoom(level, X + CAVE_OFFSET);
		for (BlockPos corner : new BlockPos[] {new BlockPos(X + CAVE_OFFSET - 5, FLOOR + ROOM_HEIGHT, Z - 5), new BlockPos(X + CAVE_OFFSET + 3, FLOOR + ROOM_HEIGHT, Z + 5)}) {
			level.setBlock(corner, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 5), Block.UPDATE_ALL);
		}
	}

	// ------------------------------------------------------------------------------------------------ camera

	/** A camera point {@code distance} from the pod's middle, in front of a pod facing +z, {@code pitch} degrees up. */
	private static Vec3 orbit(Vec3 pod, double yaw, double pitch) {
		double p = Math.toRadians(pitch);
		double y = Math.toRadians(yaw);
		double flat = TURNTABLE_DISTANCE * Math.cos(p);
		return pod.add(-Math.sin(y) * flat, POD_MIDDLE + TURNTABLE_DISTANCE * Math.sin(p), Math.cos(y) * flat);
	}

	/** Puts the camera's eye at {@code eye}, looking at the middle of a pod standing at {@code pod}. */
	private void look(Vec3 eye, Vec3 pod) {
		Vec3 target = pod.add(0, POD_MIDDLE, 0);
		Vec3 d = target.subtract(eye);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
		serverDo(server -> server.getPlayerList().getPlayers().getFirst().teleportTo(layerOne(server), eye.x, eye.y - EYE, eye.z, Set.of(), yaw, pitch, true));
	}

	/**
	 * Waits until the light has settled and every section in view has rendered, and both have held for {@value #SETTLE_POLLS} polls in a
	 * row: block changes reach the client a little after the server makes them, so one good poll can come before the sections go dirty.
	 */
	private void settle() {
		int[] stable = {0};
		ClientWait.until(ctx, "the view settled", () -> {
			boolean ready = !serverGet(server -> layerOne(server).getLightEngine().hasLightWork())
					&& ctx.computeOnClient(client -> client.levelRenderer.hasRenderedAllSections());
			stable[0] = ready ? stable[0] + 1 : 0;
			return stable[0] >= SETTLE_POLLS;
		}, () -> ctx.computeOnClient(ClientWait::describe));
	}

	private void frame() {
		frame(ctx);
		framesTaken++;
	}

	private void shot(PodConcept concept, String view) {
		screenshot(ctx, concept.id() + "-" + view);
	}

	private void serverDo(Consumer<MinecraftServer> action) {
		world.getServer().runOnServer(action::accept);
	}

	private <T> T serverGet(Function<MinecraftServer, T> query) {
		return world.getServer().computeOnServer(query::apply);
	}

	private static ServerLevel layerOne(MinecraftServer server) {
		return server.getLevel(LayerChain.dimension(1));
	}
}
