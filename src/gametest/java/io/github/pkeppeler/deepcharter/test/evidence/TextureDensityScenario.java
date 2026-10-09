package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.surface.SurfaceBlocks;
import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;

/**
 * Evidence scenario "texture-density" for #336 (docs/design/texture-density.md): the same views with each texture density variant.
 * A is the mod as it ships; B, C and D are test packs, each turned on in turn as a player does in the pack screen, and off again.
 *
 * <p>The views, each named {@code <variant>-<view>}: a cavern wall in layer 1 with every ore set in it, lit only by a pod's lamp (a
 * level 12 light, a tier 3 lights part, three blocks out from the wall, no night vision); the same wall close up; the wall of a bored
 * shaft at mid distance, lit every four blocks down; and the regolith south of the colony at noon. The player is a creative, flying
 * camera with the HUD hidden.
 */
public class TextureDensityScenario extends EvidenceScenario {
	private static final double EYE = 1.62;
	/** The cavern room in layer 1: its floor is at {@link #ROOM}, and its south wall, where the ores are, at {@code z + WALL}. */
	private static final BlockPos ROOM = new BlockPos(2600, 100, 2600);
	private static final int WALL = 3;
	private static final int HALF_WIDTH = 6;
	private static final int HEIGHT = 5;
	private static final int LAMP = 12;
	/** The bored shaft: 3 x 3 blocks, from its mouth at {@link #SHAFT} down {@link #SHAFT_DEPTH} blocks. */
	private static final BlockPos SHAFT = new BlockPos(2640, 150, 2600);
	private static final int SHAFT_DEPTH = 28;
	/** The ores in the cavern wall, by their place in it: blocks east of the room's middle, and up from its floor. */
	private static final Map<BlockPos, Block> WALL_BLOCKS = Map.ofEntries(
			Map.entry(new BlockPos(-4, 3, 0), OreRegistry.block(OreType.IRONIUM)),
			Map.entry(new BlockPos(-3, 3, 0), OreRegistry.block(OreType.IRONIUM)),
			Map.entry(new BlockPos(-5, 0, 0), OreRegistry.block(OreType.CICATRIUM)),
			Map.entry(new BlockPos(-3, 1, 0), OreRegistry.block(OreType.BRONZIUM)),
			Map.entry(new BlockPos(-1, 2, 0), OreRegistry.block(OreType.EINSTEINIUM)),
			Map.entry(new BlockPos(1, 1, 0), OreRegistry.block(OreType.GOLDIUM)),
			Map.entry(new BlockPos(2, 3, 0), OreRegistry.block(OreType.SILVERIUM)),
			Map.entry(new BlockPos(4, 2, 0), OreRegistry.block(OreType.PLATINIUM)),
			Map.entry(new BlockPos(5, 0, 0), HazardBlocks.COMPANY_ROCK));
	/** The variants, in order, with the test pack each turns on (none for A). */
	private static final List<Variant> VARIANTS = List.of(new Variant("a", null), new Variant("b", TestPacks.TEXTURE_DENSITY_B),
			new Variant("c", TestPacks.TEXTURE_DENSITY_C), new Variant("d", TestPacks.TEXTURE_DENSITY_D));
	/** The wall-clock limit of one settle. */
	private static final long SETTLE_LIMIT_NANOS = 120_000_000_000L;
	private static final int SETTLE_POLL_TICKS = 2;
	private static final int SETTLE_STABLE_POLLS = 5;

	private record Variant(String name, String pack) { }

	private ClientGameTestContext ctx;
	private TestSingleplayerContext sp;

	@Override
	protected String name() {
		return "texture-density";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		ctx = context;
		try {
			shoot();
		} finally {
			// The HUD is hidden for the stills; a full-suite run goes on to tests that draw on it.
			context.runOnClient(client -> {
				if (client.gui.hud.isHidden()) {
					client.gui.hud.toggle();
				}
			});
		}
	}

	private void shoot() {
		// A real world, as a player gets one (the design tour's seed), so the surface is the generator's regolith.
		try (TestSingleplayerContext singleplayer = ctx.worldBuilder().setUseConsistentSettings(false)
				.adjustSettings(state -> state.setSeed("deepcharter-design-tour")).create()) {
			sp = singleplayer;
			ClientWait.until(ctx, "the player in the world", client -> client.player != null && client.level != null);
			pinWorld();
			BlockPos square = serverGet(server -> Colony.placed(server).orElseThrow(() -> new AssertionError("no colony")).anchors()
					.get(ColonyAnchor.STATUE));
			Vec3 plain = regolithPlain(square);
			serverDo(server -> {
				ServerLevel one = server.getLevel(LayerChain.dimension(1));
				buildCavern(one);
				buildShaft(one);
			});
			ctx.runOnClient(client -> {
				client.options.setCameraType(CameraType.FIRST_PERSON);
				client.options.particles().set(ParticleStatus.MINIMAL);
				client.options.bobView().set(false);
				if (!client.gui.hud.isHidden()) {
					client.gui.hud.toggle();
				}
			});
			for (Variant variant : VARIANTS) {
				if (variant.pack() != null) {
					ClientPacks.enable(ctx, variant.pack());
				}
				try {
					views(variant.name(), plain);
				} finally {
					if (variant.pack() != null) {
						ClientPacks.disable(ctx, variant.pack());
					}
				}
			}
		}
	}

