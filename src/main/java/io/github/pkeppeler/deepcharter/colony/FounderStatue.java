package io.github.pkeppeler.deepcharter.colony;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The hands of the Founder statue in the colony square. The colony is built without them ({@link ColonyBuilder}) and a work order
 * puts them back. Both positions are offsets from the pad's centre, the same ground the statue stands on.
 */
public final class FounderStatue {
	/** The end of each arm, west then east. */
	private static final List<BlockPos> HAND_OFFSETS = List.of(new BlockPos(-3, 6, 0), new BlockPos(3, 6, 0));

	private FounderStatue() {
	}

	/** A hand: a slab of the statue's bronze. */
	public static BlockState hand() {
		return Blocks.CUT_COPPER_SLAB.waxed().unaffected().defaultBlockState();
	}

	/** Where the hands go, or empty before the colony is built or when its data is unreadable. */
	public static Optional<List<BlockPos>> handPositions(MinecraftServer server) {
		return Colony.placed(server).map(colony -> HAND_OFFSETS.stream().map(offset -> colony.center().offset(offset)).toList());
	}

	/** Puts both hands in place; a hand already there stays. Throws before the colony is built: check {@link #handPositions} first. */
	public static void restoreHands(MinecraftServer server) {
		List<BlockPos> positions = handPositions(server).orElseThrow(() -> new IllegalStateException("the colony is not built: the Founder has no hands to restore"));
		ServerLevel overworld = server.overworld();
		for (BlockPos pos : positions) {
			// Loads the chunk first: a block set in an unloaded chunk is lost.
			overworld.getChunk(pos);
			overworld.setBlock(pos, hand(), Block.UPDATE_ALL);
		}
	}
}
