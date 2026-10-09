package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

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
import net.minecraft.world.entity.EntityType;
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
import io.github.pkeppeler.deepcharter.client.pod.PodGeoRenderer;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.pod.Chassis;
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
 * Evidence scenario "pods" (#243): the real Mole and Prospector, drawn with GeckoLib, each cutter in turn, and the wrecks.
 *
 * <p>The scene is the regolith plain south of the colony, in the design tour's world, under the brightest dusk of the sky. Per
 * subject (the Mole with the tricone, the stacked rings and the fluted auger, the Prospector with those and the cluster, each
 * on the drill tier that shows it): a turntable of the parked pod (front, three-quarter, side, back, then the camera
 * rising to look down) and the stills {@code <id>-front}, {@code -threequarter}, {@code -side}, {@code -back}, {@code -above},
 * {@code -unlit} (parked, no pilot) and {@code -lit} (a pilot aboard, at night, lamps on). The derelict Mole and the scorched
 * Prospector get the same turntable, by day and by night, with no lit stills (a wreck has no pilot). Last, the Mole and the Prospector each
 * drive into a ledge of rock and chew it, bore the floor and fly up out of the hole, with the stills
 * {@code <id>-chewing-the-ledge}, {@code -boring-the-floor} and {@code -flying}.
 *
 * <p>Every segment's frames are logged as {@code [pods-segment] <name> <first>-<last>}, which is how the GIFs are cut.
 */
public class PodsScenario extends EvidenceScenario {
	/** One thing to turn on the table: a pod of a chassis with a drill of a tier, whole or wrecked. */
	private record Subject(String id, Chassis chassis, int drillTier, boolean wreck, String cutter) {
	}

	private static final List<Subject> SUBJECTS = List.of(
			new Subject("mole-tricone", Chassis.MOLE, 0, false, "tricone"),
			new Subject("mole-stacked", Chassis.MOLE, 1, false, "stacked"),
			new Subject("mole-fluted", Chassis.MOLE, 2, false, "fluted"),
			new Subject("prospector-tricone", Chassis.PROSPECTOR, 0, false, "tricone"),
			new Subject("prospector-stacked", Chassis.PROSPECTOR, 1, false, "stacked"),
			new Subject("prospector-fluted", Chassis.PROSPECTOR, 2, false, "fluted"),
			new Subject("prospector-cluster", Chassis.PROSPECTOR, 3, false, "cluster"));
	private static final List<Subject> WRECKS = List.of(
			new Subject("mole-wreck", Chassis.MOLE, 2, true, "fluted"),
			new Subject("prospector-wreck", Chassis.PROSPECTOR, 3, true, "cluster"));

	/** The design tour's world, so the plain and the mesa behind it are the ones the tour shows. */
	private static final String SEED = "deepcharter-design-tour";
	/** The stage is the flattest ground from this far to {@value #STAGE_SEARCH} blocks south of the colony's centre, in steps of {@value #STAGE_STEP}. */
	private static final int STAGE_SOUTH = 56;
	private static final int STAGE_STEP = 6;
	private static final int STAGE_SEARCH = 240;
	/** The ground judged is both pads and this margin round them. */
	private static final int STAGE_MARGIN = 4;
	/** The most the ground may rise or fall over that area, in blocks: more, and a levelled pad is a pit or a shelf. */
	private static final int STAGE_ROUGHNESS = 4;
	/** The clip's ground is this far east of the turntable. */
	private static final int CLIP_EAST = 22;
	/** Half the side of each levelled pad of regolith. */
	private static final int PAD_HALF = 9;
	private static final int PAD_CLEARANCE = 12;
	private static final double EYE = 1.62;
	private static final int SETTLE_POLLS = 5;

	/** The gameplay clock, which sets the light, at noon and at night: the sky clock is pinned with it ({@link EvidenceWorld#skyPhase}). */
	private static final int NOON = 6_000;
	private static final int NIGHT = 18_000;

	private static final int TURN_FRAMES = 36;
	private static final int RISE_FRAMES = 9;
	private static final int APPROACH_FRAMES = 8;
	private static final int TICKS_PER_APPROACH_FRAME = 2;
	/** The ledge is chewed one tick a frame: at the cones' turn (16 degrees a tick), under half the 45 degrees between their points, so the turn reads forward. */
	private static final int CHEW_FRAMES = 58;
	private static final int FLOOR_FRAMES = 30;
	private static final int FLY_FRAMES = 16;
	/** The floor-boring still comes this many frames in: the mount has swung most of the way, and the cone points almost straight down. */
	private static final int BORE_STILL_FRAME = 3;
	private static final int TICKS_PER_FLY_FRAME = 3;

	private static final double TURNTABLE_PITCH = 16;
	private static final double ABOVE_PITCH = 86;
	/** The turntable frame of the three-quarter still: the pod turned 40 degrees from facing the camera, showing its front and its left side. */
	private static final int THREEQUARTER_FRAME = 4;
	/** The parked pod faces north, toward the turntable camera; the plain and the mesa are behind it. */
	private static final float FACING_CAMERA = 180f;
	/** The pilot looks east, along the clip. */
	private static final float CLIP_YAW = -90f;
	private static final float FIRST_PERSON_PITCH = 20f;

	private static final Input DRIVE = new Input(true, false, false, false, false, false, false);
	private static final Input BORE_DOWN = new Input(false, false, false, false, false, false, true);
	private static final Input LIFT = new Input(false, false, false, false, true, false, false);

	private ClientGameTestContext ctx;
	private TestSingleplayerContext world;
	private BlockPos stage;
	private BlockPos clipGround;
	private CharterId charter;
	private PodEntity parked;
	private PodEntity driven;
	private MockPlayer pilot;
	private int framesTaken;
	private final List<Consumer<Minecraft>> clientUndo = new ArrayList<>();
	private final List<Consumer<MinecraftServer>> serverUndo = new ArrayList<>();

	@Override
	protected String name() {
		return "pods";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		ctx = context;
		try (TestSingleplayerContext singleplayer = context.worldBuilder().setUseConsistentSettings(false)
				.adjustSettings(state -> state.setSeed(SEED)).create()) {
			world = singleplayer;
			try {
				setUp();
				for (Subject subject : SUBJECTS) {
					showcase(subject);
				}
				for (Subject wreck : WRECKS) {
					showcase(wreck);
				}
				for (Subject subject : List.of(SUBJECTS.getFirst(), SUBJECTS.get(6))) {
					rebuildGround();
					clip(subject);
				}
			} finally {
				undo();
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
	 * HUD hidden, levels two pads of regolith south of the colony and founds a charter for the pilot.
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
			pilot = MockPlayers.join(server, "Pilot");
			pilot.teleportTo(overworld, Vec3.atBottomCenterOf(clipGround).add(0, 0, 2), CLIP_YAW, 0f);
			pilot.player().setInvisible(true);
			pilot.player().setPermanentlyInvulnerable(true);
			if (Charters.found(server, pilot.player().getUUID(), "Pod Works").isPresent()) {
				throw new AssertionError("founding the pilot's charter should succeed");
			}
			charter = Charters.charterOfOrThrow(server, pilot.player().getUUID()).orElseThrow().id();
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

	// ------------------------------------------------------------------------------------------------ pods

	/**
	 * A pod of {@code chassis} for {@code subject}, put down at {@code at} facing {@code yaw}, owned by the pilot's charter and with a
	 * drill of the subject's tier fitted (none for tier 0, the stock drill), whole and with fuel, or wrecked. It waits until the client draws
	 * the subject's cutter.
	 */
	private PodEntity spawn(Subject subject, Vec3 at, float yaw) {
		PodEntity[] made = {null};
		serverDo(server -> {
			ServerLevel overworld = server.overworld();
			EntityType<PodEntity> type = PodRegistry.typeOf(subject.chassis());
			PodEntity pod = type.create(overworld, EntitySpawnReason.COMMAND);
			pod.setPos(at);
			pod.setYRot(yaw);
			overworld.addFreshEntity(pod);
			PodComponents.register(pod, charter);
			if (subject.drillTier() > 0) {
				PodComponents.install(pod, ComponentItems.mint(server, ComponentTrack.DRILL, subject.drillTier(), charter));
			}
			pod.setFuel(100f);
			made[0] = pod;
		});
		PodEntity pod = made[0];
		ClientWait.until(ctx, subject.id() + " drawn with the " + subject.cutter(), client -> client.level.getEntity(pod.getId()) instanceof PodEntity shown
				&& renderer(client, shown).appearanceOf(shown).cutter().equals(subject.cutter()),
				client -> client.level.getEntity(pod.getId()) instanceof PodEntity shown ? String.valueOf(renderer(client, shown).appearanceOf(shown)) : "no pod");
		return pod;
	}

	private static PodGeoRenderer renderer(Minecraft client, PodEntity pod) {
		Object drawn = client.getEntityRenderDispatcher().getRenderer(pod);
		if (!(drawn instanceof PodGeoRenderer geo)) {
			throw new AssertionError("the pod is not drawn with the GeckoLib renderer but with " + drawn);
		}
		return geo;
	}

	private void remove(PodEntity pod) {
		serverDo(server -> {
			pod.getPassengers().forEach(Entity::stopRiding);
			pod.discard();
		});
		ClientWait.until(ctx, "the pod gone from the client", client -> client.level.getEntity(pod.getId()) == null);
	}

	private void wreckIt(PodEntity pod) {
		serverDo(server -> pod.setHull(0f));
		ClientWait.until(ctx, "the pod a wreck on the client", client -> client.level.getEntity(pod.getId()) instanceof PodEntity shown && renderer(client, shown).appearanceOf(shown).wrecked());
	}

	/** The pilot boards {@code pod}, which then has power: lamps on. */
	private void board(PodEntity pod) {
		serverDo(server -> {
			if (!pilot.player().startRiding(pod)) {
				throw new AssertionError("the pilot could not board " + pod);
			}
		});
		ClientWait.until(ctx, "the pilot aboard on the client", client -> client.level.getEntity(pod.getId()) instanceof PodEntity shown && shown.getControllingPassenger() != null);
		// An invisible player still draws what it holds, and the handbook it must carry would float over the pod. Empty the hands of the
		// client's copy only: the server's pilot keeps its handbook.
		ctx.runOnClient(client -> {
			if (client.level.getEntity(pilot.player().getId()) instanceof Player shown) {
				shown.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
				shown.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
			}
		});
	}

	private void disembark(PodEntity pod) {
		serverDo(server -> pilot.player().stopRiding());
		ClientWait.until(ctx, "the pilot off the pod on the client", client -> client.level.getEntity(pod.getId()) instanceof PodEntity shown && shown.getControllingPassenger() == null);
	}

	// ------------------------------------------------------------------------------------------------ the showcase

	/** The turntable of {@code subject} and its stills, then its lit still if it can be lit. */
	private void showcase(Subject subject) {
		Vec3 at = Vec3.atBottomCenterOf(stage);
		parked = spawn(subject, at, FACING_CAMERA);
		if (subject.wreck()) {
			wreckIt(parked);
		}
		int first = framesTaken + 1;
		turntable(subject);
		segment(subject.id() + "-turntable", first);
		if (subject.wreck()) {
			setTime(NIGHT, EvidenceWorld.SKY_DARKEST);
			turnParked(FACING_CAMERA + 35f);
			look(orbit(at, 22, subject.chassis()), at, subject.chassis());
			settle();
			shot(subject, "night");
			setTime(NOON, EvidenceWorld.SKY_BRIGHTEST);
		} else {
			board(parked);
			setTime(NIGHT, EvidenceWorld.SKY_DARKEST);
			turnParked(FACING_CAMERA + 35f);
			look(orbit(at, 22, subject.chassis()), at, subject.chassis());
			settle();
			shot(subject, "lit");
			setTime(NOON, EvidenceWorld.SKY_BRIGHTEST);
			disembark(parked);
		}
		remove(parked);
	}

	private void turntable(Subject subject) {
		Vec3 pod = Vec3.atBottomCenterOf(stage);
		Chassis chassis = subject.chassis();
		look(orbit(pod, TURNTABLE_PITCH, chassis), pod, chassis);
		settle();
		for (int i = 0; i < TURN_FRAMES; i++) {
			turnParked(FACING_CAMERA + i * 360f / TURN_FRAMES);
			ctx.waitTick();
			frame();
			if (i == 0) {
				shot(subject, "front");
			} else if (i == THREEQUARTER_FRAME) {
				shot(subject, "threequarter");
			} else if (i == TURN_FRAMES / 4) {
				shot(subject, "side");
			} else if (i == TURN_FRAMES / 2) {
				shot(subject, "back");
			}
		}
		turnParked(FACING_CAMERA);
		for (int i = 1; i <= RISE_FRAMES; i++) {
			look(orbit(pod, TURNTABLE_PITCH + (ABOVE_PITCH - TURNTABLE_PITCH) * i / RISE_FRAMES, chassis), pod, chassis);
			ctx.waitTick();
			frame();
		}
		settle();
		shot(subject, "above");
		turnParked(FACING_CAMERA + 35f);
		look(orbit(pod, 22, chassis), pod, chassis);
		settle();
		shot(subject, "unlit");
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

	/** Where the clip's pod starts: a block short of the ledge, its footprint on whole blocks so it bores a clean bore. */
	private Vec3 clipStart() {
		return new Vec3(clipGround.getX(), clipGround.getY(), clipGround.getZ());
	}

	private void clip(Subject subject) {
		double middle = middleOf(subject.chassis());
		Vec3 start = clipStart();
		driven = spawn(subject, start, CLIP_YAW);
		board(driven);
		int first = framesTaken + 1;
		placeDriven(start);
		Vec3 eye = serverGet(server -> pilot.player().getEyePosition());
		lookFrom(eye, CLIP_YAW, FIRST_PERSON_PITCH);
		settle();
		shot(subject, "first-person");
		// Drive east at the ledge and chew into it, seen from above and to the pod's left, riding along with it: the cone's tip
		// leads the pod by two blocks and goes into the rock first, and its turn shows against the cut.
		double scale = subject.chassis() == Chassis.PROSPECTOR ? 1.5 : 1.0;
		Vec3 chewOffset = new Vec3(0.6 * scale, 3.6 * scale, -3.4 * scale);
		Vec3 cutter = new Vec3(1.6 * scale, 0.1, 0);
		look(start.add(chewOffset), start.add(cutter), middle);
		settle();
		serverDo(server -> pilot.setInput(DRIVE));
		for (int i = 0; i < APPROACH_FRAMES; i++) {
			ctx.waitTicks(TICKS_PER_APPROACH_FRAME); // tick-wait: the clip is cut at fixed frames
			frame();
		}
		for (int i = 0; i < CHEW_FRAMES; i++) {
			Vec3 pod = serverGet(server -> driven.position());
			look(start.add(chewOffset).add(pod.x - start.x, 0, 0), pod.add(cutter), middle);
			ctx.waitTick();
			frame();
			if (i == CHEW_FRAMES / 3) {
				shot(subject, "chewing-the-ledge");
			}
		}
		// Back to the open pad, then bore the floor: the cutter swings under the belly and the pod sinks into its lamp-lit shaft.
		releasePilot();
		Vec3 floor = start.add(0, 0, -6);
		placeDriven(floor);
		look(floor.add(2.9 * scale, 1.5 * scale, -2.6 * scale), floor.add(0.2, 0.2, 0), middle);
		settle();
		serverDo(server -> pilot.setInput(BORE_DOWN));
		for (int i = 0; i < FLOOR_FRAMES; i++) {
			ctx.waitTick();
			frame();
			if (i == BORE_STILL_FRAME) {
				shot(subject, "boring-the-floor");
			}
		}
		// And fly up out of the hole.
		serverDo(server -> pilot.setInput(LIFT));
		for (int i = 0; i < FLY_FRAMES; i++) {
			Vec3 pod = serverGet(server -> driven.position());
			Vec3 at = pod.add(3.6 * scale, 1.0, -3.4 * scale);
			look(new Vec3(at.x, Math.max(at.y, floor.y + 1.2), at.z), pod, middle);
			ctx.waitTicks(TICKS_PER_FLY_FRAME); // tick-wait: the clip is cut at fixed frames
			frame();
			if (i == FLY_FRAMES / 2) {
				shot(subject, "flying");
			}
		}
		releasePilot();
		segment(subject.id() + "-clip", first);
		disembark(driven);
		remove(driven);
	}

	/** Sets the clip's pod down at {@code at}, full of fuel and whole, with the pilot aboard looking along the clip. */
	private void placeDriven(Vec3 at) {
		serverDo(server -> {
			driven.setDeltaMovement(Vec3.ZERO);
			driven.setPos(at);
			driven.setYRot(CLIP_YAW);
			driven.setFuel(100f);
			driven.setHull(driven.maxHull());
			driven.setStranded(false);
			pilot.player().setYRot(CLIP_YAW);
		});
		ctx.waitTick();
	}

	private void releasePilot() {
		serverDo(server -> pilot.releaseInput());
	}

	// ------------------------------------------------------------------------------------------------ the ground

	/** The pads whole again, the last clip's shaft filled, and the ledge put back: the last clip chewed it. */
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
	 * A ledge of rust-red rock one block high and four deep across the clip's path, east of its start, for the cutter to chew: the
	 * cutter's top half stands over it, so its turn shows the whole time it bites. Wide enough for a Prospector's bore.
	 */
	private void buildLedge(ServerLevel level) {
		BlockState rock = SurfaceBlocks.REGOLITH_ROCK.defaultBlockState();
		for (int dx = 3; dx <= 6; dx++) {
			for (int dz = -4; dz <= 3; dz++) {
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

	/** The middle of the hull, which the camera looks at. */
	private static double middleOf(Chassis chassis) {
		return chassis.height() / 2;
	}

	/** A camera point north of the pod, {@code pitch} degrees up, far enough to keep a cone's tip in view when it points at the camera. */
	private static Vec3 orbit(Vec3 pod, double pitch, Chassis chassis) {
		double p = Math.toRadians(pitch);
		double distance = chassis == Chassis.PROSPECTOR ? 5.4 : 3.6;
		return pod.add(0, middleOf(chassis) + distance * Math.sin(p), -distance * Math.cos(p));
	}

	private void look(Vec3 eye, Vec3 pod, Chassis chassis) {
		look(eye, pod, middleOf(chassis));
	}

	/** Puts the camera's eye at {@code eye}, looking at the middle of a pod standing at {@code pod}, {@code middle} blocks up. */
	private void look(Vec3 eye, Vec3 pod, double middle) {
		Vec3 target = pod.add(0, middle, 0);
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

	private void shot(Subject subject, String view) {
		screenshot(ctx, subject.id() + "-" + view);
	}

	/** Logs the frames from {@code first} to the last one taken, which is how the GIF of this segment is cut. */
	private void segment(String segment, int first) {
		System.out.println("[pods-segment] " + segment + " " + first + "-" + framesTaken);
	}

	private void serverDo(Consumer<MinecraftServer> action) {
		world.getServer().runOnServer(action::accept);
	}

	private <T> T serverGet(Function<MinecraftServer, T> query) {
		return world.getServer().computeOnServer(query::apply);
	}
}
