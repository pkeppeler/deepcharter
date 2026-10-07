package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.market.AccountHud;
import io.github.pkeppeler.deepcharter.client.market.OreProcessorScreen;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;

/**
 * Evidence scenario "m2-ore-processor" for #68: a charter with an empty account opens its repaired ore processor, sells the ore in
 * a parked pod's bay, then the ore it carries, and the account in the corner of the HUD and on the screen climbs with each sale.
 */
public class OreProcessorScenario extends EvidenceScenario {
	private static final int TICKS_PER_FRAME = 3;
	private static final int TYPING_FRAMES = 45;
	private static final int HOLD_FRAMES = 8;
	private static final int WAIT_TICKS = 200;

	@Override
	protected String name() {
		return "m2-ore-processor";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			BlockPos processor = singleplayer.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				if (Charters.found(server, player.getUUID(), "Riggs and Sons").isPresent()) {
					throw new AssertionError("founding should succeed");
				}
				BlockPos at = player.blockPosition().relative(Direction.EAST, 2);
				server.overworld().setBlock(at, TerminalTypes.ORE_PROCESSOR.block().defaultBlockState(), 3);
				RepairState state = RepairState.get(server);
				for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR)) {
					type.parts().forEach(part -> state.insert(type, part));
				}
				player.getInventory().add(OreRegistry.stack(OreType.IRONIUM));
				player.getInventory().add(OreRegistry.stack(OreType.SILVERIUM));
				PodEntity pod = PodRegistry.POD.create(server.overworld(), EntitySpawnReason.COMMAND);
				pod.setPos(player.position().add(0, 0, 4));
				server.overworld().addFreshEntity(pod);
				pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM));
				pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.PLATINIUM));
				return at;
			});

			context.waitFor(client -> AccountHud.text().isPresent(), WAIT_TICKS);
			context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(processor)));
			context.waitForScreen(OreProcessorScreen.class);
			OreProcessorScreen screen = context.computeOnClient(client -> (OreProcessorScreen) client.gui.screen());
			for (int i = 0; i < TYPING_FRAMES && !screen.typewriter().done(); i++) {
				context.waitTicks(TICKS_PER_FRAME);
				frame(context);
			}
			hold(context);
			screenshot(context, "processor-before-sale");

			long cargo = OreType.GOLDIUM.value() + OreType.PLATINIUM.value();
			context.clickScreenButton("SELL ALL POD CARGO");
			context.waitFor(client -> AccountHud.text().map(text -> text.getString().endsWith("$" + cargo)).orElse(false), WAIT_TICKS);
			hold(context);
			screenshot(context, "processor-after-cargo-sale");

			long total = cargo + OreType.IRONIUM.value() + OreType.SILVERIUM.value();
			context.clickScreenButton("SELL ALL CARRIED ORE");
			context.waitFor(client -> AccountHud.text().map(text -> text.getString().endsWith("$" + total)).orElse(false), WAIT_TICKS);
			hold(context);
			screenshot(context, "processor-after-inventory-sale");
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
