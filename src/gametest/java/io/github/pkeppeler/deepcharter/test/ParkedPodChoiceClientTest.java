package io.github.pkeppeler.deepcharter.test;

import java.util.Optional;
import java.util.UUID;

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
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.fuel.FuelPumpScreen;
import io.github.pkeppeler.deepcharter.client.repair.RepairStationScreen;
import io.github.pkeppeler.deepcharter.client.terminal.ShownPod;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/**
 * A pod terminal's screen shows the pod that the server will serve: the player's own pod, even when an unowned pod is parked
 * nearer. The fuel pump and the repair station each have the unowned pod nearer than the own one.
 */
public class ParkedPodChoiceClientTest implements FabricClientGameTest {
	/** What the scene gives the test. */
	private record Scene(BlockPos pump, BlockPos station, UUID own, UUID unowned) {
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			Scene scene = singleplayer.getServer().computeOnServer(ParkedPodChoiceClientTest::setUp);

			context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.pump())));
			ClientWait.screen(context, FuelPumpScreen.class);
			FuelPumpScreen pump = context.computeOnClient(client -> (FuelPumpScreen) client.gui.screen());
			ClientWait.until(context, "both pods tracked at the pump", client -> shown(client, pump.view()).isPresent(), client -> "shown " + shown(client, pump.view()));
			Optional<UUID> onPump = context.computeOnClient(client -> shown(client, pump.view()));
			require(onPump.equals(Optional.of(scene.own())), "the pump screen should show the own pod " + scene.own()
					+ " and not the nearer unowned pod " + scene.unowned() + ", shows " + onPump);
			context.setScreen(() -> null);

			context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.station())));
			ClientWait.screen(context, RepairStationScreen.class);
			RepairStationScreen station = context.computeOnClient(client -> (RepairStationScreen) client.gui.screen());
			ClientWait.until(context, "the pod tracked at the station", client -> shown(client, station.view()).isPresent(), client -> "shown " + shown(client, station.view()));
			Optional<UUID> onStation = context.computeOnClient(client -> shown(client, station.view()));
			require(onStation.equals(Optional.of(scene.own())), "the station screen should show the own pod " + scene.own()
					+ " and not the nearer unowned pod " + scene.unowned() + ", shows " + onStation);
			context.setScreen(() -> null);
		}
	}

	private static Optional<UUID> shown(Minecraft client, TerminalView view) {
		return ShownPod.of(client.level, view).map(PodEntity::getUUID);
	}

	/** A repaired pump east of the player and a repaired station north; the own pod three blocks west, an unowned one a block east. */
	private static Scene setUp(MinecraftServer server) {
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		require(Charters.found(server, player.getUUID(), "Pod Choice Charter").isEmpty(), "founding the charter should succeed");
		CharterId charter = Charters.charterOfOrThrow(server, player.getUUID()).orElseThrow().id();
		RepairState repairs = RepairState.get(server);
		for (TerminalType type : new TerminalType[] {TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR, TerminalTypes.UPGRADE_TERMINAL, TerminalTypes.REPAIR_STATION}) {
			type.parts().forEach(part -> repairs.insert(type, part));
		}
		BlockPos pump = player.blockPosition().relative(Direction.EAST, 2);
		BlockPos station = player.blockPosition().relative(Direction.NORTH, 2);
		server.overworld().setBlock(pump, TerminalTypes.FUEL_PUMP.block().defaultBlockState(), 3);
		server.overworld().setBlock(station, TerminalTypes.REPAIR_STATION.block().defaultBlockState(), 3);
		PodEntity own = spawn(player, player.position().relative(Direction.WEST, 3));
		PodComponents.register(own, charter);
		PodEntity unowned = spawn(player, player.position().relative(Direction.EAST, 1));
		return new Scene(pump, station, own.getUUID(), unowned.getUUID());
	}

	private static PodEntity spawn(ServerPlayer player, Vec3 at) {
		PodEntity pod = PodRegistry.POD.create(player.level(), EntitySpawnReason.COMMAND);
		pod.setPos(at);
		player.level().addFreshEntity(pod);
		return pod;
	}
}
