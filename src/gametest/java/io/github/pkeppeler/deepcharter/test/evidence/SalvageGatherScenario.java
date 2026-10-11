package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Comparator;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.test.support.BlockSurvey;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;

/**
 * Evidence scenario "salvage-gather" (#245): a new player on foot, bare handed, with the hotbar showing, walks up to a crate the world
 * generated and breaks it, picks up what falls out (wood, scrap, cloth), then does the same to a fungal stalk on a crater floor. Both
 * are the real generated features, found by scanning the world, and the drops are the real loot tables. The two walks are cut: the
 * crater is a long way from the nearest crate.
 */
public class SalvageGatherScenario extends EvidenceScenario {
	/** The survey looks this many chunks each way of the colony, and a crate must be this far out to be beyond the colony pad. */
	private static final int SURVEY_CHUNKS = 8;
	private static final int MIN_CRATE_DISTANCE = 70;
	private static final int TICKS_PER_FRAME = 2;
	private static final int WALK_UP_TICKS = 40;
	private static final int PICK_UP_TICKS = 16;
	private static final int HOLD_FRAMES = 10;
	/** How far in front of the block the player starts, and how near the walk ends. */
	private static final int START_DISTANCE = 4;

	private ClientGameTestContext ctx;
	private TestSingleplayerContext world;

	@Override
	protected String name() {
		return "salvage-gather";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		ctx = context;
		try (TestSingleplayerContext singleplayer = context.worldBuilder().setUseConsistentSettings(false)
				.adjustSettings(state -> state.setSeed("deepcharter-design-tour")).create()) {
			world = singleplayer;
			ClientWait.until(context, "the player in the world", client -> client.player != null && client.level != null);
			EvidenceWorld.pin(context, singleplayer);
			context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
			BlockPos crate = world.getServer().computeOnServer(server -> nearest(server, "salvage_crate", false));
			BlockPos stalk = world.getServer().computeOnServer(server -> nearest(server, "fungal_stalk", true));
			world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst().getInventory().clearContent());
			gather(crate, false);
			gather(stalk, true);
			screenshot(context, "gathered");
		}
	}

	/** Walks up to {@code target}, breaks it bare handed and picks up what falls; a stalk is broken at its foot and then at head height. */
	private void gather(BlockPos target, boolean twice) {
		stand(target);
		walk(WALK_UP_TICKS);
		breakBlock(target);
		if (twice) {
			breakBlock(target.above());
		}
		walk(PICK_UP_TICKS);
		for (int i = 0; i < HOLD_FRAMES; i++) {
			ctx.waitTicks(TICKS_PER_FRAME); // tick-wait: the clip holds on the full hotbar for a fixed number of frames
			frame(ctx);
		}
	}

	/** Puts the player {@link #START_DISTANCE} blocks south of the block, on the ground, bare handed, and looking at it. */
	private void stand(BlockPos target) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			ServerLevel level = player.level();
			player.setGameMode(GameType.SURVIVAL);
			player.setNoGravity(false);
			player.getAbilities().flying = false;
			player.getAbilities().mayfly = false;
			player.onUpdateAbilities();
			BlockPos feet = target.offset(0, 0, START_DISTANCE);
			while (!level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)) {
				feet = feet.below();
			}
			player.teleportTo(level, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, Set.of(), 180f, 0f, true);
		});
		ClientWait.until(ctx, "the player next to " + target.toShortString(), client -> client.player.blockPosition().distSqr(target) < 40
				&& client.player.onGround() && client.level.getChunkSource().hasChunk(target.getX() >> 4, target.getZ() >> 4));
		ctx.getInput().lookAt(target);
		ctx.waitTicks(10); // tick-wait: the chunks and the light round the player come in
		frame(ctx);
	}

	/** Holds forward for {@code ticks} ticks, a frame every {@link #TICKS_PER_FRAME}. */
	private void walk(int ticks) {
		ctx.getInput().holdKey(options -> options.keyUp);
		for (int tick = 0; tick < ticks; tick += TICKS_PER_FRAME) {
			ctx.waitTicks(TICKS_PER_FRAME); // tick-wait: a walk of a fixed length
			frame(ctx);
		}
		ctx.getInput().releaseKey(options -> options.keyUp);
	}

	/** Mines the block bare handed, a frame every {@link #TICKS_PER_FRAME} ticks. */
	private void breakBlock(BlockPos target) {
		mineBlock(ctx, world, target, TICKS_PER_FRAME);
	}

	/**
	 * The block of the given kind nearest the colony that the player can walk up to: the crates must stand beyond the pad, the stalk must have
	 * a free standing place {@link #START_DISTANCE} blocks south of its foot. Failing that, the run fails and says so.
	 */
	private static BlockPos nearest(MinecraftServer server, String path, boolean foot) {
		Block wanted = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
		ServerLevel level = server.overworld();
		return BlockSurvey.find(level, SURVEY_CHUNKS, wanted).stream().filter(at -> reachable(level, at, foot))
				.min(Comparator.comparingDouble(SalvageGatherScenario::distance))
				.orElseThrow(() -> new AssertionError("no " + path + " within " + SURVEY_CHUNKS + " chunks of the colony that a player can walk up to"));
	}

	private static boolean reachable(ServerLevel level, BlockPos at, boolean foot) {
		if (foot ? level.getBlockState(at.below()).is(level.getBlockState(at).getBlock()) : distance(at) < MIN_CRATE_DISTANCE) {
			return false;
		}
		for (int dz = 1; dz <= START_DISTANCE; dz++) {
			BlockPos cell = at.offset(0, 0, dz);
			boolean free = level.getBlockState(cell).isAir() && level.getBlockState(cell.above()).isAir();
			boolean ground = level.getBlockState(cell.below()).isFaceSturdy(level, cell.below(), Direction.UP);
			if (!free || !ground) {
				return false;
			}
		}
		return true;
	}

	private static double distance(BlockPos at) {
		return Vec3.atCenterOf(at).horizontalDistanceSqr();
	}
}
