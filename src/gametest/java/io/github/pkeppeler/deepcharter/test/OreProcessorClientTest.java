package io.github.pkeppeler.deepcharter.test;

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.FounderStatue;
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
 * Client GameTest for #68: the account HUD shows the charter's balance, the processor screen opens, and each sell button sells
 * through the server so that both the HUD and the screen show the new balance.
 */
public class OreProcessorClientTest implements FabricClientGameTest {
	private static final String CHARTER = "Processor Test Charter";
	private static final int WAIT_TICKS = 200;

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			BlockPos processor = singleplayer.getServer().computeOnServer(OreProcessorClientTest::setUp);

			context.waitFor(client -> hud(client).equals(CHARTER + "  $0"), WAIT_TICKS);
			check(hud(context).equals(CHARTER + "  $0"), "the HUD shows the charter and an empty account, got '" + hud(context) + "'");

			context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(processor)));
			context.waitForScreen(OreProcessorScreen.class);
			OreProcessorScreen screen = context.computeOnClient(client -> (OreProcessorScreen) client.gui.screen());
			context.waitFor(client -> screen.typewriter().done(), WAIT_TICKS);

			long cargo = OreType.GOLDIUM.value() + OreType.PLATINIUM.value();
			context.clickScreenButton("SELL ALL POD CARGO");
			context.waitFor(client -> hud(client).endsWith("$" + cargo), WAIT_TICKS);
			check(context.computeOnClient(client -> client.gui.screen() == screen), "a sale updates the screen in place");
			check(context.computeOnClient(client -> OreProcessorScreen.accountLine()).equals("ACCOUNT $" + cargo),
					"the screen shows the new balance, got '" + context.computeOnClient(client -> OreProcessorScreen.accountLine()) + "'");

			long carried = OreType.IRONIUM.value() + OreType.SILVERIUM.value();
			context.clickScreenButton("SELL ALL CARRIED ORE");
			context.waitFor(client -> hud(client).endsWith("$" + (cargo + carried)), WAIT_TICKS);
			check(hud(context).equals(CHARTER + "  $" + (cargo + carried)), "the HUD shows the total, got '" + hud(context) + "'");
			int ore = singleplayer.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				return player.getInventory().countItem(OreRegistry.item(OreType.IRONIUM)) + player.getInventory().countItem(OreRegistry.item(OreType.SILVERIUM));
			});
			check(ore == 0, "the sold ore left the inventory, " + ore + " remain");

			// #80: the work order is listed with no progress, and handing in ten Bronzium finishes it and restores the Founder's hands.
			check(context.computeOnClient(client -> screen.orderLines()).equals(List.of("WORK ORDERS", "RESTORE THE FOUNDER'S HANDS", "0 / 10 BRONZIUM")),
					"the screen lists the work order, got " + context.computeOnClient(client -> screen.orderLines()));
			singleplayer.getServer().runOnServer(server -> {
				for (int i = 0; i < 10; i++) {
					server.getPlayerList().getPlayers().getFirst().getInventory().add(OreRegistry.stack(OreType.BRONZIUM));
				}
			});
			context.clickScreenButton("DELIVER BRONZIUM");
			context.waitFor(client -> screen.orderLines().getLast().endsWith("DONE"), WAIT_TICKS);
			check(singleplayer.getServer().computeOnServer(WorkOrdersTest::handsRestored), "the Founder's hands are restored");
			singleplayer.getServer().runOnServer(server -> FounderStatue.handPositions(server).orElseThrow()
					.forEach(pos -> server.overworld().setBlock(pos, Blocks.AIR.defaultBlockState(), 3)));
			context.setScreen(() -> null);
		}
	}

	/** The player founds a charter, carries two ores, and has a repaired processor and a pod with two ores in its bay beside them. */
	private static BlockPos setUp(MinecraftServer server) {
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		if (Charters.found(server, player.getUUID(), CHARTER).isPresent()) {
			throw new AssertionError("founding should succeed");
		}
		BlockPos processor = player.blockPosition().relative(Direction.EAST, 2);
		server.overworld().setBlock(processor, TerminalTypes.ORE_PROCESSOR.block().defaultBlockState(), 3);
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
		return processor;
	}

	private static String hud(ClientGameTestContext context) {
		return context.computeOnClient(OreProcessorClientTest::hud);
	}

	private static String hud(Minecraft client) {
		return AccountHud.text().map(text -> text.getString()).orElse("");
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
