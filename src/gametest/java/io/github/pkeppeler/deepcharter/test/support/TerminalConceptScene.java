package io.github.pkeppeler.deepcharter.test.support;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractTerminal;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/**
 * The world the terminal concept round (#246) draws its screens in: a charter with money, one repaired terminal of each kind round the
 * player, and a damaged pod parked among them with ore in its cargo. Used by the {@code terminal-concepts} evidence scenario and by the
 * client test that holds every screen inside the screen.
 */
public final class TerminalConceptScene {
	/** The account the charter starts with: enough to afford every row, and a number with more than one digit. */
	public static final long ACCOUNT = 12_500;

	/** Where each terminal stands. */
	public record Scene(BlockPos hangar, BlockPos processor, BlockPos upgrade, BlockPos repair) {
	}

	private TerminalConceptScene() {
	}

	/** Founds the charter, repairs the terminals and places them, and parks a pod. Runs on the server thread. */
	public static Scene setUp(MinecraftServer server) {
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		require(Charters.found(server, player.getUUID(), "Riggs and Sons").isEmpty(), "founding should succeed");
		Charter charter = Charters.charterOfOrThrow(server, player.getUUID()).orElseThrow();
		require(Charters.deposit(server, charter.id(), ACCOUNT).isEmpty(), "funding should succeed");
		RepairState state = RepairState.get(server);
		for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR, TerminalTypes.UPGRADE_TERMINAL, TerminalTypes.REPAIR_STATION,
				HangarTerminal.TYPE)) {
			type.parts().forEach(part -> state.insert(type, part).ifPresent(refusal -> {
				throw new AssertionError("repairing " + type.id() + ": " + refusal);
			}));
		}
		BlockPos here = player.blockPosition();
		BlockPos hangar = here.relative(Direction.EAST, 2);
		BlockPos processor = here.relative(Direction.WEST, 2);
		BlockPos upgrade = here.relative(Direction.NORTH, 2);
		BlockPos repair = here.relative(Direction.SOUTH, 2);
		server.overworld().setBlock(hangar, HangarTerminal.TYPE.block().defaultBlockState(), 3);
		server.overworld().setBlock(processor, TerminalTypes.ORE_PROCESSOR.block().defaultBlockState(), 3);
		server.overworld().setBlock(upgrade, TerminalTypes.UPGRADE_TERMINAL.block().defaultBlockState(), 3);
		server.overworld().setBlock(repair, TerminalTypes.REPAIR_STATION.block().defaultBlockState(), 3);
		PodEntity pod = PodRegistry.POD.create(server.overworld(), EntitySpawnReason.COMMAND);
		pod.setPos(here.getX() + 0.5, here.getY(), here.getZ() + 5.5);
		server.overworld().addFreshEntity(pod);
		PodComponents.register(pod, charter.id());
		pod.setHull(pod.maxHull() - 35);
		pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM));
		pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.PLATINIUM));
		player.getInventory().add(OreRegistry.stack(OreType.IRONIUM));
		player.getInventory().add(OreRegistry.stack(OreType.SILVERIUM));
		return new Scene(hangar, processor, upgrade, repair);
	}

	/** In a world where the player has no charter: two charters of other people to apply to, and a contract terminal. Returns the terminal. */
	public static BlockPos setUpContract(MinecraftServer server) {
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		for (String name : List.of("Riggs and Sons", "Deep Pocket Mining")) {
			require(Charters.found(server, UUID.randomUUID(), name).isEmpty(), "founding " + name + " should succeed");
		}
		BlockPos terminal = player.blockPosition().relative(Direction.EAST, 2);
		server.overworld().setBlock(terminal, ContractTerminal.TYPE.block().defaultBlockState(), 3);
		return terminal;
	}
}
