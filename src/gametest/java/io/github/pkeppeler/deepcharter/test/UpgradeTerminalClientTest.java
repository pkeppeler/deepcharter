package io.github.pkeppeler.deepcharter.test;

import java.util.List;
import java.util.UUID;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;

import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.upgrade.UpgradeScreen;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.scanner.ScannerRegistry;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.UpgradeShown;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

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
			require(screen.upgrade().orElseThrow().pod().orElseThrow().cap() == 2, "the screen shows the Mole's cap of 2");
			ClientWait.until(context, "the upgrade screen finished typing", client -> screen.typewriter().done(), client -> "typewriter text '" + screen.typewriter().text() + "'");
			require(labels(context, screen).contains("DRILL  T0") && labels(context, screen).contains("FUEL TANK  T0"),
					"every track is listed with its installed tier, got " + labels(context, screen));

			context.clickScreenButton("HULL  T0");
			ClientWait.until(context, "the hull track lit", client -> UpgradeShown.isChosen(screen, ComponentTrack.HULL));
			List<String> hull = labels(context, screen);
			require(hull.contains("BUY TIER 1  $200") && hull.contains("BUY TIER 2  $500"), "the hull parts show their prices, got " + hull);
			require(hull.contains("BUY TIER 3  $1250  (WORKS AS TIER 2)") && hull.contains("BUY TIER 4  $5000  (WORKS AS TIER 2)"),
					"tiers above the cap say so, got " + hull);

			context.clickScreenButton("BUY TIER 1  $200");
			ClientWait.until(context, "the tier 1 install label", client -> labels(client, screen).contains("TIER 1  INSTALLED"));
			require(singleplayer.getServer().computeOnServer(server2 -> PodComponents.partOf(pod(server2, scene), ComponentTrack.HULL)
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
			require(cappedAndDropped, "the tier 4 hull works as tier 2, the tier 1 hull dropped, and the account paid 5200 in all");

			CrtButton unaffordable = context.computeOnClient(client -> screen.children().stream().filter(CrtButton.class::isInstance)
					.map(CrtButton.class::cast).filter(button -> button.getMessage().getString().startsWith("BUY TIER 6")).findFirst().orElseThrow());
			require(!unaffordable.active, "a part the account cannot pay for is dead");

			// #483: the scanner's tier 1 is crafted, so its row is not for sale and the next tier waits for it to be fitted.
			context.clickScreenButton("SCANNER  T0");
			ClientWait.until(context, "the scanner track selected", client -> UpgradeShown.isChosen(screen, ComponentTrack.SCANNER));
			List<String> bare = labels(context, screen);
			require(bare.contains("TIER 1  CRAFTED, NOT SOLD") && bare.contains("BUY TIER 2  $500  (FIT TIER 1 FIRST)"),
					"the scanner shows tier 1 as crafted and tier 2 as waiting for it, got " + bare);
			require(!button(context, screen, "TIER 1  CRAFTED, NOT SOLD").active && !button(context, screen, "BUY TIER 2  $500  (FIT TIER 1 FIRST)").active,
					"neither scanner row can be bought");

			singleplayer.getServer().runOnServer(server2 -> {
				ServerPlayer player = server2.getPlayerList().getPlayers().getFirst();
				player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ScannerRegistry.SCANNER_UNIT));
				require(UseEntityCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND, pod(server2, scene), null).consumesAction(),
						"using the crafted unit on the pod should fit it");
			});
			context.setScreen(() -> null);
			context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.terminal())));
			ClientWait.screen(context, UpgradeScreen.class);
			UpgradeScreen fitted = context.computeOnClient(client -> (UpgradeScreen) client.gui.screen());
			ClientWait.until(context, "the upgrade screen finished typing", client -> fitted.typewriter().done(), client -> "typewriter text '" + fitted.typewriter().text() + "'");
			context.clickScreenButton("SCANNER  T1");
			ClientWait.until(context, "the scanner track selected again", client -> UpgradeShown.isChosen(fitted, ComponentTrack.SCANNER));
			List<String> after = labels(context, fitted);
			require(after.contains("TIER 1  INSTALLED") && after.contains("BUY TIER 2  $500"), "a fitted scanner shows tier 1 installed and tier 2 for sale, got " + after);
			context.clickScreenButton("BUY TIER 2  $500");
			ClientWait.until(context, "the tier 2 install label", client -> labels(client, fitted).contains("TIER 2  INSTALLED"));
			require(singleplayer.getServer().computeOnServer(server2 -> PodComponents.effectiveTier(pod(server2, scene), ComponentTrack.SCANNER) == 2),
					"the server installed the tier 2 scanner over the crafted one");
			context.setScreen(() -> null);
		}
	}

	/** The player founds a charter with money, and has a repaired upgrade terminal and a pod of the charter beside them. */
	public static Scene setUp(MinecraftServer server) {
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		require(Charters.found(server, player.getUUID(), "Upgrade Test Charter").isEmpty(), "founding should succeed");
		CharterId charter = Charters.charterOfOrThrow(server, player.getUUID()).orElseThrow().id();
		require(Charters.deposit(server, charter, ACCOUNT).isEmpty(), "funding should succeed");
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

	private static CrtButton button(ClientGameTestContext context, UpgradeScreen screen, String label) {
		return context.computeOnClient(client -> screen.children().stream().filter(CrtButton.class::isInstance).map(CrtButton.class::cast)
				.filter(button -> button.getMessage().getString().replaceFirst("^> ", "").equals(label)).findFirst().orElseThrow());
	}

	private static List<String> labels(ClientGameTestContext context, UpgradeScreen screen) {
		return context.computeOnClient(client -> labels(client, screen));
	}

	private static List<String> labels(Minecraft client,UpgradeScreen screen) {
		return screen.children().stream().filter(CrtButton.class::isInstance).map(button -> ((CrtButton) button).getMessage().getString())
				.map(label -> label.startsWith("> ") ? label.substring(2) : label).toList();
	}
}
