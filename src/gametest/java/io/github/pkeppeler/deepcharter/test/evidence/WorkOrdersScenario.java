package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.market.OreProcessorScreen;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;

/**
 * Evidence scenario "m2-work-orders" for #80: the Founder statue stands in the colony square without its hands. A charter opens
 * the colony's ore processor, sees the work order "Restore the Founder's hands", hands in four Bronzium and then the six still
 * owed, and the statue's hands appear.
 */
public class WorkOrdersScenario extends EvidenceScenario {
	private static final int TICKS_PER_FRAME = 3;
	private static final int TYPING_FRAMES = 45;
	private static final int HOLD_FRAMES = 8;
	private static final int SETTLE_TICKS = 60;
	private static final int WAIT_TICKS = 200;
	private static final String FIRST_DELIVERY = "4 / 10 BRONZIUM";

	@Override
	protected String name() {
		return "m2-work-orders";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitFor(client -> client.player != null && client.level != null);
			ColonySite.Placed colony = singleplayer.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.setPermanentlyInvulnerable(true);
				// The Handbook in the hand would cover the view.
				player.getInventory().clearContent();
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
				if (Charters.found(server, player.getUUID(), "Riggs and Sons").isPresent()) {
					throw new AssertionError("founding should succeed");
				}
				RepairState state = RepairState.get(server);
				for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR)) {
					type.parts().forEach(part -> state.insert(type, part));
				}
				return Colony.placed(server).orElseThrow(() -> new AssertionError("the colony was not built when the world started"));
			});
			BlockPos processor = colony.anchors().get(ColonyAnchor.ORE_PROCESSOR);
			Vec3 hands = Vec3.atCenterOf(colony.center()).add(0, 6, 0);
			Vec3 statueViewpoint = Vec3.atBottomCenterOf(colony.center()).add(0, 1, 11);
			Vec3 terminalViewpoint = Vec3.atBottomCenterOf(colony.center()).add(-4, 1, -4);

			stand(singleplayer, statueViewpoint, hands);
			context.waitTicks(SETTLE_TICKS);
			screenshot(context, "statue-without-hands");
			hold(context);

			stand(singleplayer, terminalViewpoint, Vec3.atCenterOf(processor));
			context.waitTicks(SETTLE_TICKS);
			context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(processor)));
			context.waitForScreen(OreProcessorScreen.class);
			OreProcessorScreen screen = context.computeOnClient(client -> (OreProcessorScreen) client.gui.screen());
			for (int i = 0; i < TYPING_FRAMES && !screen.typewriter().done(); i++) {
				context.waitTicks(TICKS_PER_FRAME);
				frame(context);
			}
			hold(context);
			screenshot(context, "work-order-on-the-terminal");

			give(singleplayer, 4);
			context.clickScreenButton("DELIVER BRONZIUM");
			context.waitFor(client -> screen.orderLines().getLast().equals(FIRST_DELIVERY), WAIT_TICKS);
			hold(context);
			screenshot(context, "partial-delivery");

			give(singleplayer, 6);
			context.clickScreenButton("DELIVER BRONZIUM");
			context.waitFor(client -> screen.orderLines().getLast().endsWith("DONE"), WAIT_TICKS);
			hold(context);
			screenshot(context, "order-done");
			context.setScreen(() -> null);

			stand(singleplayer, statueViewpoint, hands);
			context.waitTicks(SETTLE_TICKS);
			screenshot(context, "statue-with-hands-restored");
			hold(context);
		}
	}

	private static void give(TestSingleplayerContext singleplayer, int count) {
		singleplayer.getServer().runOnServer(server -> {
			for (int i = 0; i < count; i++) {
				server.getPlayerList().getPlayers().getFirst().getInventory().add(OreRegistry.stack(OreType.BRONZIUM));
			}
		});
	}

	private void hold(ClientGameTestContext context) {
		for (int i = 0; i < HOLD_FRAMES; i++) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
	}

	/** Puts the player on the ground at {@code at} in the overworld, looking at {@code target}. */
	private static void stand(TestSingleplayerContext singleplayer, Vec3 at, Vec3 target) {
		Vec3 eye = at.add(0, 1.62, 0);
		Vec3 d = target.subtract(eye);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
		singleplayer.getServer().runOnServer(server ->
				server.getPlayerList().getPlayers().getFirst().teleportTo(server.overworld(), at.x, at.y, at.z, Set.of(), yaw, pitch, true));
	}
}