	private void views(String variant, Vec3 plain) {
		Vec3 wall = Vec3.atBottomCenterOf(ROOM).add(0, 2, WALL);
		view(1, wall.add(0, 0.1, -6), wall, 60);
		still(variant + "-cavern-wall-lamp-lit");
		view(1, wall.add(0.5, 0, -2), wall.add(0.5, 0, 0), 20);
		still(variant + "-cavern-wall-close");
		// From just under the shaft's mouth, by its north wall, down at its south wall.
		Vec3 mouth = Vec3.atBottomCenterOf(SHAFT);
		view(1, mouth.add(0, -1.5, -1.2), mouth.add(0, -12, 1.5), 20);
		still(variant + "-shaft-wall-mid");
		view(0, plain.add(0, 5, -9), plain, 40);
		still(variant + "-surface-regolith-day");
	}

	/**
	 * Gameplay noon, the visual sky at its brightest dusk, clear weather, no random ticks or mobs, and a creative, flying camera: the
	 * same pins as the design tour.
	 */
	private void pinWorld() {
		serverDo(server -> {
			GameRules rules = server.getGameRules();
			rules.set(GameRules.ADVANCE_TIME, false, server);
			rules.set(GameRules.ADVANCE_WEATHER, false, server);
			rules.set(GameRules.SPAWN_MOBS, false, server);
			rules.set(GameRules.SPAWN_MONSTERS, false, server);
			rules.set(GameRules.RANDOM_TICK_SPEED, 0, server);
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
			ResourceKey<WorldClock> sky = ResourceKey.create(Registries.WORLD_CLOCK, Identifier.fromNamespaceAndPath("deepcharter", "sky"));
			var clock = server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(sky);
			server.clockManager().setPaused(clock, true);
			server.clockManager().setTotalTicks(clock, 0);
			ServerPlayer player = player(server);
			player.setGameMode(GameType.CREATIVE);
			player.getInventory().clearContent();
			player.getAbilities().mayfly = true;
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
			player.setPermanentlyInvulnerable(true);
		});
	}

