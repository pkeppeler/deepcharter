package io.github.pkeppeler.deepcharter.test;

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.ore.OreCargoScreen;
import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.ore.OreCargoMenu;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Client GameTest for #65: the parts of a pod reach the client, so the HUD shows the hull as points out of the pod's real
 * maximum, and the cargo screen has one slot for each slot of the bay.
 */
public class PodComponentsClientTest implements FabricClientGameTest {
	private static final float HULL_NOW = 150f;
	/** A tier 2 hull: 30 points of the original's 10 for the stock one, scaled to the mod's 100. */
	private static final int HULL_MAX = 300;
	/** A tier 1 bay. */
	private static final int BAY_SLOTS = 15;
	private static final OreType[] LOAD = {OreType.IRONIUM, OreType.BRONZIUM, OreType.SILVERIUM, OreType.GOLDIUM, OreType.PLATINIUM,
			OreType.EINSTEINIUM, OreType.CICATRIUM, OreType.IRONIUM, OreType.BRONZIUM, OreType.GOLDIUM};

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				if (Charters.found(server, player.getUUID(), "Client components").isPresent()) {
					throw new AssertionError("the player could not found a charter");
				}
				CharterId charter = Charters.charterOfOrThrow(server, player.getUUID()).orElseThrow().id();
				PodEntity pod = PodRegistry.POD.create(player.level(), EntitySpawnReason.COMMAND);
				pod.setPos(player.position());
				player.level().addFreshEntity(pod);
				PodComponents.register(pod, charter);
				PodComponents.install(pod, ComponentItems.mint(server, ComponentTrack.HULL, 2, charter));
				PodComponents.install(pod, ComponentItems.mint(server, ComponentTrack.CARGO_BAY, 1, charter));
				pod.setHull(HULL_NOW);
				for (OreType type : LOAD) {
					pod.cargo().tryAdd(pod, OreRegistry.stack(type));
				}
				if (!player.startRiding(pod)) {
					throw new AssertionError("the charter's founder could not mount the pod");
				}
			});
			context.waitFor(client -> client.player != null && client.player.getVehicle() instanceof PodEntity pod
					&& PodComponents.registration(pod).isPresent());

			int maxHull = context.computeOnClient(client -> Math.round(((PodEntity) client.player.getVehicle()).maxHull()));
			if (maxHull != HULL_MAX) {
				throw new AssertionError("The client should work out the pod's maximum hull " + HULL_MAX + " from the synced parts, got " + maxHull);
			}
			// The pod's entity data and its attachments reach the client in separate packets; wait for the hull.
			context.waitFor(client -> ((PodEntity) client.player.getVehicle()).hull() == HULL_NOW);
			List<Component> lines = context.computeOnClient(client -> PodStatusHud.lines((PodEntity) client.player.getVehicle()));
			if (!lines.getFirst().getString().equals("Hull 150/300")) {
				throw new AssertionError("The HUD should show the hull as 150/300, showed " + lines.getFirst().getString());
			}
			context.waitTicks(10);
			context.takeScreenshot("m2-pod-components-hud");

			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				OreCargoMenu.open(player, (PodEntity) player.getVehicle());
			});
			context.waitForScreen(OreCargoScreen.class);
			int slots = context.computeOnClient(client -> ((OreCargoMenu) client.player.containerMenu).cargoSlots());
			int menuSlots = context.computeOnClient(client -> client.player.containerMenu.slots.size());
			int shown = context.computeOnClient(client -> ((OreCargoMenu) client.player.containerMenu).shownOre().size());
			if (slots != BAY_SLOTS || menuSlots != BAY_SLOTS || shown != LOAD.length) {
				throw new AssertionError("The cargo screen should have " + BAY_SLOTS + " slots and show " + LOAD.length + " ore, has "
						+ slots + " slots (" + menuSlots + " in the menu) and shows " + shown);
			}
			context.waitTicks(10);
			context.takeScreenshot("m2-pod-components-cargo");
		}
	}
}
