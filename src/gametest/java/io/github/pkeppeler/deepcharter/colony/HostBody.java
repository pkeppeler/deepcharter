package io.github.pkeppeler.deepcharter.colony;

import net.minecraft.world.level.block.state.BlockState;

/** The Host's body as a test sees it. It lives in the colony package because the sculpture block and its pieces are package-private. */
public final class HostBody {
	private HostBody() {
	}

	/** The block state of the display of the body: the sculpture block's piece of him. */
	public static BlockState state() {
		return ColonyKit.SCULPTURE.defaultBlockState().setValue(KitSculptureBlock.PIECE, KitSculptureBlock.Piece.FOUNDER_C);
	}
}
