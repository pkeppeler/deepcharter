package io.github.pkeppeler.deepcharter.test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.repair.RepairStationScreen;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;

/**
 * Client GameTest for #70: the repair station screen opens, and its repair and buy buttons send the actions the server
 * acts on (the server's answer is checked, not the screen's own state).
 */
public class RepairStationClientTest implements FabricClientGameTest {
	private static final long START_BALANCE = 100_000;
	private static final float DAMAGE = 40f;

	/** What {@link #setUp} made: the station, the parked pod, and the charter. */
	public record Scene(BlockPos station, PodEntity pod, Charter charter) {
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			Scene scene = singleplayer.getServer().computeOnServer(RepairStationClientTest::setUp);

			context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.station())));
			ClientWait.screen(context, RepairStationScreen.class);

			clickRow(context, "REPAIR 10 HP ($10)");
			awaitServer(context, "the pod repaired by 10 HP", () -> hull(singleplayer, scene) == scene.pod().maxHull() - DAMAGE + 10f,
					() -> "hull " + hull(singleplayer, scene) + ", account $" + account(singleplayer, scene));
			check(account(singleplayer, scene) == START_BALANCE - 10, "10 HP cost $10, the account is $" + account(singleplayer, scene));

			clickRow(context, "BUY DYNAMITE $100");
			awaitServer(context, "one dynamite carried", () -> carried(singleplayer, Consumable.DYNAMITE) == 1,
					() -> carried(singleplayer, Consumable.DYNAMITE) + " dynamite, account $" + account(singleplayer, scene));
			check(account(singleplayer, scene) == START_BALANCE - 10 - 100, "the dynamite cost $100, the account is $" + account(singleplayer, scene));

			clickRow(context, "REPAIR ALL");
			awaitServer(context, "the pod fully repaired", () -> hull(singleplayer, scene) == scene.pod().maxHull(),
					() -> "hull " + hull(singleplayer, scene) + " of " + scene.pod().maxHull() + ", account $" + account(singleplayer, scene));
			check(account(singleplayer, scene) == START_BALANCE - 10 - 100 - 30 * 1, "the rest of the hull cost $30, the account is $" + account(singleplayer, scene));
			context.setScreen(() -> null);
		}
	}

	/**
	 * Scrolls the open repair station until the button labelled {@code label} is shown, then clicks it: a small screen shows only
	 * some rows. Fails when no row has the label, or when two do (the click would take the first).
	 */
	public static void clickRow(ClientGameTestContext context, String label) {
		RepairStationScreen screen = context.computeOnClient(client -> (RepairStationScreen) client.gui.screen());
		int matches = context.computeOnClient(client -> {
			Set<Integer> rows = new HashSet<>();
			int shownAt = 0;
			for (int first = 0; first < screen.rowCount(); first++) {
				screen.scrollTo(first);
				List<Button> buttons = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
				for (int i = 0; i < buttons.size(); i++) {
					if (buttons.get(i).getMessage().getString().equals(label) && rows.add(screen.firstRow() + i)) {
						shownAt = screen.firstRow();
					}
				}
			}
			// Leave the list where the row is shown, so that the click finds it.
			screen.scrollTo(shownAt);
			return rows.size();
		});
		check(matches == 1, matches + " buttons in the repair station are labelled '" + label + "', not 1");
		context.clickScreenButton(label);
	}

	/** Waits for server state that {@code condition} reads through {@code computeOnServer}, which {@code waitFor} may not call (it runs on the client thread). */
	public static void awaitServer(ClientGameTestContext context, String what, BooleanSupplier condition, Supplier<String> seen) {
		ClientWait.until(context, what, condition, seen);
	}

	/** The player founds a charter, a repaired station stands beside them, and the charter's damaged pod is parked at it. */
	public static Scene setUp(MinecraftServer server) {
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		if (Charters.found(server, player.getUUID(), "Emendation Test Charter").isPresent()) {
			throw new AssertionError("founding should succeed");
		}
		Charter charter = Charters.charterOfOrThrow(server, player.getUUID()).orElseThrow();
		RepairState state = RepairState.get(server);
		for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR, TerminalTypes.UPGRADE_TERMINAL, TerminalTypes.REPAIR_STATION)) {
			type.parts().forEach(part -> state.insert(type, part).ifPresent(refusal -> {
				throw new AssertionError("repairing " + type.id() + ": " + refusal);
			}));
		}
		BlockPos here = player.blockPosition();
		BlockPos station = here.east(2);
		server.overworld().setBlock(station, TerminalTypes.REPAIR_STATION.block().defaultBlockState(), 3);
		PodEntity pod = PodRegistry.POD.create(server.overworld(), EntitySpawnReason.COMMAND);
		pod.setPos(here.getX() + 0.5, here.getY(), here.getZ() + 4.5);
		server.overworld().addFreshEntity(pod);
		PodComponents.register(pod, charter.id());
		pod.setHull(pod.maxHull() - DAMAGE);
		if (Charters.deposit(server, charter.id(), START_BALANCE).isPresent()) {
			throw new AssertionError("funding should succeed");
		}
		return new Scene(station, pod, charter);
	}

	private static float hull(TestSingleplayerContext singleplayer, Scene scene) {
		return singleplayer.getServer().computeOnServer(server -> scene.pod().hull());
	}

	private static long account(TestSingleplayerContext singleplayer, Scene scene) {
		return singleplayer.getServer().computeOnServer(server -> Charters.findOrThrow(server, scene.charter().id()).orElseThrow().account());
	}

	private static int carried(TestSingleplayerContext singleplayer, Consumable consumable) {
		return singleplayer.getServer().computeOnServer(server ->
				server.getPlayerList().getPlayers().getFirst().getInventory().countItem(RepairRegistry.item(consumable)));
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
