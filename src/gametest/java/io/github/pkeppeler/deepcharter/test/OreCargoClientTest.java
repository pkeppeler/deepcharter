package io.github.pkeppeler.deepcharter.test;

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;

import io.github.pkeppeler.deepcharter.client.ore.OreCargoScreen;
import io.github.pkeppeler.deepcharter.ore.OreCargoMenu;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/** Client GameTest for #56: sneak-using a loaded pod opens a cargo screen that shows the ore and its mass. */
public class OreCargoClientTest implements FabricClientGameTest {
	/** Put a pod with Goldium and Einsteinium beside the first player, and sneak-use it as that player. Server thread. */
	public static void sneakUseLoadedPod(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		singleplayer.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			PodEntity pod = PodRegistry.POD.create(player.level(), EntitySpawnReason.COMMAND);
			pod.setPos(player.position().add(2, 0, 0));
			player.level().addFreshEntity(pod);
			pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM));
			pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.EINSTEINIUM));
			player.setShiftKeyDown(true);
			UseEntityCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND, pod, null);
			player.setShiftKeyDown(false);
		});
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			sneakUseLoadedPod(context, singleplayer);
			ClientWait.screen(context, OreCargoScreen.class);
			int expected = Math.round(OreType.GOLDIUM.mass() + OreType.EINSTEINIUM.mass());
			// The slot contents and the mass sync separately from the open-screen packet.
			ClientWait.until(context, "the cargo menu with 2 ore and mass " + expected,
					client -> client.player.containerMenu instanceof OreCargoMenu menu && menu.shownOre().size() == 2 && menu.cargoMass() == expected,
					client -> client.player.containerMenu instanceof OreCargoMenu menu
							? menu.shownOre().size() + " ore, mass " + menu.cargoMass() : "no cargo menu");

			List<ItemStack> shown = context.computeOnClient(client -> ((OreCargoMenu) client.player.containerMenu).shownOre());
			require(shown.size() == 2 && shown.get(0).is(OreRegistry.item(OreType.GOLDIUM)) && shown.get(1).is(OreRegistry.item(OreType.EINSTEINIUM)), "The cargo screen should show the Goldium and the Einsteinium, it shows " + shown);
			int mass = context.computeOnClient(client -> ((OreCargoMenu) client.player.containerMenu).cargoMass());
			require(mass == expected, "The cargo screen should show mass " + expected + ", it shows " + mass);

			context.runOnClient(client -> client.player.closeContainer());
			ClientWait.until(context, "the inventory menu back", client -> client.player.containerMenu == client.player.inventoryMenu);
		}
	}
}
