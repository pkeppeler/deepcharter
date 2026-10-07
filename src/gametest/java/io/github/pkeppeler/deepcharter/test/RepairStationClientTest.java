package io.github.pkeppeler.deepcharter.test;

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

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

/**
 * Client GameTest for #70: the repair station screen opens, and its repair and buy buttons send the actions the server
 * acts on (the server's answer is checked, not the screen's own state).
 */
public class RepairStationClientTest implements FabricClientGameTest {
	private static final int WAIT_TICKS = 200;
	private static final long START_BALANCE = 100_000;
	private static final float DAMAGE = 40f;

	/** What {@link #setUp} made: the station, the parked pod, and the charter. */
	public record Scene(BlockPos station, PodEntity pod, Charter charter) {
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			Scene scene = singleplayer.getServer().computeOnServer(RepairStationClientTest::setUp);

			context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.station())));
			context.waitForScreen(RepairStationScreen.class);

			context.clickScreenButton("REPAIR 10 HP ($150)");
			context.waitFor(client -> hull(singleplayer, scene) == scene.pod().maxHull() - DAMAGE + 10f, WAIT_TICKS);
			check(account(singleplayer, scene) == START_BALANCE - 150, "10 HP cost $150, the account is $" + account(singleplayer, scene));

			context.clickScreenButton("BUY DYNAMITE $2000");
			context.waitFor(client -> carried(singleplayer, Consumable.DYNAMITE) == 1, WAIT_TICKS);
			check(account(singleplayer, scene) == START_BALANCE - 150 - 2_000, "the dynamite cost $2000, the account is $" + account(singleplayer, scene));

			context.clickScreenButton("REPAIR ALL");
			context.waitFor(client -> hull(singleplayer, scene) == scene.pod().maxHull(), WAIT_TICKS);
			check(account(singleplayer, scene) == START_BALANCE - 150 - 2_000 - 30 * 15, "the rest of the hull cost $450, the account is $" + account(singleplayer, scene));
			context.setScreen(() -> null);
		}
	}

	/** The player founds a charter, a repaired station stands beside them, and the charter's damaged pod is parked at it. */
	public static Scene setUp(MinecraftServer server) {
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		if (Charters.found(server, player.getUUID(), "Emendation Test Charter").isPresent()) {
			throw new AssertionError("founding should succeed");
		}
		Charter charter = Charters.charterOf(server, player.getUUID()).orElseThrow();
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
		return singleplayer.getServer().computeOnServer(server -> Charters.find(server, scene.charter().id()).orElseThrow().account());
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
