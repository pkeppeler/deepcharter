package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.market.OreProcessorScreen;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;

/**
 * Evidence scenario "m2-work-orders-repeat" for #188: the Founder's hands are done, the charter has reached the top of layer 3, and a
 * pod of the charter parked at the colony's ore processor holds 10 Silverium. The processor lists two orders (the finished one greyed
 * out, the Morale Initiative active). One click on "DELIVER SILVERIUM" takes the ore from the pod's hold, pays the round, and the row
 * reads "0 / 10 SILVERIUM  DONE x1", ready for the next round. The pager is left out: two orders never need it.
 */
public class WorkOrdersRepeatScenario extends EvidenceScenario {
	private static final int TICKS_PER_FRAME = 3;
	private static final int TYPING_FRAMES = 45;
	private static final int HOLD_FRAMES = 8;
	private static final int SETTLE_TICKS = 60;
	private static final int WAIT_TICKS = 200;
	private static final int ORDER_LINES = 5;
	private static final int HOLD_SILVERIUM = 7;
	private static final int PODS = 2;
	/** Where the pod parks, 6 blocks west and 4 north of the colony's centre: inside the processor's parking radius (8). */
	private static final Vec3 PARK_OFFSET = new Vec3(-6, 1, -4);

	@Override
	protected String name() {
		return "m2-work-orders-repeat";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitFor(client -> client.player != null && client.level != null);
			ColonySite.Placed colony = singleplayer.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.setPermanentlyInvulnerable(true);
				// The Handbook in the hand would cover the view.
				player.getInventory().clearContent();
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
				if (Charters.found(server, player.getUUID(), "Riggs and Sons").isPresent()) {
					throw new AssertionError("founding should succeed");
				}
				RepairState state = RepairState.get(server);
				for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR)) {
					type.parts().forEach(part -> state.insert(type, part));
				}
				return Colony.placed(server).orElseThrow(() -> new AssertionError("the colony was not built when the world started"));
			});
			BlockPos processor = colony.anchors().get(ColonyAnchor.ORE_PROCESSOR);
			Vec3 terminalViewpoint = Vec3.atBottomCenterOf(colony.center()).add(-4, 1, -4);
			stand(singleplayer, terminalViewpoint, Vec3.atCenterOf(processor));
			context.waitTicks(SETTLE_TICKS);

			// The Founder's hands first, so the Morale Initiative is the only order left to do.
			OreProcessorScreen screen = open(context, processor);
			give(singleplayer, OreType.BRONZIUM, 10);
			context.clickScreenButton("DELIVER BRONZIUM");
			context.waitFor(client -> screen.orderLines().getLast().endsWith("DONE"), WAIT_TICKS);
			context.setScreen(() -> null);

			// The charter reaches the top of layer 3, and a pod of its own parks at the processor with 10 Silverium in its holds.
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				CharterId charter = Charters.charterOfOrThrow(server, player.getUUID()).orElseThrow().id();
				Charters.recordDeepestPoint(server, charter, LayerChain.topDepth(server.registryAccess(), 3));
				park(server, colony, charter, HOLD_SILVERIUM);
				park(server, colony, charter, 10 - HOLD_SILVERIUM);
				if (Terminals.parkedPods(server.overworld(), processor, Optional.of(Charters.charterOfOrThrow(server, player.getUUID()).orElseThrow())).size() != PODS) {
					throw new AssertionError("both pods should be parked at the processor");
				}
			});

			OreProcessorScreen second = open(context, processor);
			context.waitFor(client -> second.orderLines().size() == ORDER_LINES, WAIT_TICKS);
			hold(context);
			screenshot(context, "two-orders-listed");

			context.clickScreenButton("DELIVER SILVERIUM");
			context.waitFor(client -> second.orderLines().getLast().equals("0 / 10 SILVERIUM  DONE x1"), WAIT_TICKS);
			singleplayer.getServer().runOnServer(server -> {
				if (Terminals.parkedPods(server.overworld(), processor).stream().anyMatch(pod -> pod.cargoUsed() != 0)) {
					throw new AssertionError("the delivery should have emptied the holds");
				}
			});
			hold(context);
			screenshot(context, "morale-initiative-delivered-from-the-hold");
			context.setScreen(() -> null);
		}
	}

	/** Opens the ore processor's screen and lets its intro type out. */
	private OreProcessorScreen open(ClientGameTestContext context, BlockPos processor) {
		context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(processor)));
		context.waitForScreen(OreProcessorScreen.class);
		OreProcessorScreen screen = context.computeOnClient(client -> (OreProcessorScreen) client.gui.screen());
		for (int i = 0; i < TYPING_FRAMES && !screen.typewriter().done(); i++) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
		return screen;
	}

	/** Spawns a pod of the charter beside the colony's ore processor holding {@code silverium} Silverium. */
	private static void park(MinecraftServer server, ColonySite.Placed colony, CharterId charter, int silverium) {
		PodEntity pod = PodRegistry.POD.create(server.overworld(), EntitySpawnReason.COMMAND);
		pod.setPos(Vec3.atBottomCenterOf(colony.center()).add(PARK_OFFSET).add(silverium == HOLD_SILVERIUM ? 0 : 1.5, 0, 0));
		server.overworld().addFreshEntity(pod);
		PodComponents.register(pod, charter);
		for (int i = 0; i < silverium; i++) {
			if (!pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.SILVERIUM))) {
				throw new AssertionError("the pod should have room for " + silverium + " Silverium");
			}
		}
	}

	private static void give(TestSingleplayerContext singleplayer, OreType ore, int count) {
		singleplayer.getServer().runOnServer(server -> {
			for (int i = 0; i < count; i++) {
				server.getPlayerList().getPlayers().getFirst().getInventory().add(OreRegistry.stack(ore));
			}
		});
	}

	private void hold(ClientGameTestContext context) {
		for (int i = 0; i < HOLD_FRAMES; i++) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
	}

	/** Puts the player on the ground at {@code at} in the overworld, looking at {@code target}. */
	private static void stand(TestSingleplayerContext singleplayer, Vec3 at, Vec3 target) {
		Vec3 eye = at.add(0, 1.62, 0);
		Vec3 d = target.subtract(eye);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
		singleplayer.getServer().runOnServer(server ->
				server.getPlayerList().getPlayers().getFirst().teleportTo(server.overworld(), at.x, at.y, at.z, Set.of(), yaw, pitch, true));
	}
}
