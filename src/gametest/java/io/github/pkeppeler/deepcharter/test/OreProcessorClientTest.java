package io.github.pkeppeler.deepcharter.test;

import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.FounderStatue;
import io.github.pkeppeler.deepcharter.client.market.AccountHud;
import io.github.pkeppeler.deepcharter.client.market.OrderRowButton;
import io.github.pkeppeler.deepcharter.client.market.OreProcessorScreen;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.market.WorkOrder;
import io.github.pkeppeler.deepcharter.market.WorkOrdersView;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/**
 * Client GameTest for #68: the account HUD shows the charter's balance, the processor screen opens, and each sell button sells
 * through the server so that both the HUD and the screen show the new balance.
 */
public class OreProcessorClientTest implements FabricClientGameTest {
	private static final String CHARTER = "Processor Test Charter";

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			BlockPos processor = singleplayer.getServer().computeOnServer(OreProcessorClientTest::setUp);

			ClientWait.until(context, "the hud showing $0 cargo", client -> hud(client).equals(CHARTER + "  $0"), client -> "hud '" + hud(client) + "'");
			require(hud(context).equals(CHARTER + "  $0"), "the HUD shows the charter and an empty account, got '" + hud(context) + "'");

			context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(processor)));
			ClientWait.screen(context, OreProcessorScreen.class);
			OreProcessorScreen screen = context.computeOnClient(client -> (OreProcessorScreen) client.gui.screen());
			ClientWait.until(context, "the processor screen finished typing", client -> screen.typewriter().done(), client -> "typewriter text '" + screen.typewriter().text() + "'");

			long cargo = OreType.GOLDIUM.value() + OreType.PLATINIUM.value();
			context.clickScreenButton("SELL ALL POD CARGO");
			ClientWait.until(context, "the hud showing the loaded cargo", client -> hud(client).endsWith("$" + cargo), client -> "hud '" + hud(client) + "'");
			require(context.computeOnClient(client -> client.gui.screen() == screen), "a sale updates the screen in place");
			require(context.computeOnClient(client -> OreProcessorScreen.accountLine()).equals("ACCOUNT $" + cargo),
					"the screen shows the new balance, got '" + context.computeOnClient(client -> OreProcessorScreen.accountLine()) + "'");

			long carried = OreType.IRONIUM.value() + OreType.SILVERIUM.value();
			context.clickScreenButton("SELL ALL CARRIED ORE");
			ClientWait.until(context, "the hud showing the carried ore added", client -> hud(client).endsWith("$" + (cargo + carried)), client -> "hud '" + hud(client) + "'");
			require(hud(context).equals(CHARTER + "  $" + (cargo + carried)), "the HUD shows the total, got '" + hud(context) + "'");
			int ore = singleplayer.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				return player.getInventory().countItem(OreRegistry.item(OreType.IRONIUM)) + player.getInventory().countItem(OreRegistry.item(OreType.SILVERIUM));
			});
			require(ore == 0, "the sold ore left the inventory, " + ore + " remain");

			// #313: four spoil in the parked pod's bay become two slag brick in its rack for $4.
			singleplayer.getServer().runOnServer(server -> PodLining.modify(parkedPod(server),
					state -> state.withSpoil(4)));
			context.clickScreenButton("MAKE SLAG BRICK  $2 EACH");
			long afterFuse = cargo + carried - 4;
			ClientWait.until(context, "the hud showing the price of two bricks taken", client -> hud(client).endsWith("$" + afterFuse), client -> "hud '" + hud(client) + "'");
			PodLining.State fused = singleplayer.getServer().computeOnServer(server -> PodLining.of(parkedPod(server)));
			require(fused.bricks() == 2 && fused.spoil() == 0, "four spoil make two bricks in the rack, got " + fused);

			// #80: the work order is listed with no progress, and handing in ten Bronzium finishes it and restores the Founder's hands.
			require(context.computeOnClient(client -> screen.orderLines()).equals(List.of("WORK ORDERS", "RESTORE THE FOUNDER'S HANDS", "0 / 10 BRONZIUM")),
					"the screen lists the work order, got " + context.computeOnClient(client -> screen.orderLines()));
			singleplayer.getServer().runOnServer(server -> {
				for (int i = 0; i < 10; i++) {
					server.getPlayerList().getPlayers().getFirst().getInventory().add(OreRegistry.stack(OreType.BRONZIUM));
				}
			});
			context.clickScreenButton("DELIVER BRONZIUM");
			ClientWait.until(context, "the last work order DONE", client -> screen.orderLines().getLast().endsWith("DONE"), client -> "order lines " + screen.orderLines());
			require(singleplayer.getServer().computeOnServer(WorkOrdersTest::handsRestored), "the Founder's hands are restored");
			singleplayer.getServer().runOnServer(server -> FounderStatue.handPositions(server).orElseThrow()
					.forEach(pos -> server.overworld().setBlock(pos, Blocks.AIR.defaultBlockState(), 3)));
			context.setScreen(() -> null);

			// #188: a charter that reached layer 3 is offered the repeatable Morale Initiative beside the finished Founder's hands.
			singleplayer.getServer().runOnServer(server -> Charters.recordDeepestPoint(server,
					Charters.charterOfOrThrow(server, server.getPlayerList().getPlayers().getFirst().getUUID()).orElseThrow().id(),
					LayerChain.topDepth(server.registryAccess(), 3)));
			context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(processor)));
			ClientWait.screen(context, OreProcessorScreen.class);
			OreProcessorScreen two = context.computeOnClient(client -> (OreProcessorScreen) client.gui.screen());
			ClientWait.until(context, "the second processor screen finished typing", client -> two.typewriter().done(), client -> "typewriter text '" + two.typewriter().text() + "'");
			List<String> expectedLines = List.of("WORK ORDERS", "RESTORE THE FOUNDER'S HANDS", "DONE", "MORALE INITIATIVE", "0 / 10 SILVERIUM");
			require(context.computeOnClient(client -> two.orderLines()).equals(expectedLines),
					"two orders are listed, got " + context.computeOnClient(client -> two.orderLines()));
			require(context.computeOnClient(client -> two.orderRows().stream().map(row -> row.active).toList()).equals(List.of(false, true)),
					"the finished order's row is inactive and the open one's is not");
			checkLayout(context, two, "the two orders");
			singleplayer.getServer().runOnServer(server -> {
				for (int i = 0; i < 25; i++) {
					server.getPlayerList().getPlayers().getFirst().getInventory().add(OreRegistry.stack(OreType.SILVERIUM));
				}
			});
			for (int round = 1; round <= 2; round++) {
				String done = "0 / 10 SILVERIUM  DONE x" + round;
				context.clickScreenButton("DELIVER SILVERIUM");
				ClientWait.until(context, "the second screen's last order done", client -> two.orderLines().getLast().equals(done), client -> "order lines " + two.orderLines());
			}
			int left = singleplayer.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().getInventory()
					.countItem(OreRegistry.item(OreType.SILVERIUM)));
			require(left == 5, "two rounds take ten each from 25 Silverium and leave 5, left " + left);
			context.setScreen(() -> null);

			// Several more orders than rows: the screen shows what fits, bounded, and pages to the rest. 320 by 240 is the smallest GUI.
			WorkOrdersView many = new WorkOrdersView(true, List.of(entry(0), entry(1), entry(2), entry(3), entry(4)));
			TerminalView manyView = new TerminalView(processor, TerminalTypes.ORE_PROCESSOR.id(), true, true, List.of(), Optional.of(many));
			for (int[] size : new int[][] {{427, 240}, {320, 240}}) {
				context.setScreen(() -> new OreProcessorScreen(manyView));
				OreProcessorScreen crowded = context.computeOnClient(client -> (OreProcessorScreen) client.gui.screen());
				context.runOnClient(client -> crowded.resize(size[0], size[1]));
				ClientWait.until(context, "the crowded screen finished typing", client -> crowded.typewriter().done(), client -> "typewriter text '" + crowded.typewriter().text() + "'");
				int rows = context.computeOnClient(client -> crowded.orderRows().size());
				require(rows >= 1 && rows < 5, "the rows are bounded by the room: " + rows + " of 5 at " + size[0] + " by " + size[1]);
				int pages = (5 + rows - 1) / rows;
				require(context.computeOnClient(client -> crowded.orderLines().getFirst()).equals("WORK ORDERS  1/" + pages),
						"the heading shows the page, got " + context.computeOnClient(client -> crowded.orderLines().getFirst()));
				checkLayout(context, crowded, "page 1 at " + size[0] + " by " + size[1]);
				for (int page = 2; page <= pages; page++) {
					context.clickScreenButton(">");
					String heading = "WORK ORDERS  " + page + "/" + pages;
					ClientWait.until(context, "the crowded screen's first line at the heading", client -> crowded.orderLines().getFirst().equals(heading), client -> "order lines " + crowded.orderLines());
					checkLayout(context, crowded, "page " + page + " at " + size[0] + " by " + size[1]);
				}
				require(context.computeOnClient(client -> crowded.orderRows().size()) == 5 - (pages - 1) * rows, "the last page holds the rest");
				require(!context.computeOnClient(client -> ClientChecks.button(crowded, ">").active), "no page after the last");
				context.clickScreenButton("<");
				ClientWait.until(context, "the crowded screen back on the previous page", client -> crowded.orderLines().getFirst().equals("WORK ORDERS  " + (pages - 1) + "/" + pages), client -> "order lines " + crowded.orderLines());
			}
			context.setScreen(() -> null);
		}
	}

	/** An entry of the crowded list: the Morale Initiative with {@code n} rounds done, so each row reads differently. */
	private static WorkOrdersView.Entry entry(int rounds) {
		return new WorkOrdersView.Entry(WorkOrder.MORALE_INITIATIVE, rounds, rounds);
	}

	/** Every button lies inside the screen, none overlaps another, and the order rows start under the account line. */
	private static void checkLayout(ClientGameTestContext context, OreProcessorScreen screen, String when) {
		Optional<String> problem = context.computeOnClient(client -> {
			List<Button> buttons = ClientChecks.buttons(screen);
			for (Button button : buttons) {
				Optional<String> buttonProblem = ClientChecks.buttonLeavesScreen(screen, button)
						.or(() -> ClientChecks.buttonOverlaps(button, buttons));
				if (buttonProblem.isPresent()) {
					return buttonProblem;
				}
			}
			for (OrderRowButton row : screen.orderRows()) {
				if (row.getY() < screen.accountBottom()) {
					return Optional.of(row.title() + " starts above the account line");
				}
			}
			return Optional.<String>empty();
		});
		ClientChecks.requireNone(when, problem);
	}

	/** The player founds a charter, carries two ores, and has a repaired processor and a pod with two ores in its bay beside them. */
	private static BlockPos setUp(MinecraftServer server) {
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		require(Charters.found(server, player.getUUID(), CHARTER).isEmpty(), "founding should succeed");
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

	/** The pod {@link #setUp} parked beside the processor: the colony's wreck is another pod in the same level, so the first one found is not it. */
	private static PodEntity parkedPod(MinecraftServer server) {
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		return server.overworld().getEntitiesOfClass(PodEntity.class, player.getBoundingBox().inflate(8)).getFirst();
	}

	private static String hud(ClientGameTestContext context) {
		return context.computeOnClient(OreProcessorClientTest::hud);
	}

	private static String hud(Minecraft client) {
		return AccountHud.text().map(text -> text.getString()).orElse("");
	}
}
