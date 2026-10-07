package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Locale;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalScreen;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;

/**
 * Evidence scenario "m2-terminal-framework" for #59: a charter opens the offline fuel pump terminal, puts its two parts in one
 * by one, and the terminal comes online. Stills of the offline and the online screen.
 */
public class TerminalFrameworkScenario extends EvidenceScenario {
	private static final int TICKS_PER_FRAME = 3;
	private static final int TYPING_FRAMES = 45;
	private static final int HOLD_FRAMES = 6;
	private static final int WAIT_TICKS = 200;

	@Override
	protected String name() {
		return "m2-terminal-framework";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			BlockPos pump = singleplayer.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				if (Charters.found(server, player.getUUID(), "Riggs and Sons").isPresent()) {
					throw new AssertionError("founding should succeed");
				}
				BlockPos at = player.blockPosition().relative(Direction.EAST, 2);
				server.overworld().setBlock(at, TerminalTypes.FUEL_PUMP.block().defaultBlockState(), 3);
				TerminalTypes.FUEL_PUMP.parts().forEach(part -> player.getInventory().add(new ItemStack(part)));
				return at;
			});

			context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(pump)));
			context.waitForScreen(TerminalScreen.class);
			TerminalScreen screen = context.computeOnClient(client -> (TerminalScreen) client.gui.screen());
			for (int i = 0; i < TYPING_FRAMES && !screen.typewriter().done(); i++) {
				context.waitTicks(TICKS_PER_FRAME);
				frame(context);
			}
			hold(context);
			screenshot(context, "terminal-offline");

			for (int part = 0; part < TerminalTypes.FUEL_PUMP.parts().size(); part++) {
				String label = "INSERT " + new ItemStack(TerminalTypes.FUEL_PUMP.parts().get(part)).getHoverName().getString().toUpperCase(Locale.ROOT);
				context.clickScreenButton(label);
				int inserted = part + 1;
				context.waitFor(client -> client.gui.screen() instanceof TerminalScreen open
						&& (open.online() || open.view().parts().stream().filter(status -> status.inserted()).count() == inserted), WAIT_TICKS);
				hold(context);
			}

			context.waitFor(client -> client.gui.screen() instanceof TerminalScreen open && open.online(), WAIT_TICKS);
			TerminalScreen online = context.computeOnClient(client -> (TerminalScreen) client.gui.screen());
			for (int i = 0; i < TYPING_FRAMES && !online.typewriter().done(); i++) {
				context.waitTicks(TICKS_PER_FRAME);
				frame(context);
			}
			hold(context);
			screenshot(context, "terminal-online");
			context.setScreen(() -> null);
		}
	}

	private void hold(ClientGameTestContext context) {
		for (int i = 0; i < HOLD_FRAMES; i++) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
	}
}
