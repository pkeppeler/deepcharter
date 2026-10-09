package io.github.pkeppeler.deepcharter.colony;

import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import io.github.pkeppeler.deepcharter.market.WorkOrder;
import io.github.pkeppeler.deepcharter.market.WorkOrderData;

/**
 * The hands of the Host, the Founder's statue in the colony square (his height and the layout are in tools/colony/town.py). The colony
 * is built without them ({@link ColonyBuilder}): his body is one block display and his hands another, a piece of their own in the
 * colony's layout, and a work order puts them back. The hands are the same size and place as the body, so placing them completes him.
 */
public final class FounderStatue {
	/** How far from the statue's anchor a hands display may stand and still be his: the figure is 10 blocks tall. */
	private static final double REACH = 12;

	private FounderStatue() {
	}

	/**
	 * A hands display lasts only while the world says the Host has his hands, that is while a charter has completed the work order. A
	 * display that is found without that state discards itself the moment it is tracked, whatever chunk it is in: so a pair that
	 * outlives its state (a rebuild, a restored backup, a test that cleared the state while the chunk did not tick) never shows. The
	 * state is the work order's own saved data, so nothing new is saved.
	 */
	public static void init() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof Display.BlockDisplay display && display.getBlockState().equals(handsState()) && !handsAreOwed(level.getServer())) {
				display.discard();
			}
		});
	}

	/** True when the Host should have his hands: a charter has completed the order. False too when the data cannot be read: then nothing is discarded. */
	private static boolean handsAreOwed(MinecraftServer server) {
		WorkOrderData data = WorkOrderData.get(server);
		return !data.isReadable() || data.anyCompleted(WorkOrder.FOUNDERS_HANDS);
	}

	/** The block state of the display of the body: the sculpture block's piece of him. */
	public static BlockState bodyState() {
		return ColonyKit.SCULPTURE.defaultBlockState().setValue(KitSculptureBlock.PIECE, KitSculptureBlock.Piece.FOUNDER_C);
	}

	/** The block state of the display of the hands: the sculpture block's piece of them. */
	public static BlockState handsState() {
		return ColonyKit.SCULPTURE.defaultBlockState().setValue(KitSculptureBlock.PIECE, KitSculptureBlock.Piece.FOUNDER_C_HANDS);
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
		ColonyLayout.read(server).later(ColonyLayout.HOST_HANDS).place(server.overworld(), colony.center());
	}
}
