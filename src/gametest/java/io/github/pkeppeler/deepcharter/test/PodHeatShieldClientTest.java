package io.github.pkeppeler.deepcharter.test;

import java.util.Optional;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Client GameTest for the heat shield's HUD (#482). A pod with the part and a rack of bricks sits in lava: the HUD says the glaze holds and how many bricks are left,
 * and not that the hull burns. When the bricks are spent it says the glaze is spent and the hull burns. A pod with no shield shows only the hull burning.
 */
public class PodHeatShieldClientTest implements FabricClientGameTest {
	private static final int FEET_Y = 10;
	private static final int RACK = 6;

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(40);
			singleplayer.getServer().runOnServer(server -> {
				ServerLevel one = server.getLevel(LayerChain.dimension(1));
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.setGameMode(GameType.SURVIVAL);
				// Hung in a sealed room, in a 2 x 2 pool of lava: no gravity holds the pod where it is, so the reading stays still.
				RoomCarver.carve(one, -3, 3, FEET_Y - 1, FEET_Y + 6, -3, 3, Blocks.AIR);
				RoomCarver.carve(one, -1, 0, FEET_Y, FEET_Y, -1, 0, Blocks.LAVA);
				player.teleportTo(one, 0, FEET_Y, 0, Set.of(), 0f, 0f, true);
				PodEntity pod = PodRegistry.POD.create(one, EntitySpawnReason.COMMAND);
				pod.setPos(0, FEET_Y, 0);
				pod.setNoGravity(true);
				one.addFreshEntity(pod);
				ScannerPods.fit(server, player, pod, ComponentTrack.SCANNER, 1);
				if (!player.startRiding(pod)) {
					throw new AssertionError("the player could not mount the pod");
				}
			});
			ClientWait.until(context, "the pod in layer 1, burning", client -> client.player != null && client.player.getVehicle() instanceof PodEntity pod
					&& client.level.dimension().equals(LayerChain.dimension(1)) && pod.hullBurning());
			ClientWait.until(context, "a pod with no shield shows the hull burning and no glaze line", client -> client.player.getVehicle() instanceof PodEntity pod
					&& key(PodStatusHud.burningLine(pod)).equals("hud.deepcharter.pod.burning") && PodStatusHud.glazeLine(pod).isEmpty());

			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				PodEntity pod = (PodEntity) player.getVehicle();
				pod.setHull(pod.maxHull());
				ScannerPods.fit(server, player, pod, ComponentTrack.HEAT_SHIELD, 1);
				PodLining.modify(pod, state -> new PodLining.State(0, RACK, 0, false, false));
			});
			ClientWait.until(context, "the glaze holding with bricks left, and the hull not burning", client -> client.player.getVehicle() instanceof PodEntity pod
					&& pod.glazeBricks() > 0 && key(PodStatusHud.glazeLine(pod)).equals("hud.deepcharter.pod.glaze_holding") && PodStatusHud.burningLine(pod).isEmpty());
			int shown = context.computeOnClient(client -> ((TranslatableContents) PodStatusHud.glazeLine((PodEntity) client.player.getVehicle()).orElseThrow().getContents()).getArgs().length);
			if (shown != 1) {
				throw new AssertionError("the glaze line reads the bricks left, one argument, found " + shown);
			}
			float hull = context.computeOnClient(client -> ((PodEntity) client.player.getVehicle()).hull());
			if (hull < 99f) {
				throw new AssertionError("a glaze with bricks left keeps the hull, it is " + hull);
			}

			ClientWait.until(context, "the bricks spent: the glaze says so and the hull burns", client -> client.player.getVehicle() instanceof PodEntity pod
					&& pod.glazeBricks() == 0 && key(PodStatusHud.glazeLine(pod)).equals("hud.deepcharter.pod.glaze_spent")
					&& key(PodStatusHud.burningLine(pod)).equals("hud.deepcharter.pod.burning"));

			singleplayer.getServer().runOnServer(server -> RoomCarver.carve(server.getLevel(LayerChain.dimension(1)), -1, 0, FEET_Y, FEET_Y, -1, 0, Blocks.AIR));
			ClientWait.until(context, "no glaze line out of lava", client -> client.player.getVehicle() instanceof PodEntity pod && PodStatusHud.glazeLine(pod).isEmpty());
		}
	}

	private static String key(Optional<Component> line) {
		return line.map(found -> found.getContents() instanceof TranslatableContents contents ? contents.getKey() : found.getString()).orElse("");
	}
}
