package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.charter.terminal.ContractScreen;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.LookSkin;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Evidence scenario "look-motion" for the look books (#326, docs/tooling/look-book.md): the four things that move, each a short clip
 * shot as stills {@code clip-<clip>-NNN} (the look-book tool makes one GIF of each) and as frames of the run's MP4. In the design
 * tour's world (its seed), with the run's look-book skin when it has one:
 * <ol>
 * <li>{@code sky-cycle}: the colony from the south-east while the sky goes from dusk to night and back,</li>
 * <li>{@code drilling}: a lit Mole drilling down through a column of every ore in layer 1, lit only by its own lamps,</li>
 * <li>{@code lava}: a lava fall into a pool in a sealed hall of layer 2,</li>
 * <li>{@code terminal}: the Contract Terminal's screen typing out.</li>
 * </ol>
 * It also shoots three stills the tour has no view for: the colony at night ({@code look-colony-at-night}) and a cavern of each
 * layer by lamp light ({@code look-layer-<n>-cavern-by-lamp}).
 */
public class LookMotionScenario extends EvidenceScenario {
	private static final double EYE = 1.62;
	/** Gameplay ticks the sky clip steps through: dusk, the fall to night, night, and the rise back (the skin timelines' keyframes). */
	private static final long[] SKY_TICKS = skyTicks();
	private static final int SKY_TICKS_PER_FRAME = 3;
	private static final int DRILL_FRAMES = 36;
	private static final int DRILL_TICKS_PER_FRAME = 6;
	private static final int LAVA_FRAMES = 24;
	private static final int LAVA_TICKS_PER_FRAME = 4;
	private static final int TERMINAL_FRAMES = 30;
	private static final int TERMINAL_TICKS_PER_FRAME = 3;
	private static final int LAYER_X = 2600;
	private static final int LAYER_Z = 2600;
	private static final int ROOM = 6;
	/** The light level of the lamp at the camera in the cavern stills: a pod's lights part of tier 2. */
	private static final int LAMP_LEVEL = 14;
	/** The cavern stills look at rock about this far from the camera, inside the lamp's reach, and search no farther than the reach. */
	private static final double WALL_DISTANCE = 7;
	private static final double WALL_REACH = 16;
	/** The drill column: this many blocks each side of the pod's column, so the shaft's walls are ore too, and each ore this deep. */
	private static final int COLUMN_RADIUS = 3;
	private static final int ORE_DEPTH = 2;
	private static final float DRILL_PITCH = 55f;

	private ClientGameTestContext ctx;
	private TestSingleplayerContext sp;
	private CharterId charter;

	@Override
	protected String name() {
		return "look-motion";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		ctx = context;
		LookSkin.enable(context);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().setUseConsistentSettings(false)
				.adjustSettings(state -> state.setSeed("deepcharter-design-tour")).create()) {
			sp = singleplayer;
			ClientWait.until(context, "the player in the world", client -> client.player != null && client.level != null);
			setUp();
			skyCycle();
			colonyAtNight();
			drilling();
			cavernByLamp(1);
			lava();
			cavernByLamp(2);
			terminal();
		}
	}

	private void setUp() {
		serverDo(server -> {
			GameRules rules = server.getGameRules();
			rules.set(GameRules.ADVANCE_TIME, false, server);
			rules.set(GameRules.ADVANCE_WEATHER, false, server);
			rules.set(GameRules.SPAWN_MOBS, false, server);
			rules.set(GameRules.SPAWN_MONSTERS, false, server);
			rules.set(GameRules.RANDOM_TICK_SPEED, 0, server);
			command(server, "weather clear");
			command(server, "time set noon");
			ServerPlayer player = player(server);
			player.setGameMode(GameType.CREATIVE);
			player.getInventory().clearContent();
			player.getAbilities().mayfly = true;
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
			player.setPermanentlyInvulnerable(true);
			if (Charters.found(server, player.getUUID(), "Look Book Co.").isPresent()) {
				throw new AssertionError("founding the charter should succeed");
			}
			charter = Charters.charterOfOrThrow(server, player.getUUID()).orElseThrow().id();
		});
		ctx.runOnClient(client -> {
			client.options.particles().set(ParticleStatus.MINIMAL);
			client.options.bobView().set(false);
			client.options.setCameraType(CameraType.FIRST_PERSON);
			if (!client.gui.hud.isHidden()) {
				client.gui.hud.toggle();
			}
		});
	}

	// ------------------------------------------------------------------------------------------------ clips

	private void skyCycle() {
		BlockPos statue = serverGet(server -> Colony.anchor(server, ColonyAnchor.STATUE).orElseThrow(() -> new AssertionError("no colony")));
		Vec3 foot = Vec3.atBottomCenterOf(statue);
		look(0, foot.add(34, 9, 34), foot.add(-30, 22, -30));
		awaitRendered("sky-cycle");
		for (int i = 0; i < SKY_TICKS.length; i++) {
			long ticks = SKY_TICKS[i];
			serverDo(server -> command(server, "time set " + ticks));
			ctx.waitTicks(SKY_TICKS_PER_FRAME);
			shot("sky-cycle", i);
		}
		serverDo(server -> command(server, "time set noon"));
	}

	/** The still {@code look-colony-at-night}: the square at gameplay night, from its south edge, by the colony's own light. */
	private void colonyAtNight() {
		Vec3 foot = Vec3.atBottomCenterOf(serverGet(server -> Colony.anchor(server, ColonyAnchor.STATUE).orElseThrow()));
		serverDo(server -> command(server, "time set 18000"));
		look(0, foot.add(0, 5, 24), foot.add(0, 3, 0));
		awaitRendered("look-colony-at-night");
		screenshot(ctx, "look-colony-at-night");
		serverDo(server -> command(server, "time set noon"));
	}

	/**
	 * The still {@code look-layer-<n>-cavern-by-lamp}: the design tour's cave cell of the layer as played, with no night vision and one
	 * lamp-strength light at the camera, as a pod's lights part places, looking a little down at the rock nearest {@link #WALL_DISTANCE}.
	 */
	private void cavernByLamp(int layer) {
		String still = "look-layer-" + layer + "-cavern-by-lamp";
		Vec3 eye = serverGet(server -> DesignTourScenario.openCell(server.getLevel(LayerChain.dimension(layer))));
		BlockPos lamp = BlockPos.containing(eye);
		serverDo(server -> server.getLevel(LayerChain.dimension(layer)).setBlock(lamp,
				Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, LAMP_LEVEL), Block.UPDATE_ALL));
		Vec3 wall = serverGet(server -> wallInView(server.getLevel(LayerChain.dimension(layer)), eye, player(server)));
		look(layer, eye, wall);
		awaitRendered(still);
		screenshot(ctx, still);
		// room-carver: puts back the air of the tour's open cave cell, where the lamp stood
		serverDo(server -> server.getLevel(LayerChain.dimension(layer)).setBlock(lamp, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL));
	}

	private void drilling() {
		BlockPos floor = new BlockPos(LAYER_X, 120, LAYER_Z);
		serverDo(server -> {
			ServerLevel one = server.getLevel(LayerChain.dimension(1));
			loadChunks(one, floor);
			RoomCarver.carve(one, floor.offset(-ROOM, 0, -ROOM), floor.offset(ROOM, 6, ROOM), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			// The column the pod drills: every ore of layer 1 and 2, one band above the other, wide enough to line the shaft.
			List<OreType> ores = List.of(OreType.values());
			for (int depth = 0; depth < ores.size() * ORE_DEPTH; depth++) {
				Block ore = OreRegistry.block(ores.get(depth / ORE_DEPTH));
				for (int dx = -COLUMN_RADIUS; dx <= COLUMN_RADIUS; dx++) {
					for (int dz = -COLUMN_RADIUS; dz <= COLUMN_RADIUS; dz++) {
						one.setBlock(floor.offset(dx, -1 - depth, dz), ore.defaultBlockState(), 3);
					}
				}
			}
		});
		Vec3 stand = Vec3.atBottomCenterOf(floor);
		look(1, stand.add(0, EYE, 0), stand.add(0, EYE, 5));
		PodEntity pod = serverGet(server -> {
			ServerLevel one = server.getLevel(LayerChain.dimension(1));
			PodEntity mole = PodRegistry.POD.create(one, EntitySpawnReason.COMMAND);
			mole.setPos(stand);
			one.addFreshEntity(mole);
			PodComponents.register(mole, charter);
			PodComponents.install(mole, ComponentItems.mint(server, ComponentTrack.LIGHTS, 2, charter));
			mole.setFuel(100f);
			ServerPlayer player = player(server);
			player.getAbilities().flying = false;
			player.onUpdateAbilities();
			if (!player.startRiding(mole)) {
				throw new AssertionError("the player could not mount the pod");
			}
			return mole;
		});
		ClientWait.until(ctx, "the client riding the pod", client -> client.player.getVehicle() instanceof PodEntity);
		ctx.runOnClient(client -> {
			client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			client.player.setXRot(DRILL_PITCH);
		});
		awaitRendered("drilling");
		ctx.getInput().holdKey(options -> options.keySprint);
		try {
			for (int i = 0; i < DRILL_FRAMES; i++) {
				ctx.waitTicks(DRILL_TICKS_PER_FRAME);
				clearStrays();
				shot("drilling", i);
			}
		} finally {
			ctx.getInput().releaseKey(options -> options.keySprint);
		}
		serverDo(server -> {
			player(server).stopRiding();
			pod.discard();
		});
		ClientWait.until(ctx, "the client off the pod", client -> client.player.getVehicle() == null);
		ctx.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
	}

	private void lava() {
		BlockPos floor = new BlockPos(LAYER_X, 100, LAYER_Z + 40);
		serverDo(server -> {
			ServerLevel two = server.getLevel(LayerChain.dimension(2));
			loadChunks(two, floor);
			RoomCarver.carve(two, floor.offset(-ROOM, 0, -ROOM), floor.offset(ROOM, 7, ROOM), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			// A pool in the floor at the far wall, and a source in the far wall above it, which falls into the pool.
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = ROOM - 3; dz <= ROOM; dz++) {
					two.setBlock(floor.offset(dx, -1, dz), Blocks.LAVA.defaultBlockState(), 3);
				}
			}
			two.setBlock(floor.offset(0, 6, ROOM), Blocks.LAVA.defaultBlockState(), 3);
		});
		Vec3 stand = Vec3.atBottomCenterOf(floor);
		look(2, stand.add(0, 3, -ROOM + 1.5), stand.add(0, 1.5, ROOM));
		awaitRendered("lava");
		for (int i = 0; i < LAVA_FRAMES; i++) {
			ctx.waitTicks(LAVA_TICKS_PER_FRAME);
			shot("lava", i);
		}
	}

	private void terminal() {
		BlockPos terminal = serverGet(server -> Colony.anchor(server, ColonyAnchor.CONTRACT_TERMINAL).orElseThrow());
		look(0, Vec3.atCenterOf(terminal).add(0, 0.9, 2.6), Vec3.atCenterOf(terminal));
		awaitRendered("terminal");
		ctx.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(terminal)));
		ClientWait.screen(ctx, ContractScreen.class);
		ctx.getInput().setCursorPos(-10_000, -10_000);
		for (int i = 0; i < TERMINAL_FRAMES; i++) {
			shot("terminal", i);
			ctx.waitTicks(TERMINAL_TICKS_PER_FRAME);
		}
		ctx.setScreen(() -> null);
	}

	// ------------------------------------------------------------------------------------------------ helpers

	/**
	 * The point of rock the lamp lights best from {@code eye}: of sixteen rays round the compass, a little downward, the hit nearest
	 * {@link #WALL_DISTANCE} blocks away. Throws when no ray meets rock within {@link #WALL_REACH}: the cell is not a cave.
	 */
	private static Vec3 wallInView(ServerLevel level, Vec3 eye, ServerPlayer player) {
		Vec3 best = null;
		for (int i = 0; i < 16; i++) {
			double angle = 2 * Math.PI * i / 16;
			Vec3 end = eye.add(new Vec3(Math.sin(angle), -0.35, Math.cos(angle)).normalize().scale(WALL_REACH));
			BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player));
			if (hit.getType() == HitResult.Type.BLOCK && (best == null
					|| Math.abs(hit.getLocation().distanceTo(eye) - WALL_DISTANCE) < Math.abs(best.distanceTo(eye) - WALL_DISTANCE))) {
				best = hit.getLocation();
			}
		}
		if (best == null) {
			throw new AssertionError("no rock within " + WALL_REACH + " blocks of the cave cell at " + eye + " in " + level.dimension().identifier());
		}
		return best;
	}

	private static long[] skyTicks() {
		List<Long> ticks = new ArrayList<>();
		for (long t = 9000; t <= 15000; t += 300) {
			ticks.add(t);
		}
		for (long t = 21000; t <= 27000; t += 300) {
			ticks.add(t % 24000);
		}
		return ticks.stream().mapToLong(Long::longValue).toArray();
	}

	/** One frame of a clip: the still {@code clip-<clip>-NNN} and a frame of the run's MP4. */
	private void shot(String clip, int index) {
		screenshot(ctx, String.format("clip-%s-%03d", clip, index + 1));
		frame(ctx);
	}

	/** Puts the camera at {@code eye} in {@code layer}, looking at {@code target}, and waits until the client is there. */
	private void look(int layer, Vec3 eye, Vec3 target) {
		Vec3 d = target.subtract(eye);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
		serverDo(server -> {
			ServerPlayer player = player(server);
			player.setNoGravity(true);
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
			player.teleportTo(server.getLevel(LayerChain.dimension(layer)), eye.x, eye.y - EYE, eye.z, Set.of(), yaw, pitch, true);
		});
		ClientWait.until(ctx, "the camera at " + eye + " in layer " + layer, client -> client.level.dimension().equals(LayerChain.dimension(layer))
				&& client.player.distanceToSqr(eye.x, eye.y - EYE, eye.z) < 0.0001
				&& Math.abs(Mth.wrapDegrees(client.player.getYRot() - yaw)) < 0.01f);
	}

	/** Clears the mobs and waits until the light has settled, the chunks round the camera have rendered and the place's grade shows. */
	private void awaitRendered(String view) {
		clearStrays();
		ctx.waitTicks(20);
		ClientWait.until(ctx, "the " + view + " view lit, rendered and graded", () -> LookSkin.holdGrade(ctx, sp.getServer())
				&& !serverGet(server -> player(server).level().getLightEngine().hasLightWork())
				&& ctx.computeOnClient(client -> client.levelRenderer.hasRenderedAllSections()),
				() -> ctx.computeOnClient(client -> "sections rendered " + client.levelRenderer.hasRenderedAllSections() + ", "
						+ LookSkin.describeGrade(client)));
		ctx.runOnClient(client -> client.particleEngine.clearParticles());
	}

	/** Mobs, items and orbs of the world: where they walk differs every run, and the look books show none. */
	private void clearStrays() {
		serverDo(server -> {
			for (ServerLevel level : server.getAllLevels()) {
				List<Entity> strays = new ArrayList<>();
				level.getAllEntities().forEach(entity -> {
					if (entity instanceof Mob || entity instanceof ItemEntity || entity instanceof ExperienceOrb) {
						strays.add(entity);
					}
				});
				strays.forEach(Entity::discard);
			}
		});
	}

	private static void loadChunks(ServerLevel level, BlockPos centre) {
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				level.getChunk((centre.getX() >> 4) + dx, (centre.getZ() >> 4) + dz);
			}
		}
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	private static void command(MinecraftServer server, String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
	}

	private <T> T serverGet(Function<MinecraftServer, T> action) {
		return sp.getServer().computeOnServer(action::apply);
	}

	private void serverDo(Consumer<MinecraftServer> action) {
		sp.getServer().runOnServer(action::accept);
	}
}
