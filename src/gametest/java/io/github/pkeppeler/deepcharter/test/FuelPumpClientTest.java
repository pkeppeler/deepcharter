package io.github.pkeppeler.deepcharter.test;

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.fuel.FuelPumpScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.fuel.FuelPump;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalActionPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;

/**
 * Client GameTest for #69: the pump screen shows the account and the parked pod's tank, its buttons are live only when a press
 * could work, a press buys through the server, and an account that cannot pay changes nothing.
 */
public class FuelPumpClientTest implements FabricClientGameTest {
	/** Dollars the charter starts with. */
	public static final long START_BALANCE = 20;
	/** The pod starts with 2 of its 10 litres. */
	public static final float START_FUEL_PERCENT = 20f;
	private static final int WAIT_TICKS = 200;
	private static final float EPSILON = 0.01f;
	/** Marks the pod that burns no fuel while the test waits, so the litres can be compared exactly. */
	private static final String FROZEN = "fuel-pump-test-frozen";

	static {
		PodEvents.IS_POWERED.register(pod -> !pod.entityTags().contains(FROZEN));
	}

	/** What the scene gives the test. */
	public record Scene(BlockPos pump, CharterId charter) {
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			Scene scene = singleplayer.getServer().computeOnServer(FuelPumpClientTest::setUp);
			context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.pump())));
			context.waitForScreen(FuelPumpScreen.class);
			FuelPumpScreen screen = context.computeOnClient(client -> (FuelPumpScreen) client.gui.screen());
			context.waitFor(client -> screen.typewriter().done(), WAIT_TICKS);
			check(screen.typewriter().text().contains("$1 A LITRE"), "the screen names the price, got '" + screen.typewriter().text() + "'");
			context.waitFor(client -> ClientCharter.view().isPresent() && ClientCharter.view().get().balance() == START_BALANCE
					&& !FuelPump.parkedPods(client.level, scene.pump()).isEmpty(), WAIT_TICKS);

			// 2 of 10 litres: there is room for 5 but not for 10.
			check(live(context, screen, "BUY 1 L") && live(context, screen, "BUY 5 L") && !live(context, screen, "BUY 10 L") && live(context, screen, "FILL UP"),
					"with 8 litres of room and $20, the 1 and 5 litre buttons and FILL UP are live and the 10 litre button is not");

			context.clickScreenButton("BUY 5 L");
			context.waitFor(client -> ClientCharter.view().get().balance() == START_BALANCE - 5, WAIT_TICKS);
			context.waitFor(client -> Math.abs(litres(client, scene) - 7f) < EPSILON, WAIT_TICKS);
			check(context.computeOnClient(client -> client.gui.screen() == screen), "the server's answer must not replace the screen");

			context.clickScreenButton("FILL UP");
			context.waitFor(client -> ClientCharter.view().get().balance() == START_BALANCE - 5 - 3, WAIT_TICKS);
			context.waitFor(client -> Math.abs(litres(client, scene) - 10f) < EPSILON, WAIT_TICKS);
			context.waitTicks(2);
			check(!live(context, screen, "BUY 1 L") && !live(context, screen, "FILL UP"), "with a full tank no buy button is live");

			// An empty account: nothing is live, and a press sent anyway is refused and changes nothing.
			singleplayer.getServer().runOnServer(server -> {
				Charters.spend(server, scene.charter(), Charters.find(server, scene.charter()).orElseThrow().account());
				pod(server).setFuel(0f);
			});
			context.waitFor(client -> ClientCharter.view().get().balance() == 0 && litres(client, scene) < EPSILON, WAIT_TICKS);
			context.waitTicks(2);
			check(!live(context, screen, "BUY 1 L") && !live(context, screen, "FILL UP"), "with an empty account no buy button is live");
			CompoundTag args = new CompoundTag();
			args.putInt(FuelPump.LITRES_KEY, 1);
			context.runOnClient(client -> ClientPlayNetworking.send(new TerminalActionPayload(scene.pump(), FuelPump.BUY, args)));
			context.waitTicks(20);
			float afterRefusal = singleplayer.getServer().computeOnServer(server -> pod(server).fuel());
			check(afterRefusal == 0f, "a purchase with an empty account must not fuel the pod, fuel is " + afterRefusal);
			context.setScreen(() -> null);
		}
	}

	/**
	 * The player founds a charter with {@link #START_BALANCE} dollars, with a repaired pump two blocks east and its own pod three
	 * blocks west, which has {@link #START_FUEL_PERCENT} of its tank. Public for the evidence scenario.
	 */
	public static Scene setUp(MinecraftServer server) {
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		if (Charters.found(server, player.getUUID(), "Pump Test Charter").isPresent()) {
			throw new AssertionError("founding the charter should succeed");
		}
		CharterId charter = Charters.charterOf(server, player.getUUID()).orElseThrow().id();
		if (Charters.deposit(server, charter, START_BALANCE).isPresent()) {
			throw new AssertionError("the deposit should succeed");
		}
		RepairState repairs = RepairState.get(server);
		TerminalTypes.FUEL_PUMP.parts().forEach(part -> repairs.insert(TerminalTypes.FUEL_PUMP, part));
		BlockPos pump = player.blockPosition().relative(Direction.EAST, 2);
		server.overworld().setBlock(pump, TerminalTypes.FUEL_PUMP.block().defaultBlockState(), 3);
		PodEntity pod = PodRegistry.POD.create(player.level(), EntitySpawnReason.COMMAND);
		pod.setPos(player.position().relative(Direction.WEST, 3));
		player.level().addFreshEntity(pod);
		PodComponents.register(pod, charter);
		pod.setFuel(START_FUEL_PERCENT);
		pod.addTag(FROZEN);
		return new Scene(pump, charter);
	}

	private static PodEntity pod(MinecraftServer server) {
		return server.overworld().getEntities(PodRegistry.POD, entity -> true).getFirst();
	}

	private static float litres(Minecraft client, Scene scene) {
		List<PodEntity> pods = FuelPump.parkedPods(client.level, scene.pump());
		return pods.isEmpty() ? -1f : FuelPump.litres(pods.getFirst());
	}

	private static boolean live(ClientGameTestContext context, FuelPumpScreen screen, String label) {
		return context.computeOnClient(client -> screen.children().stream()
				.filter(CrtButton.class::isInstance).map(CrtButton.class::cast)
				.filter(button -> button.getMessage().getString().equals(label))
				.findFirst().orElseThrow(() -> new AssertionError("no button " + label)).active);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
