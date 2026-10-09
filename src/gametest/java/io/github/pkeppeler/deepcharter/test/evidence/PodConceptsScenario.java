package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.ClockInstance;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.pod.PodConcept;
import io.github.pkeppeler.deepcharter.client.pod.PodGeoRenderer;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.surface.SurfaceBlocks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Evidence scenario "pod-concepts": the Mole concepts the user is picking from now, round 2 (#352), each picked in turn with the
 * dev switch and a resource reload. Round 1 (#334) is recorded by this scenario at commit 20a09d42, and its media is on
 * pr-media/342.
 *
 * <p>The scene is the regolith plain south of the colony, in the design tour's world, under the brightest dusk of the sky (the
 * surface's own look, which the tour calls noon). Per concept: a turntable of the parked pod (front, three-quarter, side, back),
 * the camera rising to look down, and stills {@code <id>-front}, {@code -threequarter}, {@code -side}, {@code -back}, {@code -above}
 * and {@code -unlit} (parked, lamps off). Then, with an invisible pilot aboard and a lights part fitted: {@code <id>-first-person}
 * from the pilot's eye, and a clip of the pod driving into a ledge of rock and chewing into it (one tick a frame, so the cutter's
 * turn shows), boring the floor, and flying up out of its hole, with the stills {@code -chewing-the-ledge}, {@code -boring-the-floor}
 * and {@code -flying}. Last, {@code <id>-lit}: the pod at night, lamps on.
 *
 * <p>Every concept takes the same {@value #FRAMES_PER_CONCEPT} frames: {@value #TURNTABLE_FRAMES} of turntable, then
 * {@value #CLIP_FRAMES} of clip. So concept n (from 0, in {@link #CONCEPTS} order) is frames {@code n * 157 + 1} to
 * {@code n * 157 + 45} (turntable) and {@code n * 157 + 46} to {@code n * 157 + 157} (clip), which is how the per-concept GIFs are cut.
 */
public class PodConceptsScenario extends EvidenceScenario {
	/** Round 2: the Capsule with the Borer's cutter made giant. */
	private static final List<PodConcept> CONCEPTS = PodConcept.ofRound(2);
	/** The design tour's world, so the plain and the mesa behind it are the ones the tour shows. */
	private static final String SEED = "deepcharter-design-tour";
	/** The stage is the flattest ground from this far to {@value #STAGE_SEARCH} blocks south of the colony's centre, in steps of {@value #STAGE_STEP}. */
	private static final int STAGE_SOUTH = 48;
	private static final int STAGE_STEP = 6;
	private static final int STAGE_SEARCH = 240;
	/** The ground judged is both pads and this margin round them. */
	private static final int STAGE_MARGIN = 4;
	/** The most the ground may rise or fall over that area, in blocks: more, and a levelled pad is a pit or a shelf. */
	private static final int STAGE_ROUGHNESS = 4;
	/** The clip's ground is this far east of the turntable. */
	private static final int CLIP_EAST = 18;
	/** Half the side of each levelled pad of regolith. */
	private static final int PAD_HALF = 7;
	private static final int PAD_CLEARANCE = 10;
	private static final double EYE = 1.62;
	private static final int SETTLE_POLLS = 5;

	/** The gameplay clock, which sets the light, at noon and at night: the sky clock is pinned with it ({@link EvidenceWorld#skyPhase}). */
	private static final int NOON = 6_000;
	private static final int NIGHT = 18_000;

	private static final int TURN_FRAMES = 36;
	private static final int RISE_FRAMES = 9;
	private static final int TURNTABLE_FRAMES = TURN_FRAMES + RISE_FRAMES;
	private static final int APPROACH_FRAMES = 8;
	private static final int TICKS_PER_APPROACH_FRAME = 2;
	/** The ledge is chewed one tick a frame: at the giant cutters' turn, under half a tooth's spacing a frame, so the turn reads forward. */
	private static final int CHEW_FRAMES = 58;
	private static final int FLOOR_FRAMES = 30;
	private static final int FLY_FRAMES = 16;
	private static final int TICKS_PER_FLY_FRAME = 3;
	private static final int CLIP_FRAMES = APPROACH_FRAMES + CHEW_FRAMES + FLOOR_FRAMES + FLY_FRAMES;
	private static final int FRAMES_PER_CONCEPT = TURNTABLE_FRAMES + CLIP_FRAMES;

	private static final double TURNTABLE_DISTANCE = 3.4;
	private static final double TURNTABLE_PITCH = 16;
	private static final double ABOVE_PITCH = 86;
	/** The turntable frame of the three-quarter still: the pod turned 40 degrees from facing the camera, showing its front and its left side. */
	private static final int THREEQUARTER_FRAME = 4;
	/** The middle of the Mole's hull, which the camera looks at. */
	private static final double POD_MIDDLE = 0.9;
	/** The parked pod faces north, toward the turntable camera; the plain and the mesa are behind it. */
	private static final float FACING_CAMERA = 180f;
	/** The pilot looks east, along the clip. */
	private static final float CLIP_YAW = -90f;
	private static final float FIRST_PERSON_PITCH = 12f;

	private static final Input DRIVE = new Input(true, false, false, false, false, false, false);
	private static final Input BORE_DOWN = new Input(false, false, false, false, false, false, true);
	private static final Input LIFT = new Input(false, false, false, false, true, false, false);

	private ClientGameTestContext ctx;
	private TestSingleplayerContext world;
	private BlockPos stage;
	private BlockPos clipGround;
	private PodEntity parked;
	private PodEntity piloted;
	private MockPlayer pilot;
	private int framesTaken;
	private final List<Consumer<Minecraft>> clientUndo = new ArrayList<>();
	private final List<Consumer<MinecraftServer>> serverUndo = new ArrayList<>();

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
		try (TestSingleplayerContext singleplayer = context.worldBuilder().setUseConsistentSettings(false)
				.adjustSettings(state -> state.setSeed(SEED)).create()) {
			world = singleplayer;
			try {
				setUp();
				for (int n = 0; n < CONCEPTS.size(); n++) {
					PodConcept concept = CONCEPTS.get(n);
					select(concept);
					rebuildGround();
					turntable(concept);
					clip(concept);
					lit(concept);
					if (framesTaken != (n + 1) * FRAMES_PER_CONCEPT) {
						throw new AssertionError("concept " + concept.id() + " ended at frame " + framesTaken + ", not " + (n + 1) * FRAMES_PER_CONCEPT
								+ ": the GIFs are cut at fixed frames");
					}
				}
			} finally {
				try {
					System.clearProperty(PodConcept.PROPERTY);
					reloadAndWait("the Mole back on its shipping renderer", client -> !(moleRenderer(client) instanceof PodGeoRenderer));
				} finally {
					undo();
				}
			}
		}
	}

	/** Puts back, newest first, everything {@link #setUp} changed: the client's HUD and camera, the clocks, the weather, the game rules and the camera player. */
	private void undo() {
		ctx.runOnClient(client -> clientUndo.reversed().forEach(step -> step.accept(client)));
		serverDo(server -> serverUndo.reversed().forEach(step -> step.accept(server)));
	}

	/**
	 * Pins the world (no mobs, no random ticks, clear weather, the gameplay clock at noon and the sky clock at its brightest dusk), makes the camera a creative flier with the
	 * HUD hidden, levels two pads of regolith south of the colony, and puts a parked Mole on one and a piloted one on the other.
	 * Every change to the client, the rules, the clocks or the camera player is noted for {@link #undo} before it is made.
	 */
	private void setUp() {
		ClientWait.until(ctx, "the colony built", () -> serverGet(server -> Colony.placed(server).filter(ColonySite.Placed::finished).isPresent()),
				() -> "the colony " + serverGet(server -> Colony.placed(server).map(placed -> "begun at " + placed.center()).orElse("not placed")));
		BlockPos colony = serverGet(server -> Colony.placed(server).orElseThrow()).center();
		serverDo(server -> {
			GameRules rules = server.getGameRules();
			for (GameRule<Boolean> rule : List.of(GameRules.SPAWN_MOBS, GameRules.SPAWN_MONSTERS, GameRules.ADVANCE_TIME, GameRules.ADVANCE_WEATHER)) {
				boolean before = rules.get(rule);
				serverUndo.add(undoServer -> undoServer.getGameRules().set(rule, before, undoServer));
				rules.set(rule, false, server);
			}
			int randomTicks = rules.get(GameRules.RANDOM_TICK_SPEED);
			serverUndo.add(undoServer -> undoServer.getGameRules().set(GameRules.RANDOM_TICK_SPEED, randomTicks, undoServer));
			rules.set(GameRules.RANDOM_TICK_SPEED, 0, server);
			long gameTime = server.overworld().getOverworldClockTime();
			serverUndo.add(undoServer -> command(undoServer, "time set " + gameTime));
			String weather = server.overworld().isThundering() ? "thunder" : server.overworld().isRaining() ? "rain" : "clear";
			serverUndo.add(undoServer -> command(undoServer, "weather " + weather));
			command(server, "weather clear");
			Holder<WorldClock> sky = EvidenceWorld.skyClock(server);
			ClockInstance skyBefore = server.clockManager().getInstance(sky);
			long skyTicks = skyBefore.totalTicks();
			boolean skyPaused = skyBefore.isPaused();
			serverUndo.add(undoServer -> {
				undoServer.clockManager().setTotalTicks(sky, skyTicks);
				undoServer.clockManager().setPaused(sky, skyPaused);
			});
			ServerLevel overworld = server.overworld();
			stage = flatGroundSouthOf(overworld, colony);
			clipGround = stage.offset(CLIP_EAST, 0, 0);
			ServerPlayer camera = server.getPlayerList().getPlayers().getFirst();
			GameType mode = camera.gameMode();
			boolean mayfly = camera.getAbilities().mayfly;
			boolean flying = camera.getAbilities().flying;
			boolean noGravity = camera.isNoGravity();
			boolean invulnerable = camera.isPermanentlyInvulnerable();
			serverUndo.add(undoServer -> {
				ServerPlayer player = undoServer.getPlayerList().getPlayers().getFirst();
				player.setGameMode(mode);
				player.getAbilities().mayfly = mayfly;
				player.getAbilities().flying = flying;
				player.onUpdateAbilities();
				player.setNoGravity(noGravity);
				player.setPermanentlyInvulnerable(invulnerable);
			});
			camera.setGameMode(GameType.CREATIVE);
			camera.getAbilities().mayfly = true;
			camera.getAbilities().flying = true;
			camera.onUpdateAbilities();
			camera.setNoGravity(true);
			camera.setPermanentlyInvulnerable(true);
			camera.teleportTo(overworld, stage.getX() + 0.5, stage.getY() + 2, stage.getZ() - 4, Set.of(), 0f, 0f, true);
		});
		setTime(NOON, EvidenceWorld.SKY_BRIGHTEST);
		serverDo(server -> {
			ServerLevel overworld = server.overworld();
			levelPad(overworld, stage);
			levelPad(overworld, clipGround);
			parked = PodRegistry.POD.create(overworld, EntitySpawnReason.COMMAND);
			parked.setPos(Vec3.atBottomCenterOf(stage));
			parked.setYRot(FACING_CAMERA);
			overworld.addFreshEntity(parked);
			pilot = MockPlayers.join(server, "Pilot");
			pilot.teleportTo(overworld, Vec3.atBottomCenterOf(clipGround).add(0, 0, 2), CLIP_YAW, 0f);
			pilot.player().setInvisible(true);
			pilot.player().setPermanentlyInvulnerable(true);
			if (Charters.found(server, pilot.player().getUUID(), "Concept Works").isPresent()) {
				throw new AssertionError("founding the pilot's charter should succeed");
			}
			CharterId charter = Charters.charterOfOrThrow(server, pilot.player().getUUID()).orElseThrow().id();
			piloted = PodRegistry.POD.create(overworld, EntitySpawnReason.COMMAND);
			piloted.setPos(clipStart());
			// A piloted pod that has not moved keeps the heading its renderer first saw, so it faces along the clip from the start.
			piloted.setYRot(CLIP_YAW);
			piloted.setFuel(100f);
			overworld.addFreshEntity(piloted);
			PodComponents.register(piloted, charter);
			PodComponents.install(piloted, ComponentItems.mint(server, ComponentTrack.LIGHTS, 2, charter));
			if (!pilot.player().startRiding(piloted)) {
				throw new AssertionError("the pilot could not board the clip's Mole");
			}
		});
		ctx.runOnClient(client -> {
			CameraType cameraType = client.options.getCameraType();
			boolean hudHidden = client.gui.hud.isHidden();
			clientUndo.add(undoClient -> {
				undoClient.options.setCameraType(cameraType);
				if (undoClient.gui.hud.isHidden() != hudHidden) {
					undoClient.gui.hud.toggle();
				}
			});
			client.options.setCameraType(CameraType.FIRST_PERSON);
			if (!hudHidden) {
				client.gui.hud.toggle();
			}
		});
		ClientWait.until(ctx, "both Moles and the pilot aboard on the client", client -> client.level.getEntity(parked.getId()) instanceof PodEntity
				&& client.level.getEntity(piloted.getId()) instanceof PodEntity pod && pod.getControllingPassenger() != null);
	}

	/**
	 * Sets the gameplay clock, which the game rules hold still, to {@code time}, and pins the sky clock at {@code sky}, and waits
	 * until the client reads both back.
	 */
	private void setTime(int time, long sky) {
		serverDo(server -> command(server, "time set " + time));
		EvidenceWorld.skyPhase(ctx, world, sky);
		ClientWait.until(ctx, "the client to read the time " + time, client -> client.level.getOverworldClockTime() % 24_000 == time,
				client -> "the time " + client.level.getOverworldClockTime());
	}

	private static void command(MinecraftServer server, String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
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
		var mole = client.level == null || parked == null ? null : client.level.getEntity(parked.getId());
		return mole == null ? null : client.getEntityRenderDispatcher().getRenderer(mole);
	}

	// ------------------------------------------------------------------------------------------------ the turntable

	private void turntable(PodConcept concept) {
		Vec3 pod = Vec3.atBottomCenterOf(stage);
		serverDo(server -> parked.setPos(pod));
		look(orbit(pod, TURNTABLE_PITCH), pod);
		settle();
		for (int i = 0; i < TURN_FRAMES; i++) {
			turnParked(FACING_CAMERA + i * 360f / TURN_FRAMES);
			ctx.waitTick();
			frame();
			if (i == 0) {
				shot(concept, "front");
			} else if (i == THREEQUARTER_FRAME) {
				shot(concept, "threequarter");
			} else if (i == TURN_FRAMES / 4) {
				shot(concept, "side");
			} else if (i == TURN_FRAMES / 2) {
				shot(concept, "back");
			}
		}
		turnParked(FACING_CAMERA);
		for (int i = 1; i <= RISE_FRAMES; i++) {
			look(orbit(pod, TURNTABLE_PITCH + (ABOVE_PITCH - TURNTABLE_PITCH) * i / RISE_FRAMES), pod);
			ctx.waitTick();
			frame();
		}
		settle();
		shot(concept, "above");
		turnParked(FACING_CAMERA + 35f);
		look(orbit(pod, 22), pod);
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

	// ------------------------------------------------------------------------------------------------ the clip

	/** Where the clip's Mole starts: a block short of the ledge, its footprint on whole blocks so it bores a clean 2 x 2. */
	private Vec3 clipStart() {
		return new Vec3(clipGround.getX(), clipGround.getY(), clipGround.getZ());
	}

	private void clip(PodConcept concept) {
		Vec3 start = clipStart();
		placePiloted(start, CLIP_YAW);
		Vec3 eye = serverGet(server -> pilot.player().getEyePosition());
		lookFrom(eye, CLIP_YAW, FIRST_PERSON_PITCH);
		settle();
		shot(concept, "first-person");
		// Drive east at the ledge and chew into it, seen from beyond the ledge and to the pod's left, riding along with it: the
		// cutter's top half stands over the rock and turns toward the camera.
		Vec3 chewOffset = new Vec3(3.0, 1.5, -2.1);
		Vec3 cutter = new Vec3(1.0, 0.2, 0);
		look(start.add(chewOffset), start.add(cutter));
		settle();
		serverDo(server -> pilot.setInput(DRIVE));
		for (int i = 0; i < APPROACH_FRAMES; i++) {
			ctx.waitTicks(TICKS_PER_APPROACH_FRAME); // tick-wait: the clip is cut at fixed frames
			frame();
		}
		for (int i = 0; i < CHEW_FRAMES; i++) {
			Vec3 pod = serverGet(server -> piloted.position());
			look(start.add(chewOffset).add(pod.x - start.x, 0, 0), pod.add(cutter));
			ctx.waitTick();
			frame();
			if (i == CHEW_FRAMES / 3) {
				shot(concept, "chewing-the-ledge");
			}
		}
		// Back to the open pad, then bore the floor: the cutter swings under the belly and the pod sinks into its lamp-lit shaft.
		releasePilot();
		Vec3 floor = start.add(0, 0, -5);
		placePiloted(floor, CLIP_YAW);
		look(floor.add(2.0, 2.4, -1.8), floor.add(0, 0.1, 0));
		settle();
		serverDo(server -> pilot.setInput(BORE_DOWN));
		for (int i = 0; i < FLOOR_FRAMES; i++) {
			ctx.waitTick();
			frame();
			if (i == FLOOR_FRAMES * 2 / 3) {
				shot(concept, "boring-the-floor");
			}
		}
		// And fly up out of the hole.
		serverDo(server -> pilot.setInput(LIFT));
		for (int i = 0; i < FLY_FRAMES; i++) {
			Vec3 pod = serverGet(server -> piloted.position());
			Vec3 at = pod.add(2.8, 0.9, -2.8);
			look(new Vec3(at.x, Math.max(at.y, floor.y + 1.2), at.z), pod);
			ctx.waitTicks(TICKS_PER_FLY_FRAME); // tick-wait: the clip is cut at fixed frames
			frame();
			if (i == FLY_FRAMES / 2) {
				shot(concept, "flying");
			}
		}
		releasePilot();
		placePiloted(start, CLIP_YAW);
	}

	/** The clip's pod at night with its lamps on, seen from in front and to its left; then back to the brightest dusk. */
	private void lit(PodConcept concept) {
		Vec3 at = new Vec3(clipGround.getX(), clipGround.getY(), clipGround.getZ() + 4);
		placePiloted(at, CLIP_YAW);
		setTime(NIGHT, EvidenceWorld.SKY_DARKEST);
		look(at.add(3.0, 1.3, -1.6), at);
		settle();
		shot(concept, "lit");
		setTime(NOON, EvidenceWorld.SKY_BRIGHTEST);
		placePiloted(clipStart(), CLIP_YAW);
	}

	/** Sets the clip's Mole down at {@code at}, full of fuel and whole, with the pilot aboard looking {@code pilotYaw}. */
	private void placePiloted(Vec3 at, float pilotYaw) {
		serverDo(server -> {
			piloted.setDeltaMovement(Vec3.ZERO);
			piloted.setPos(at);
			piloted.setYRot(pilotYaw);
			piloted.setFuel(100f);
			piloted.setHull(piloted.maxHull());
			piloted.setStranded(false);
			if (pilot.player().getVehicle() != piloted && !pilot.player().startRiding(piloted)) {
				throw new AssertionError("the pilot could not board the clip's Mole again");
			}
			pilot.player().setYRot(pilotYaw);
		});
		ClientWait.until(ctx, "the pilot aboard the clip's Mole on the client",
				client -> client.level.getEntity(piloted.getId()) instanceof PodEntity pod && pod.getControllingPassenger() != null);
		// An invisible player still draws what it holds, and the handbook it must carry would float over the pod. Empty the hands of the
		// client's copy only: the server's pilot keeps its handbook.
		ctx.runOnClient(client -> {
			if (client.level.getEntity(pilot.player().getId()) instanceof Player shown) {
				shown.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
				shown.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
			}
		});
	}

	private void releasePilot() {
		serverDo(server -> pilot.releaseInput());
	}

	// ------------------------------------------------------------------------------------------------ the ground

	/** The pads whole again, the last concept's shaft filled, and the ledge put back: the last concept chewed it. */
	private void rebuildGround() {
		serverDo(server -> {
			ServerLevel overworld = server.overworld();
			levelPad(overworld, clipGround);
			buildLedge(overworld);
			clearMobs(overworld);
		});
		settle();
	}

	/**
	 * The stage south of {@code colony} whose pads and the margin round them rise and fall least, the nearest of equals, at the
	 * stage's own ground height: a pad levelled there is neither a pit in a crater nor a shelf on a mesa. Throws when even the
	 * flattest is rougher than {@value #STAGE_ROUGHNESS} blocks.
	 */
	private static BlockPos flatGroundSouthOf(ServerLevel level, BlockPos colony) {
		BlockPos best = null;
		int bestRoughness = Integer.MAX_VALUE;
		for (int south = STAGE_SOUTH; south <= STAGE_SEARCH; south += STAGE_STEP) {
			BlockPos candidate = colony.offset(0, 0, south);
			int low = Integer.MAX_VALUE;
			int high = Integer.MIN_VALUE;
			for (int dx = -PAD_HALF - STAGE_MARGIN; dx <= CLIP_EAST + PAD_HALF + STAGE_MARGIN; dx++) {
				for (int dz = -PAD_HALF - STAGE_MARGIN; dz <= PAD_HALF + STAGE_MARGIN; dz++) {
					int y = groundY(level, candidate.getX() + dx, candidate.getZ() + dz);
					low = Math.min(low, y);
					high = Math.max(high, y);
				}
			}
			if (high - low < bestRoughness) {
				bestRoughness = high - low;
				best = candidate.atY(groundY(level, candidate.getX(), candidate.getZ()));
			}
		}
		if (bestRoughness > STAGE_ROUGHNESS) {
			throw new AssertionError("the flattest stage south of the colony at " + colony + " rises and falls " + bestRoughness + " blocks, at " + best);
		}
		return best;
	}

	/** The first air over the ground at x, z, found by looking down the column from the sky, in a chunk loaded for it. */
	private static int groundY(ServerLevel level, int x, int z) {
		LevelChunk chunk = level.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, level.getMaxY(), z);
		while (pos.getY() > level.getMinY() && chunk.getBlockState(pos).isAir()) {
			pos.move(0, -1, 0);
		}
		return pos.getY() + 1;
	}

	/** A flat square round {@code centre}: a layer of regolith over three of packed regolith, with clear air above it. */
	private static void levelPad(ServerLevel level, BlockPos centre) {
		BlockState top = SurfaceBlocks.REGOLITH.defaultBlockState();
		BlockState packed = SurfaceBlocks.REGOLITH_PACKED.defaultBlockState();
		for (int dx = -PAD_HALF; dx <= PAD_HALF; dx++) {
			for (int dz = -PAD_HALF; dz <= PAD_HALF; dz++) {
				for (int dy = -4; dy < PAD_CLEARANCE; dy++) {
					BlockState state = dy < -1 ? packed : dy == -1 ? top : Blocks.AIR.defaultBlockState();
					level.setBlock(centre.offset(dx, dy, dz), state, Block.UPDATE_ALL);
				}
			}
		}
	}

	/**
	 * A ledge of rust-red rock one block high and three deep across the clip's path, east of its start, for the cutter to chew: the
	 * cutter's top half stands over it, so its turn shows the whole time it bites.
	 */
	private void buildLedge(ServerLevel level) {
		BlockState rock = SurfaceBlocks.REGOLITH_ROCK.defaultBlockState();
		for (int dx = 2; dx <= 4; dx++) {
			for (int dz = -3; dz <= 2; dz++) {
				level.setBlock(clipGround.offset(dx, 0, dz), rock, Block.UPDATE_ALL);
			}
		}
	}

	/** Mobs the world made before the rules stopped them, which would walk through the stills. */
	private void clearMobs(ServerLevel level) {
		AABB around = new AABB(stage).inflate(48);
		level.getEntitiesOfClass(Mob.class, around).forEach(Entity::discard);
	}

	// ------------------------------------------------------------------------------------------------ camera

	/** A camera point {@value #TURNTABLE_DISTANCE} blocks from the pod's middle, north of it, {@code pitch} degrees up. */
	private static Vec3 orbit(Vec3 pod, double pitch) {
		double p = Math.toRadians(pitch);
		return pod.add(0, POD_MIDDLE + TURNTABLE_DISTANCE * Math.sin(p), -TURNTABLE_DISTANCE * Math.cos(p));
	}

	/** Puts the camera's eye at {@code eye}, looking at the middle of a pod standing at {@code pod}. */
	private void look(Vec3 eye, Vec3 pod) {
		Vec3 target = pod.add(0, POD_MIDDLE, 0);
		Vec3 d = target.subtract(eye);
		lookFrom(eye, (float) Math.toDegrees(Math.atan2(-d.x, d.z)), (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z))));
	}

	private void lookFrom(Vec3 eye, float yaw, float pitch) {
		serverDo(server -> server.getPlayerList().getPlayers().getFirst().teleportTo(server.overworld(), eye.x, eye.y - EYE, eye.z, Set.of(), yaw, pitch, true));
	}

	/**
	 * Waits until the light has settled and every section in view has rendered, and both have held for {@value #SETTLE_POLLS} polls in a
	 * row: block changes reach the client a little after the server makes them, so one good poll can come before the sections go dirty.
	 */
	private void settle() {
		int[] stable = {0};
		ClientWait.until(ctx, "the view settled", () -> {
			boolean ready = !serverGet(server -> server.overworld().getLightEngine().hasLightWork())
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
}
