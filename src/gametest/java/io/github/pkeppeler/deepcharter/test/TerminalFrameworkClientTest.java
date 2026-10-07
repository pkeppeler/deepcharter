package io.github.pkeppeler.deepcharter.test;

import java.util.List;
import java.util.Locale;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalScreen;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalScreens;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalViewScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.ui.CrtScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtTextField;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalActionPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.test.support.TerminalTestTypes;

/**
 * Client GameTest for #59: the offline screen opens and renders, a part button puts a part in through the server, the screen
 * flips to online when the last part is in, a locked terminal's buttons are dead, and the server refuses an open request from
 * too far.
 */
public class TerminalFrameworkClientTest implements FabricClientGameTest {
	private static final int FAR_BLOCKS = 20;
	private static final int WAIT_TICKS = 200;
	private static final int REFUSAL_TICKS = 20;

	/** The three terminals the test places, as absolute positions. */
	private record Scene(BlockPos pump, BlockPos processor, BlockPos farPump, BlockPos open) {
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			Scene scene = singleplayer.getServer().computeOnServer(TerminalFrameworkClientTest::setUp);

			farRequestIsRefused(context, scene);
			offlineScreenOpensAndRenders(context, scene);
			lockedTerminalHasDeadButtons(context, scene);
			partButtonsRepairThroughTheServer(context, singleplayer, scene);
			onlineScreenOpensOnceRepaired(context, scene);
			customScreenUpdatesInPlace(context, scene);
		}
	}

	/** The player founds a charter, carries every part, and has a pump and a processor beside them and a second pump far away. */
	private static Scene setUp(MinecraftServer server) {
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		if (Charters.found(server, player.getUUID(), "Terminal Test Charter").isPresent()) {
			throw new AssertionError("founding should succeed");
		}
		BlockPos here = player.blockPosition();
		Scene scene = new Scene(here.relative(Direction.EAST, 2), here.relative(Direction.SOUTH, 2), here.relative(Direction.EAST, FAR_BLOCKS),
				here.relative(Direction.WEST, 2));
		server.overworld().setBlock(scene.pump(), TerminalTypes.FUEL_PUMP.block().defaultBlockState(), 3);
		server.overworld().setBlock(scene.processor(), TerminalTypes.ORE_PROCESSOR.block().defaultBlockState(), 3);
		server.overworld().setBlock(scene.open(), TerminalTestTypes.OPEN.block().defaultBlockState(), 3);
		server.overworld().setBlock(scene.farPump(), TerminalTypes.FUEL_PUMP.block().defaultBlockState(), 3);
		TerminalTypes.all().forEach(type -> type.parts().forEach(part -> player.getInventory().add(new ItemStack(part))));
		return scene;
	}

	private static void request(ClientGameTestContext context, BlockPos pos) {
		context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(pos)));
	}

	private static TerminalScreen awaitScreen(ClientGameTestContext context) {
		context.waitForScreen(TerminalScreen.class);
		return context.computeOnClient(client -> (TerminalScreen) client.gui.screen());
	}

	private static void farRequestIsRefused(ClientGameTestContext context, Scene scene) {
		request(context, scene.farPump());
		context.waitTicks(REFUSAL_TICKS);
		check(context.computeOnClient(client -> client.gui.screen() == null), "an open request from " + FAR_BLOCKS + " blocks away must not open a screen");
	}

	private static void offlineScreenOpensAndRenders(ClientGameTestContext context, Scene scene) {
		request(context, scene.pump());
		TerminalScreen screen = awaitScreen(context);
		check(!screen.online(), "an unrepaired terminal opens offline");
		check(screen.view().unlocked(), "the pump has no prerequisite, so its parts can go in");
		context.waitFor(client -> screen.typewriter().done(), WAIT_TICKS);
		check(screen.typewriter().text().contains("OFFLINE"), "the offline text says so, got '" + screen.typewriter().text() + "'");
		check(buttons(context, screen).stream().filter(button -> button.active).count() == TerminalTypes.FUEL_PUMP.parts().size() + 1,
				"every part button and CLOSE are live");
		context.waitTicks(5);
		context.setScreen(() -> null);
	}

	private static void lockedTerminalHasDeadButtons(ClientGameTestContext context, Scene scene) {
		request(context, scene.processor());
		TerminalScreen screen = awaitScreen(context);
		check(!screen.online() && !screen.view().unlocked(), "the processor is offline and locked while the pump is not repaired");
		context.waitFor(client -> screen.typewriter().done(), WAIT_TICKS);
		check(screen.typewriter().text().contains("FIRST"), "the locked text names what to repair first, got '" + screen.typewriter().text() + "'");
		check(buttons(context, screen).stream().filter(button -> button.active).count() == 1, "only CLOSE is live on a locked terminal");
		context.setScreen(() -> null);
	}

	private static void partButtonsRepairThroughTheServer(ClientGameTestContext context, TestSingleplayerContext singleplayer, Scene scene) {
		request(context, scene.pump());
		TerminalScreen screen = awaitScreen(context);
		String firstLabel = "INSERT " + partLabel(TerminalTypes.FUEL_PUMP, 0);
		context.clickScreenButton(firstLabel);
		context.waitFor(client -> screen.view().parts().getFirst().inserted(), WAIT_TICKS);
		check(!screen.online(), "one part of two does not repair the pump");
		check(buttons(context, screen).stream().noneMatch(button -> button.getMessage().getString().equals(firstLabel)),
				"the inserted part's button now reads inserted");

		context.clickScreenButton("INSERT " + partLabel(TerminalTypes.FUEL_PUMP, 1));
		context.waitFor(client -> client.gui.screen() instanceof TerminalScreen open && open.online(), WAIT_TICKS);
		boolean repaired = singleplayer.getServer().computeOnServer(server -> RepairState.get(server).repaired(TerminalTypes.FUEL_PUMP));
		check(repaired, "the server has the pump repaired");
		int carried = singleplayer.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().getInventory()
				.countItem(TerminalTypes.FUEL_PUMP.parts().getFirst()));
		check(carried == 0, "the part left the inventory, " + carried + " remain");
	}

	private static void onlineScreenOpensOnceRepaired(ClientGameTestContext context, Scene scene) {
		context.setScreen(() -> null);
		request(context, scene.farPump());
		context.waitTicks(REFUSAL_TICKS);
		check(context.computeOnClient(client -> client.gui.screen() == null), "the repair does not widen the range");

		request(context, scene.pump());
		TerminalScreen screen = awaitScreen(context);
		check(screen.online(), "a repaired terminal opens online");
		context.waitFor(client -> screen.typewriter().done(), WAIT_TICKS);
		check(screen.typewriter().text().contains("ONLINE"), "the online text says so, got '" + screen.typewriter().text() + "'");
		context.waitTicks(5);
		context.setScreen(() -> null);

		request(context, scene.processor());
		TerminalScreen processor = awaitScreen(context);
		check(!processor.online() && processor.view().unlocked(), "with the pump repaired the processor's parts can go in");
		context.setScreen(() -> null);
	}

	/** A feature's own online screen keeps its text field when the server answers an action: it is updated, not replaced. */
	private static void customScreenUpdatesInPlace(ClientGameTestContext context, Scene scene) {
		TerminalScreens.register(TerminalTestTypes.OPEN, InPlaceScreen::new);
		request(context, scene.open());
		context.waitForScreen(InPlaceScreen.class);
		InPlaceScreen screen = context.computeOnClient(client -> (InPlaceScreen) client.gui.screen());
		check(screen.view().repaired() && screen.view().parts().isEmpty(), "a terminal with no repair opens online, with no parts");
		context.runOnClient(client -> screen.field().setValue("RIGGS"));
		context.runOnClient(client -> ClientPlayNetworking.send(new TerminalActionPayload(scene.open(), TerminalTestTypes.PING, new CompoundTag())));
		context.waitFor(client -> screen.updates() == 1, WAIT_TICKS);
		check(context.computeOnClient(client -> client.gui.screen() == screen), "the action's answer must not replace the screen");
		check(screen.field().getValue().equals("RIGGS"), "the text field keeps its text, got '" + screen.field().getValue() + "'");
		context.setScreen(() -> null);
	}

	/** A screen with a text field, standing in for a feature's own online screen. */
	private static final class InPlaceScreen extends CrtScreen implements TerminalViewScreen {
		private TerminalView view;
		private CrtTextField field;
		private int updates;

		private InPlaceScreen(TerminalView view) {
			super(Component.literal("IN PLACE"));
			this.view = view;
		}

		@Override
		protected void layout() {
			String typed = field == null ? "" : field.getValue();
			field = addRenderableWidget(new CrtTextField(font, 30, 30, width - 60, 14, Component.literal("NAME")));
			field.setValue(typed);
		}

		@Override
		public boolean accepts(TerminalView other) {
			return view.pos().equals(other.pos());
		}

		@Override
		public void update(TerminalView newer) {
			view = newer;
			updates++;
			rebuildWidgets();
		}

		TerminalView view() {
			return view;
		}

		CrtTextField field() {
			return field;
		}

		int updates() {
			return updates;
		}
	}

	private static String partLabel(TerminalType type, int index) {
		return new ItemStack(type.parts().get(index)).getHoverName().getString().toUpperCase(Locale.ROOT);
	}

	private static List<CrtButton> buttons(ClientGameTestContext context, TerminalScreen screen) {
		return context.computeOnClient(client -> screen.children().stream()
				.filter(CrtButton.class::isInstance).map(CrtButton.class::cast).toList());
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
