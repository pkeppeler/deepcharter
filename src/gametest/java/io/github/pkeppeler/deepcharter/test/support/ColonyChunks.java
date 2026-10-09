package io.github.pkeppeler.deepcharter.test.support;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.AABB;

import io.github.pkeppeler.deepcharter.colony.ColonyLayout;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.colony.ColonyTuning;
import io.github.pkeppeler.deepcharter.colony.FounderStatue;

/**
 * The chunks of the colony's pad, and a wait for them to tick. An entity in a chunk that does not tick is not found by a query (the
 * Host's displays are entities), and a rebuild takes away the displays of the first try only in chunks that tick.
 */
public final class ColonyChunks {
	private ColonyChunks() {
	}

	/** One position, at the pad's ground, in each chunk the pad touches. */
	public static List<BlockPos> of(ColonySite.Placed colony) {
		int half = ColonyTuning.DEFAULT.padSize() / 2;
		BlockPos centre = colony.center();
		Set<Long> seen = new HashSet<>();
		List<BlockPos> chunks = new ArrayList<>();
		for (int x = centre.getX() - half; x < centre.getX() + half + 16; x += 16) {
			for (int z = centre.getZ() - half; z < centre.getZ() + half + 16; z += 16) {
				int chunkX = Math.min(x, centre.getX() + half - 1) >> 4;
				int chunkZ = Math.min(z, centre.getZ() + half - 1) >> 4;
				if (seen.add(((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL))) {
					chunks.add(new BlockPos(chunkX << 4, centre.getY(), chunkZ << 4));
				}
			}
		}
		return chunks;
	}

	/** Keeps the pad's chunks loaded and ticking for as long as the world lives. */
	public static void force(ServerLevel level, ColonySite.Placed colony) {
		for (BlockPos pos : of(colony)) {
			level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
		}
	}

	/**
	 * Runs {@code body} once, on the first tick when every chunk of {@code positions} ticks and {@code ready} holds (the entities of a
	 * chunk load a little after it ticks), and succeeds the test when it returns. Call from the test method, which must give itself
	 * {@link FarChunks#AWAIT_BUDGET_TICKS} ticks and does not call {@code succeed}.
	 */
	public static void whenTicking(GameTestHelper helper, ServerLevel level, List<BlockPos> positions, BooleanSupplier ready, Runnable body) {
		int[] ticking = {0};
		FarChunks.awaitEntityTicking(helper, level, positions, index -> ticking[0]++);
		boolean[] ran = {false};
		RuntimeException[] failed = {null};
		helper.succeedWhen(() -> {
			if (failed[0] != null) {
				throw failed[0];
			}
			if (ticking[0] < positions.size()) {
				throw helper.assertionException(Component.literal("waiting for the chunks to tick: " + ticking[0] + " of " + positions.size()));
			}
			if (!ready.getAsBoolean()) {
				throw helper.assertionException(Component.literal("waiting for the entities of the chunks to load"));
			}
			if (ran[0]) {
				return;
			}
			ran[0] = true;
			try {
				body.run();
			} catch (RuntimeException e) {
				failed[0] = e;
				throw e;
			}
		});
	}

	/** The block displays the layout's pieces place, apart from the Host's hands, which a work order places. */
	public static int displaysOf(MinecraftServer server) {
		return ColonyLayout.read(server).pieces().stream().mapToInt(ColonyLayout.Piece::displays).sum();
	}

	/** The block displays standing on the pad now, apart from the Host's hands. */
	public static int displaysOnThePad(ServerLevel level, ColonySite.Placed colony) {
		int half = ColonyTuning.DEFAULT.padSize() / 2;
		BlockPos centre = colony.center();
		AABB pad = new AABB(centre.getX() - half, centre.getY(), centre.getZ() - half, centre.getX() + half, centre.getY() + ColonyTuning.DEFAULT.clearHeight(),
				centre.getZ() + half);
		return level.getEntitiesOfClass(Display.BlockDisplay.class, pad, display -> !display.getBlockState().equals(FounderStatue.handsState())).size();
	}
}
