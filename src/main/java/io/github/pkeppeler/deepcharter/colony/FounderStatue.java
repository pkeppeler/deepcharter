package io.github.pkeppeler.deepcharter.colony;

import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The hands of the Host, the Founder's statue in the colony square (his height and the layout are in tools/colony/town.py). The colony
 * is built without them ({@link ColonyBuilder}): his body is one block display and his hands another, a piece of their own in the
 * colony's layout, and a work order puts them back. The hands are the same size and place as the body, so placing them completes him.
 */
public final class FounderStatue {
	/** How far from the statue's anchor a hands display may stand and still be his: the figure is 10 blocks tall. */
	private static final double REACH = 12;
	/** The marker is this many blocks under a hands display, which stands in the block the Host's feet are in. */
	private static final int MARKER_DEPTH = 3;

	private FounderStatue() {
	}

	/**
	 * A hands display lasts only while the world says the Host has his hands. The state is a block in the colony's own chunk data: a
	 * {@code structure_void} where the plinth's shaft has a plate ({@link #markerPos}), set when the hands are placed. A display that is
	 * found without it (3 blocks under the display, in its own chunk) discards itself the moment it is tracked, so a pair that outlives its state (a rebuild
	 * puts the plate back, a restored backup, a test that took the hands away while the chunk did not tick) never shows. The marker is
	 * in the colony's chunk data and loads with it, so a pair that the world owes survives a save and a reload.
	 */
	public static void init() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof Display.BlockDisplay display && display.getBlockState().equals(handsState())
					&& !level.getBlockState(display.blockPosition().below(MARKER_DEPTH)).is(Blocks.STRUCTURE_VOID)) {
				display.discard();
			}
		});
	}

	/** Where the state of the hands is kept: inside the plinth, {@link #MARKER_DEPTH} blocks under the Host's feet, which is under any hands display. Empty before the colony is built. */
	public static Optional<BlockPos> markerPos(MinecraftServer server) {
		return Colony.anchor(server, ColonyAnchor.STATUE).map(anchor -> anchor.below(MARKER_DEPTH));
	}

	/** Takes the hands away: the displays that are found and the marker, which gives the plinth its plate back. For tests and for a dev who wants them gone. */
	public static void removeHands(MinecraftServer server) {
		markerPos(server).ifPresent(pos -> {
			ServerLevel overworld = server.overworld();
			overworld.getChunk(pos);
			if (overworld.getBlockState(pos).is(Blocks.STRUCTURE_VOID)) {
				overworld.setBlock(pos, ColonyKit.RIVETED_PLATE_RED.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
		});
		hands(server).forEach(Entity::discard);
	}

	/** The block state of the display of the body: the sculpture block's piece of him. */
	public static BlockState bodyState() {
		return ColonyKit.SCULPTURE.defaultBlockState().setValue(KitSculptureBlock.PIECE, KitSculptureBlock.Piece.FOUNDER_C);
	}

	/** The block state of the display of the hands: the sculpture block's piece of them. */
	public static BlockState handsState() {
		return ColonyKit.SCULPTURE.defaultBlockState().setValue(KitSculptureBlock.PIECE, KitSculptureBlock.Piece.FOUNDER_C_HANDS);
	}

	/**
	 * True when the colony was built with this layout's Host: its saved statue anchor is where the layout puts him. A colony built before
	 * the rebuild has its statue elsewhere, and no body display. It reads saved data and the layout only, so it gives the same answer
	 * whichever chunks are loaded; an entity query would not.
	 */
	public static boolean hasHost(MinecraftServer server) {
		return Colony.placed(server).filter(colony -> {
			BlockPos wanted = colony.center().offset(ColonyLayout.read(server).anchors().get(ColonyAnchor.STATUE));
			return wanted.equals(colony.anchors().get(ColonyAnchor.STATUE));
		}).isPresent();
	}

	/** The displays of the statue's hands, loading the chunk they stand in; empty before the colony is built or while he has none. */
	public static List<Display.BlockDisplay> hands(MinecraftServer server) {
		return displays(server, handsState());
	}

	/** The displays of the statue's body, loading the chunk it stands in; empty before the colony is built. */
	public static List<Display.BlockDisplay> body(MinecraftServer server) {
		return displays(server, bodyState());
	}

	private static List<Display.BlockDisplay> displays(MinecraftServer server, BlockState state) {
		Optional<BlockPos> anchor = Colony.anchor(server, ColonyAnchor.STATUE);
		if (anchor.isEmpty()) {
			return List.of();
		}
		ServerLevel overworld = server.overworld();
		overworld.getChunk(anchor.get());
		AABB around = new AABB(anchor.get()).inflate(REACH);
		return overworld.getEntitiesOfClass(Display.BlockDisplay.class, around, display -> display.getBlockState().equals(state));
	}

	/**
	 * Gives the Host his hands: places the layout's hands piece where the body stands. A hand already there is replaced, so
	 * the call can be repeated. Throws before the colony is built.
	 */
	public static void restoreHands(MinecraftServer server) {
		ColonySite.Placed colony = Colony.placed(server).orElseThrow(() -> new IllegalStateException("the colony is not built: the Founder has no hands to restore"));
		hands(server).forEach(Entity::discard);
		ServerLevel overworld = server.overworld();
		overworld.setBlock(markerPos(server).orElseThrow(), Blocks.STRUCTURE_VOID.defaultBlockState(), Block.UPDATE_CLIENTS);
		ColonyLayout.read(server).later(ColonyLayout.HOST_HANDS).place(server.overworld(), colony.center());
	}
}
