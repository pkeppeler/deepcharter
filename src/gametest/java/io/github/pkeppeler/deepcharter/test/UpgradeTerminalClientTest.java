package io.github.pkeppeler.deepcharter.test;

import java.util.List;
import java.util.UUID;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.upgrade.UpgradeScreen;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Client GameTest for #73: the upgrade screen lists the parts of a track with their prices, marks the tiers above the Mole's cap
 * and shows the cap, buys through the server, shows the installed part (and the cap on it), and leaves a part the charter cannot
 * pay for dead.
 */
public class UpgradeTerminalClientTest implements FabricClientGameTest {
	private static final long ACCOUNT = 30_000;

	/** The terminal, and the id of the pod parked beside it. */
	public record Scene(BlockPos terminal, UUID pod) {
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			Scene scene = singleplayer.getServer().computeOnServer(UpgradeTerminalClientTest::setUp);
			ClientWait.until(context, "the account at its starting balance", client -> ClientCharter.view().map(charter -> charter.balance() == ACCOUNT).orElse(false), client -> "charter " + ClientCharter.view());

			context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.terminal())));
			ClientWait.screen(context, UpgradeScreen.class);
			UpgradeScreen screen = context.computeOnClient(client -> (UpgradeScreen) client.gui.screen());
			check(screen.upgrade().orElseThrow().pod().orElseThrow().cap() == 2, "the screen shows the Mole's cap of 2");
			ClientWait.until(context, "the upgrade screen finished typing", client -> screen.typewriter().done(), client -> "typewriter text '" + screen.typewriter().text() + "'");
			check(labels(context, screen).contains("DRILL  T0") && labels(context, screen).contains("FUEL TANK  T0"),
					"every track is listed with its installed tier, got " + labels(context, screen));

			context.clickScreenButton("HULL  T0");
			ClientWait.until(context, "the hull track selected", client -> screen.selected() == ComponentTrack.HULL);
			List<String> hull = labels(context, screen);
			check(hull.contains("BUY TIER 1  $200") && hull.contains("BUY TIER 2  $500"), "the hull parts show their prices, got " + hull);
			check(hull.contains("BUY TIER 3  $1250  (WORKS AS TIER 2)") && hull.contains("BUY TIER 4  $5000  (WORKS AS TIER 2)"),
					"tiers above the cap say so, got " + hull);

			context.clickScreenButton("BUY TIER 1  $200");
			ClientWait.until(context, "the tier 1 install label", client -> labels(client, screen).contains("TIER 1  INSTALLED"));
			check(singleplayer.getServer().computeOnServer(server2 -> PodComponents.partOf(pod(server2, scene), ComponentTrack.HULL)
					.map(label -> label.tier() == 1).orElse(false)), "the server installed the tier 1 hull");

			context.clickScreenButton("BUY TIER 4  $5000  (WORKS AS TIER 2)");
			ClientWait.until(context, "the tier 4 install label", client -> labels(client, screen).contains("TIER 4  INSTALLED  (WORKS AS TIER 2)"));
			boolean cappedAndDropped = singleplayer.getServer().computeOnServer(server2 -> {
				PodEntity pod = pod(server2, scene);
				boolean capped = PodComponents.effectiveTier(pod, ComponentTrack.HULL) == 2;
				boolean dropped = pod.level().getEntitiesOfClass(ItemEntity.class, new AABB(scene.terminal()).inflate(20)).stream()
						.anyMatch(item -> ComponentItems.labelOf(item.getItem()).map(label -> label.tier() == 1).orElse(false));
				long account = Charters.charterOfOrThrow(server2, server2.getPlayerList().getPlayers().getFirst().getUUID()).orElseThrow().account();
				return capped && dropped && account == ACCOUNT - 200 - 5_000;
			});
			check(cappedAndDropped, "the tier 4 hull works as tier 2, the tier 1 hull dropped, and the account paid 5200 in all");

			CrtButton unaffordable = context.computeOnClient(client -> screen.children().stream().filter(CrtButton.class::isInstance)
					.map(CrtButton.class::cast).filter(button -> button.getMessage().getString().startsWith("BUY TIER 6")).findFirst().orElseThrow());
			check(!unaffordable.active, "a part the account cannot pay for is dead");
			context.setScreen(() -> null);
		}
	}

	/** The player founds a charter with money, and has a repaired upgrade terminal and a pod of the charter beside them. */
	public static Scene setUp(MinecraftServer server) {
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		if (Charters.found(server, player.getUUID(), "Upgrade Test Charter").isPresent()) {
			throw new AssertionError("founding should succeed");
		}
		CharterId charter = Charters.charterOfOrThrow(server, player.getUUID()).orElseThrow().id();
		if (Charters.deposit(server, charter, ACCOUNT).isPresent()) {
			throw new AssertionError("funding should succeed");
		}
		RepairState state = RepairState.get(server);
		for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR, TerminalTypes.UPGRADE_TERMINAL)) {
			type.parts().forEach(part -> state.insert(type, part).ifPresent(refusal -> {
				throw new AssertionError("repairing " + type.id() + ": " + refusal);
			}));
		}
		BlockPos here = player.blockPosition();
		BlockPos terminal = here.relative(Direction.EAST, 2);
		server.overworld().setBlock(terminal, TerminalTypes.UPGRADE_TERMINAL.block().defaultBlockState(), 3);
		PodEntity pod = PodRegistry.POD.create(server.overworld(), EntitySpawnReason.COMMAND);
		BlockPos parked = here.relative(Direction.WEST, 3);
		pod.setPos(parked.getX() + 0.5, parked.getY(), parked.getZ() + 0.5);
		server.overworld().addFreshEntity(pod);
		PodComponents.register(pod, charter);
		return new Scene(terminal, pod.getUUID());
	}

	private static PodEntity pod(MinecraftServer server, Scene scene) {
		return (PodEntity) server.overworld().getEntity(scene.pod());
	}

	private static List<String> labels(ClientGameTestContext context, UpgradeScreen screen) {
		return context.computeOnClient(client -> labels(client, screen));
	}

	private static List<String> labels(Minecraft client,UpgradeScreen screen) {
		return screen.children().stream().filter(CrtButton.class::isInstance).map(button -> ((CrtButton) button).getMessage().getString())
				.map(label -> label.startsWith("> ") ? label.substring(2) : label).toList();
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
