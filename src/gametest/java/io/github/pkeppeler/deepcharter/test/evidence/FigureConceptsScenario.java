package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.creature.FigureConcept;
import io.github.pkeppeler.deepcharter.client.creature.FigureConceptRenderer;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.Chassis;
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
 * Evidence scenario "figure-concepts" (#250): each lampless figure concept in turn, picked with the dev switch and a resource reload.
 *
 * <p>The scene is a sealed chamber of stone in layer 1, where the figure is met: layer 1's fog and ambient light, dark but for one pod with its
 * lights fitted. The stills are {@code <id>-rock} (the figure at the edge of the lamp's reach, from the pod, with nothing lit behind it),
 * {@code -rock-lit} (side-on, in the same rock, with the lamp lighting the wall behind the figure so that it reads as a silhouette),
 * {@code -front} (from the far end, with the pod's wall lit behind it), {@code -head} (a close-up of the hat and the empty bracket, with the pod's
 * light close by) and {@code -scale} (a miner beside it), and a short clip of the front view as it idles.
 *
 * <p>Every segment's frames are logged as {@code [figure-concepts-segment] <name> <first>-<last>}, which is how the GIFs are cut.
 */
public class FigureConceptsScenario extends EvidenceScenario {
	/** The chamber, in blocks from its floor corner: x runs down the chamber, z across it. Interior only; the shell is one block more. */
	private static final int LENGTH = 12;
	private static final int HALF_WIDTH = 3;
	private static final int HEIGHT = 5;
	/** The layer the chamber is cut into, and where: the terrain there is noise, so the chamber is carved and sealed in the rock. */
	private static final int LAYER = 1;
	private static final BlockPos FLOOR = new BlockPos(2000, 100, 2000);
	/** The tier of lights fitted to the pod: 4 is the brightest. */
	private static final int LIGHTS_TIER = 3;

	/** Where things stand, as block coordinates in the chamber (x down, y up from the floor, z across), at the centre of the block. */
	private static final Vec3 POD = new Vec3(3.5, 1, -1.5);
	/** Where the figure stands at the edge of the lamp's reach (block light 3 at its feet), and where it stands in the lit stills (block light 9). */
	private static final Vec3 EDGE = new Vec3(9.5, 1, 0.5);
	private static final Vec3 LIT = new Vec3(6.0, 1, 0.5);
	/** The miner stands beside the figure in the lit stills, along the chamber. */
	private static final Vec3 MINER = new Vec3(7.5, 1, 0.5);
	/** Yaw 90 is toward -x, the pod; yaw 270 is toward +x, the far end. */
	private static final float TOWARD_POD = 90f;
	private static final float TOWARD_FAR_END = 270f;
	private static final float POD_YAW = -90f;
	/** The walk starts at the far wall's foot and heads south, across the chamber. */
	private static final Vec3 WALK_START = new Vec3(10.5, 1, -2.5);
	private static final int WALK_FRAMES = 40;

	private static final double EYE = 1.62;
	private static final int SETTLE_POLLS = 5;
	private static final int IDLE_TICKS = 40;
	private static final int CLIP_FRAMES = 36;
	private static final int TICKS_PER_FRAME = 3;

	private ClientGameTestContext ctx;
	private TestSingleplayerContext world;
	private int figureId;
	private int podId;
	private MockPlayer miner;
	private int framesTaken;

	@Override
	protected String name() {
		return "figure-concepts";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		ctx = context;
		if (System.getProperty(FigureConcept.PROPERTY) != null) {
			throw new AssertionError("the dev switch " + FigureConcept.PROPERTY + " is already set; the scenario sets it itself");
		}
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			world = singleplayer;
			CameraType cameraType = context.computeOnClient(client -> client.options.getCameraType());
			boolean hudHidden = context.computeOnClient(client -> client.gui.hud.isHidden());
			try {
				setUp();
					logLight();
				for (FigureConcept concept : FigureConcept.values()) {
					showcase(concept);
				}
				darkenForWalks();
				for (FigureConcept concept : FigureConcept.values()) {
					walk(concept);
				}
			} finally {
				System.clearProperty(FigureConcept.PROPERTY);
				context.runOnClient(client -> {
					client.options.setCameraType(cameraType);
					if (client.gui.hud.isHidden() != hudHidden) {
						client.gui.hud.toggle();
					}
				});
			}
		}
	}

	/** Takes the camera into layer 1, builds the chamber there, lights the pod, hides the HUD and makes the camera a creative flier that nothing can hurt. */
	private void setUp() {
		ctx.waitTicks(40); // tick-wait: the world's spawn settles before the camera is moved
		serverDo(server -> {
			GameRules rules = server.getGameRules();
			rules.set(GameRules.SPAWN_MOBS, false, server);
			rules.set(GameRules.SPAWN_MONSTERS, false, server);
			ServerPlayer camera = server.getPlayerList().getPlayers().getFirst();
			camera.setGameMode(GameType.CREATIVE);
			camera.getAbilities().mayfly = true;
			camera.getAbilities().flying = true;
			camera.onUpdateAbilities();
			camera.setNoGravity(true);
			camera.setPermanentlyInvulnerable(true);
			Charters.found(server, camera.getUUID(), "Demo Charter");
			camera.teleportTo(layer(server), FLOOR.getX() + 0.5, FLOOR.getY() + 3, FLOOR.getZ() + 0.5, Set.of(), 0f, 0f, true);
		});
		ClientWait.until(ctx, "the camera in layer " + LAYER, client -> client.level != null && client.level.dimension().equals(LayerChain.dimension(LAYER)) && client.gui.screen() == null);
		serverDo(server -> {
			ServerPlayer camera = server.getPlayerList().getPlayers().getFirst();
			ServerLevel level = layer(server);
			buildChamber(level);
			PodEntity pod = PodRegistry.typeOf(Chassis.PROSPECTOR).create(level, EntitySpawnReason.COMMAND);
			pod.setPos(at(POD));
			pod.setYRot(POD_YAW);
			level.addFreshEntity(pod);
			podId = pod.getId();
			CharterId charter = Charters.charterOfOrThrow(server, camera.getUUID()).orElseThrow().id();
			PodComponents.register(pod, charter);
			PodComponents.install(pod, ComponentItems.mint(server, ComponentTrack.LIGHTS, LIGHTS_TIER, charter));
			LamplessFigure figure = CreatureRegistry.LAMPLESS_FIGURE.create(level, EntitySpawnReason.COMMAND);
			figure.setNoAi(true);
			figure.setPos(at(EDGE));
			level.addFreshEntity(figure);
			figureId = figure.getId();
		});
		stand(EDGE, TOWARD_POD);
		ClientWait.until(ctx, "the figure on the client", client -> client.level != null && client.level.getEntity(figureId) instanceof LamplessFigure);
		ctx.runOnClient(client -> {
			client.options.setCameraType(CameraType.FIRST_PERSON);
			if (!client.gui.hud.isHidden()) {
				client.gui.hud.toggle();
			}
		});
	}

	/**
	 * Puts the figure at a place in the chamber facing a way, and waits for the idle: the move plays the walk on the client until the
	 * figure has been still a moment. It has no AI, so it stays where it is put.
	 */
	private void stand(Vec3 inChamber, float yaw) {
		serverDo(server -> {
			LamplessFigure figure = (LamplessFigure) layer(server).getEntity(figureId);
			figure.setPos(at(inChamber));
			figure.setYRot(yaw);
			figure.setYBodyRot(yaw);
			figure.setYHeadRot(yaw);
		});
		ctx.waitTicks(IDLE_TICKS); // tick-wait: the walk the move started ends and the idle runs
	}

	private static ServerLevel layer(MinecraftServer server) {
		return server.getLevel(LayerChain.dimension(LAYER));
	}

	/** Logs the block light (0 to 15) at the pod, under the figure in both places, on the wall behind it in the lit stills and on the far wall: how far the lamp reaches. */
	private void logLight() {
		settle();
		serverDo(server -> {
			ServerLevel level = layer(server);
			Vec3[] spots = {POD, EDGE, LIT, new Vec3(0.5, 3, 0.5), new Vec3(LIT.x, 2, -HALF_WIDTH + 0.5), new Vec3(LENGTH - 0.5, 3, 0.5)};
			for (Vec3 spot : spots) {
				System.out.println("[figure-concepts-light] " + spot + " block light " + level.getBrightness(LightLayer.BLOCK, BlockPos.containing(at(spot))));
			}
		});
	}

	/** Draws the figure with the concept's files: the dev switch and a resource reload. */
	private void draw(FigureConcept concept) {
		System.setProperty(FigureConcept.PROPERTY, concept.id());
		ctx.runOnClient(Minecraft::reloadResourcePacks);
		ClientWait.until(ctx, "the figure drawn as " + concept.id(), client -> renderer(client) instanceof FigureConceptRenderer drawn && drawn.concept() == concept,
				client -> String.valueOf(renderer(client)));
	}

	private void showcase(FigureConcept concept) {
		draw(concept);
		stand(EDGE, TOWARD_POD);

		// In the rock: from behind the pod, down the chamber to the figure at the edge of the light. Nothing is lit behind it.
		look(at(new Vec3(0.9, 3.7, 0.5)), at(EDGE).add(0, 1.4, 0));
		settle();
		screenshot(ctx, concept.id() + "-rock");

		// In the rock, lit: the figure nearer the pod, seen side-on across the chamber, with the lamp lighting the wall behind it.
		stand(LIT, TOWARD_POD);
		look(at(new Vec3(LIT.x, 2.2, 3.4)), at(LIT).add(0, 1.4, 0));
		settle();
		screenshot(ctx, concept.id() + "-rock-lit");

		// Front, from the far end, with the lit wall behind it, and the idle running on a few frames.
		stand(LIT, TOWARD_FAR_END);
		int first = framesTaken + 1;
		look(at(new Vec3(9.6, 2.3, 0.5)), at(LIT).add(0, 1.5, 0));
		settle();
		screenshot(ctx, concept.id() + "-front");
		for (int i = 0; i < CLIP_FRAMES; i++) {
			ctx.waitTicks(TICKS_PER_FRAME); // tick-wait: the clip is cut at fixed frames
			frame(ctx);
			framesTaken++;
		}
		System.out.println("[figure-concepts-segment] " + concept.id() + "-idle " + first + "-" + framesTaken);

		// The head: the hat and the empty bracket, a block off and a little above, with the pod's light close by.
		look(at(new Vec3(7.3, 3.8, 0.9)), at(LIT).add(0.3, 2.3, 0));
		settle();
		screenshot(ctx, concept.id() + "-head");

		// Beside a miner, for scale, side-on across the chamber.
		stand(LIT, TOWARD_POD);
		serverDo(server -> {
			miner = MockPlayers.join(server, "Miner");
			miner.teleportTo(layer(server), at(MINER), 90f, 0f);
			miner.player().setPermanentlyInvulnerable(true);
		});
		ClientWait.until(ctx, "the miner on the client", client -> client.level != null && client.level.getEntity(miner.player().getId()) != null);
		ctx.waitTicks(2); // tick-wait: the miner's skin and place reach the client
		look(at(new Vec3((LIT.x + MINER.x) / 2, 2.3, 3.6)), at(new Vec3((LIT.x + MINER.x) / 2, 2.2, 0.5)));
		settle();
		screenshot(ctx, concept.id() + "-scale");
		int minerId = miner.player().getId();
		serverDo(server -> miner.leave());
		ClientWait.until(ctx, "the miner gone from the client", client -> client.level.getEntity(minerId) == null);
	}

	/** The pod's lights go out and the camera sees by night vision: the figure may walk without a light on it, and still be seen. */
	private void darkenForWalks() {
		serverDo(server -> {
			((PodEntity) layer(server).getEntity(podId)).setStranded(true);
			server.getPlayerList().getPlayers().getFirst().addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
		});
		settle();
	}

	/**
	 * The figure with its AI on walks across the chamber from one wall toward the other, seen from the far end: far enough off that it does
	 * not fade for a player near it, and in the dark so it does not fade for a light. Its walk is the walk of the concept's animation file.
	 */
	private void walk(FigureConcept concept) {
		draw(concept);
		serverDo(server -> {
			LamplessFigure figure = (LamplessFigure) layer(server).getEntity(figureId);
			figure.setPos(at(WALK_START));
			figure.setHeading(Direction.SOUTH);
			figure.setNoAi(false);
		});
		look(at(new Vec3(0.6, 2.9, 0.5)), at(new Vec3(WALK_START.x, 1.5, 0.5)));
		settle();
		int first = framesTaken + 1;
		for (int i = 0; i < WALK_FRAMES; i++) {
			ctx.waitTicks(TICKS_PER_FRAME); // tick-wait: the clip is cut at fixed frames
			frame(ctx);
			framesTaken++;
		}
		System.out.println("[figure-concepts-segment] " + concept.id() + "-walk " + first + "-" + framesTaken);
		serverDo(server -> ((LamplessFigure) layer(server).getEntity(figureId)).setNoAi(true));
	}

	private EntityRenderer<?, ?> renderer(Minecraft client) {
		return client.getEntityRenderDispatcher().getRenderer(client.level.getEntity(figureId));
	}

	/** A point in the chamber as a world position. */
	private Vec3 at(Vec3 inChamber) {
		return new Vec3(FLOOR.getX() + inChamber.x, FLOOR.getY() + inChamber.y, FLOOR.getZ() + inChamber.z);
	}

	/**
	 * A stone chamber with its floor block at {@code FLOOR}: LENGTH blocks long, 2 * HALF_WIDTH + 1 wide, HEIGHT high, the sky unable to reach inside
	 * and no void or sky in view from any point of it. x runs from the floor block's corner, z from -HALF_WIDTH to HALF_WIDTH across it.
	 */
	private void buildChamber(ServerLevel level) {
		// Layer rock, the stone the layers' walls are: no backdrop is staged. RoomCarver seals the shell against worldgen lava first.
		BlockPos west = FLOOR.offset(-1, 0, -HALF_WIDTH - 1);
		BlockPos east = FLOOR.offset(LENGTH, HEIGHT + 1, HALF_WIDTH + 1);
		RoomCarver.carve(level, west, east, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		RoomCarver.carve(level, west.offset(1, 1, 1), east.offset(-1, -1, -1), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
	}

	private void look(Vec3 eye, Vec3 target) {
		Vec3 d = target.subtract(eye);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
		serverDo(server -> server.getPlayerList().getPlayers().getFirst().teleportTo(layer(server), eye.x, eye.y - EYE, eye.z, Set.of(), yaw, pitch, true));
	}

	/**
	 * Waits until the light has settled and every section in view has rendered, and both have held for {@value #SETTLE_POLLS} polls in a
	 * row: block changes reach the client a little after the server makes them, so one good poll can come before the sections go dirty.
	 */
	private void settle() {
		int[] stable = {0};
		ClientWait.until(ctx, "the view settled", () -> {
			boolean ready = !serverGet(server -> layer(server).getLightEngine().hasLightWork())
					&& ctx.computeOnClient(client -> client.levelRenderer.hasRenderedAllSections());
			stable[0] = ready ? stable[0] + 1 : 0;
			return stable[0] >= SETTLE_POLLS;
		}, () -> ctx.computeOnClient(ClientWait::describe));
	}

	private void serverDo(Consumer<MinecraftServer> action) {
		world.getServer().runOnServer(action::accept);
	}

	private <T> T serverGet(Function<MinecraftServer, T> query) {
		return world.getServer().computeOnServer(query::apply);
	}
}