	/**
	 * A sealed room in the layer 1 rock with its south wall made plain stone and the ores (and one Company rock) set into it, and a
	 * pod lamp three blocks out from the wall. The room's other walls, floor and ceiling are the layer's own rock.
	 */
	private static void buildCavern(ServerLevel level) {
		loadChunks(level, ROOM, 2);
		RoomCarver.carve(level, ROOM.offset(-HALF_WIDTH, 0, -WALL - 5), ROOM.offset(HALF_WIDTH, HEIGHT - 1, WALL - 1), Blocks.AIR.defaultBlockState());
		for (int dx = -HALF_WIDTH - 1; dx <= HALF_WIDTH + 1; dx++) {
			for (int dy = -1; dy <= HEIGHT; dy++) {
				level.setBlock(ROOM.offset(dx, dy, WALL), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
		WALL_BLOCKS.forEach((at, block) -> level.setBlock(ROOM.offset(at.getX(), at.getY(), WALL), block.defaultBlockState(), Block.UPDATE_ALL));
		level.setBlock(ROOM.offset(0, 2, 0), lamp(), Block.UPDATE_ALL);
	}

	/** A 3 x 3 shaft bored straight down through the layer's own rock, with a lamp at its middle every four blocks. */
	private static void buildShaft(ServerLevel level) {
		loadChunks(level, SHAFT, 2);
		RoomCarver.carve(level, SHAFT.offset(-1, -SHAFT_DEPTH, -1), SHAFT.offset(1, 0, 1), Blocks.AIR.defaultBlockState());
		for (int depth = 2; depth < SHAFT_DEPTH; depth += 4) {
			level.setBlock(SHAFT.below(depth), lamp(), Block.UPDATE_ALL);
		}
	}

	private static BlockState lamp() {
		return Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, LAMP);
	}

	/**
	 * The top of the first open regolith plain out from the square, going west, north and east in steps of 20 blocks: a column whose
	 * 5 x 5 blocks round it are all regolith on top, within 3 blocks of each other in height. Fails, naming the ground it saw, if none
	 * is in reach.
	 */
	private Vec3 regolithPlain(BlockPos square) {
		return serverGet(server -> {
			ServerLevel surface = server.overworld();
			List<String> seen = new ArrayList<>();
			for (int distance = 60; distance <= 300; distance += 20) {
				for (BlockPos step : List.of(new BlockPos(-1, 0, 0), new BlockPos(0, 0, -1), new BlockPos(1, 0, 0))) {
					BlockPos at = square.offset(step.getX() * distance, 0, step.getZ() * distance);
					loadChunks(surface, at, 1);
					BlockPos top = surface.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, at);
					if (isPlain(surface, at)) {
						return Vec3.atBottomCenterOf(top);
					}
					seen.add(BuiltInRegistries.BLOCK.getKey(surface.getBlockState(top.below()).getBlock()).getPath() + "@" + top.below().toShortString());
				}
			}
			throw new AssertionError("no open regolith plain within 300 blocks of the square at " + square.toShortString() + "; saw " + seen);
		});
	}

	private static boolean isPlain(ServerLevel surface, BlockPos at) {
		int low = Integer.MAX_VALUE;
		int high = Integer.MIN_VALUE;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				BlockPos top = surface.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, at.offset(dx, 0, dz));
				if (!surface.getBlockState(top.below()).is(SurfaceBlocks.REGOLITH)) {
					return false;
				}
				low = Math.min(low, top.getY());
				high = Math.max(high, top.getY());
			}
		}
		return high - low <= 3;
	}

	private static void loadChunks(ServerLevel level, BlockPos at, int radius) {
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				level.getChunk((at.getX() >> 4) + dx, (at.getZ() >> 4) + dz);
			}
		}
	}

	/** Puts the camera at {@code eye} in {@code layer}, looking at {@code target}, then waits {@code wait} ticks. */
	private void view(int layer, Vec3 eye, Vec3 target, int wait) {
		Vec3 d = target.subtract(eye);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
		serverDo(server -> {
			ServerPlayer player = player(server);
			player.setNoGravity(true);
			player.teleportTo(server.getLevel(LayerChain.dimension(layer)), eye.x, eye.y - EYE, eye.z, Set.of(), yaw, pitch, true);
		});
		ClientWait.until(ctx, "the camera at " + eye + " in layer " + layer, client -> client.level.dimension().equals(LayerChain.dimension(layer))
				&& client.player.distanceToSqr(eye.x, eye.y - EYE, eye.z) < 0.0001
				&& Math.abs(Mth.wrapDegrees(client.player.getYRot() - yaw)) < 0.01f && Math.abs(client.player.getXRot() - pitch) < 0.01f);
		ctx.waitTicks(wait);
	}

	/**
	 * Waits, on a wall-clock limit, until the light has settled, every chunk section in view is rendered, and no mob or loose item is
	 * in the world, for {@link #SETTLE_STABLE_POLLS} looks in a row; then shoots the still.
	 */
	private void still(String stillName) {
		long deadline = System.nanoTime() + SETTLE_LIMIT_NANOS;
		int stable = 0;
		while (stable < SETTLE_STABLE_POLLS) {
			serverDo(server -> server.getAllLevels().forEach(level -> level.getAllEntities().forEach(entity -> {
				if (stray(entity)) {
					entity.discard();
				}
			})));
			ctx.runOnClient(client -> client.particleEngine.clearParticles());
			ctx.waitTicks(SETTLE_POLL_TICKS);
			boolean lit = serverGet(server -> {
				for (ServerLevel level : server.getAllLevels()) {
					if (level.getLightEngine().hasLightWork()) {
						return false;
					}
				}
				return true;
			});
			stable = lit && ctx.computeOnClient(TextureDensityScenario::rendered) ? stable + 1 : 0;
			if (System.nanoTime() > deadline) {
				throw new AssertionError("still " + stillName + ": the world did not settle in " + SETTLE_LIMIT_NANOS / 1_000_000_000L + " s");
			}
		}
		screenshot(ctx, stillName);
		frame(ctx);
	}

	private static boolean rendered(Minecraft client) {
		for (Entity entity : client.level.entitiesForRendering()) {
			if (stray(entity)) {
				return false;
			}
		}
		return client.levelRenderer.hasRenderedAllSections();
	}

	private static boolean stray(Entity entity) {
		return entity instanceof Mob || entity instanceof ItemEntity;
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	private <T> T serverGet(Function<MinecraftServer, T> action) {
		return sp.getServer().computeOnServer(action::apply);
	}

	private void serverDo(Consumer<MinecraftServer> action) {
		sp.getServer().runOnServer(action::accept);
	}
}
