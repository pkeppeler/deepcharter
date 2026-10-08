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
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
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

			// #188: a charter that reached layer 3 is offered the repeatable Morale Initiative beside the finished Founder's hands.
			singleplayer.getServer().runOnServer(server -> Charters.recordDeepestPoint(server,
					Charters.charterOfOrThrow(server, server.getPlayerList().getPlayers().getFirst().getUUID()).orElseThrow().id(),
					LayerChain.topDepth(server.registryAccess(), 3)));
			context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(processor)));
			context.waitForScreen(OreProcessorScreen.class);
			OreProcessorScreen two = context.computeOnClient(client -> (OreProcessorScreen) client.gui.screen());
			context.waitFor(client -> two.typewriter().done(), WAIT_TICKS);
			List<String> expectedLines = List.of("WORK ORDERS", "RESTORE THE FOUNDER'S HANDS", "DONE", "MORALE INITIATIVE", "0 / 10 SILVERIUM");
			check(context.computeOnClient(client -> two.orderLines()).equals(expectedLines),
					"two orders are listed, got " + context.computeOnClient(client -> two.orderLines()));
			check(context.computeOnClient(client -> two.orderRows().stream().map(row -> row.active).toList()).equals(List.of(false, true)),
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
				context.waitFor(client -> two.orderLines().getLast().equals(done), WAIT_TICKS);
			}
			int left = singleplayer.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().getInventory()
					.countItem(OreRegistry.item(OreType.SILVERIUM)));
			check(left == 5, "two rounds take ten each from 25 Silverium and leave 5, left " + left);
			context.setScreen(() -> null);

			// Several more orders than rows: the screen shows what fits, bounded, and pages to the rest. 320 by 240 is the smallest GUI.
			WorkOrdersView many = new WorkOrdersView(true, List.of(entry(0), entry(1), entry(2), entry(3), entry(4)));
			TerminalView manyView = new TerminalView(processor, TerminalTypes.ORE_PROCESSOR.id(), true, true, List.of(), Optional.of(many));
			for (int[] size : new int[][] {{427, 240}, {320, 240}}) {
				OreProcessorScreen crowded = new OreProcessorScreen(manyView);
				context.setScreen(() -> crowded);
				context.runOnClient(client -> crowded.resize(size[0], size[1]));
				context.waitFor(client -> crowded.typewriter().done(), WAIT_TICKS);
				int rows = context.computeOnClient(client -> crowded.orderRows().size());
				check(rows >= 1 && rows < 5, "the rows are bounded by the room: " + rows + " of 5 at " + size[0] + " by " + size[1]);
				int pages = (5 + rows - 1) / rows;
				check(context.computeOnClient(client -> crowded.orderLines().getFirst()).equals("WORK ORDERS  1/" + pages),
						"the heading shows the page, got " + context.computeOnClient(client -> crowded.orderLines().getFirst()));
				checkLayout(context, crowded, "page 1 at " + size[0] + " by " + size[1]);
				for (int page = 2; page <= pages; page++) {
					context.clickScreenButton(">");
					String heading = "WORK ORDERS  " + page + "/" + pages;
					context.waitFor(client -> crowded.orderLines().getFirst().equals(heading), WAIT_TICKS);
					checkLayout(context, crowded, "page " + page + " at " + size[0] + " by " + size[1]);
				}
				check(context.computeOnClient(client -> crowded.orderRows().size()) == 5 - (pages - 1) * rows, "the last page holds the rest");
				check(!context.computeOnClient(client -> button(crowded, ">").active), "no page after the last");
				context.clickScreenButton("<");
				context.waitFor(client -> crowded.orderLines().getFirst().equals("WORK ORDERS  " + (pages - 1) + "/" + pages), WAIT_TICKS);
			}
			context.setScreen(() -> null);
		}
	}

	/** An entry of the crowded list: the Morale Initiative with {@code n} rounds done, so each row reads differently. */
	private static WorkOrdersView.Entry entry(int rounds) {
		return new WorkOrdersView.Entry(WorkOrder.MORALE_INITIATIVE, rounds, rounds);
	}

	private static Button button(OreProcessorScreen screen, String label) {
		return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
				.filter(button -> button.getMessage().getString().equals(label)).findFirst()
				.orElseThrow(() -> new AssertionError("no button '" + label + "' on the screen"));
	}

	/** Every button lies inside the screen, none overlaps another, and the order rows start under the account line. */
	private static void checkLayout(ClientGameTestContext context, OreProcessorScreen screen, String when) {
		String problem = context.computeOnClient(client -> {
			List<Button> buttons = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
			for (Button button : buttons) {
				if (button.getX() < 0 || button.getY() < 0 || button.getRight() > screen.width || button.getBottom() > screen.height) {
					return button.getMessage().getString() + " leaves the " + screen.width + " by " + screen.height + " screen";
				}
				for (Button other : buttons) {
					if (button != other && button.getX() < other.getRight() && other.getX() < button.getRight()
							&& button.getY() < other.getBottom() && other.getY() < button.getBottom()) {
						return button.getMessage().getString() + " overlaps " + other.getMessage().getString();
					}
				}
			}
			for (OrderRowButton row : screen.orderRows()) {
				if (row.getY() < screen.accountBottom()) {
					return row.title() + " starts above the account line";
				}
			}
			return "";
		});
		check(problem.isEmpty(), when + ": " + problem);
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
