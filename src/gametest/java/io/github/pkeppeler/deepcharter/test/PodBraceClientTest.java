package io.github.pkeppeler.deepcharter.test;

import java.util.Arrays;
import java.util.List;
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
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Client GameTest for the crust warning on the pod HUD (#378): a pod 7 slabs above the crust with a hull the crust would end shows the warning with
 * the slabs, the hull and the price. A breach brace with no ore says so; with ore it burns one ore into 12 hull, the hull outlasts the crust and the lines go.
 */
public class PodBraceClientTest implements FabricClientGameTest {
	private static final int FEET_Y = 10;
	private static final float HULL = 20f;
	private static final int ORE = 3;

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(40);
			singleplayer.getServer().runOnServer(server -> {
				ServerLevel one = server.getLevel(LayerChain.dimension(1));
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.setGameMode(GameType.SURVIVAL);
				// Hung in an open room: no gravity holds the pod where it is, so the reading stays still.
				RoomCarver.carve(one, -3, 3, FEET_Y - 1, FEET_Y + 6, -3, 3, Blocks.AIR);
				player.teleportTo(one, 0, FEET_Y, 0, Set.of(), 0f, 0f, true);
				PodEntity pod = PodRegistry.POD.create(one, EntitySpawnReason.COMMAND);
				pod.setPos(0, FEET_Y, 0);
				pod.setNoGravity(true);
				pod.setHull(HULL);
				one.addFreshEntity(pod);
				if (!player.startRiding(pod)) {
					throw new AssertionError("the player could not mount the pod");
				}
			});
			ClientWait.until(context, "the pod in layer 1", client -> client.player != null && client.player.getVehicle() instanceof PodEntity pod
					&& client.level.dimension().equals(LayerChain.dimension(1)) && Math.round(pod.hull()) == Math.round(HULL));
			List<Component> lines = crustLines(context);
			if (lines.size() != 1) {
				throw new AssertionError("A hull of " + HULL + " 7 slabs above the crust shows the crust warning and no brace line: " + lines);
			}
			TranslatableContents contents = (TranslatableContents) lines.getFirst().getContents();
			if (!contents.getKey().equals("hud.deepcharter.pod.crust_ahead") || !Arrays.equals(contents.getArgs(), new Object[] {7, 20, 24})) {
				throw new AssertionError("The warning reads CRUST 7 DOWN, hull 20, price 24: " + contents.getKey() + " " + Arrays.toString(contents.getArgs()));
			}

			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				ScannerPods.fit(server, player, (PodEntity) player.getVehicle(), ComponentTrack.BRACE, 1);
			});
			ClientWait.until(context, "the brace with no ore to burn", client -> client.player.getVehicle() instanceof PodEntity pod
					&& PodStatusHud.crustLines(pod, true).stream().map(PodBraceClientTest::key).toList().equals(List.of("hud.deepcharter.pod.crust_ahead", "hud.deepcharter.pod.brace_dry")));

			singleplayer.getServer().runOnServer(server -> {
				PodEntity pod = (PodEntity) server.getPlayerList().getPlayers().getFirst().getVehicle();
				for (int i = 0; i < ORE; i++) {
					pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM));
				}
			});
			// One ore of 12 hull takes 20 to 32, over the crust's 24 and the reserve of 4: the brace burns one and stops.
			ClientWait.until(context, "the brace burning one ore", client -> client.player.getVehicle() instanceof PodEntity pod
					&& Math.round(pod.hull()) == 32 && pod.cargoUsed() == ORE - 1);
			ClientWait.until(context, "no warning and no brace line once the hull outlasts the crust", client -> client.player.getVehicle() instanceof PodEntity pod
					&& PodStatusHud.crustLines(pod, true).isEmpty());

			singleplayer.getServer().runOnServer(server -> ((PodEntity) server.getPlayerList().getPlayers().getFirst().getVehicle()).setHull(100f));
			ClientWait.until(context, "no warning for a full hull", client -> client.player.getVehicle() instanceof PodEntity pod && PodStatusHud.crustLines(pod, true).isEmpty());
		}
	}

	private static String key(Component line) {
		return line.getContents() instanceof TranslatableContents contents ? contents.getKey() : line.getString();
	}

	private static List<Component> crustLines(ClientGameTestContext context) {
		return context.computeOnClient(client -> PodStatusHud.crustLines((PodEntity) client.player.getVehicle(), true));
	}
}
