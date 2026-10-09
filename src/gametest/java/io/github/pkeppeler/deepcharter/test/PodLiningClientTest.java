package io.github.pkeppeler.deepcharter.test;

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;

import io.github.pkeppeler.deepcharter.client.pod.LiningKeys;
import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;

/**
 * Client GameTest for hand lining (#313): the pilot presses the lining key from the seat and the HUD shows the stock, then the
 * lining while it runs, then the out-of-brick line when the rack empties before the slab is done.
 */
public class PodLiningClientTest implements FabricClientGameTest {
	private static final int RACK = 3;

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				PodEntity pod = PodRegistry.POD.create(player.level(), EntitySpawnReason.COMMAND);
				pod.setPos(player.position());
				player.level().addFreshEntity(pod);
				if (!player.startRiding(pod)) {
					throw new AssertionError("the player could not mount the new pod");
				}
				PodLining.modify(pod, state -> new PodLining.State(0, RACK, 0, false, false));
			});
			ClientWait.until(context, "the stock line on the pod HUD", client -> client.player != null && client.player.getVehicle() instanceof PodEntity pod
					&& keys(PodStatusHud.lines(pod)).contains("hud.deepcharter.pod.slag"));

			context.getInput().pressKey(LiningKeys.LINE);
			ClientWait.until(context, "the lining line on the pod HUD", client -> client.player.getVehicle() instanceof PodEntity pod
					&& PodStatusHud.liningLine(pod).isPresent());
			ClientWait.until(context, "the out-of-brick line on the pod HUD", client -> client.player.getVehicle() instanceof PodEntity pod
					&& PodStatusHud.outOfBrickLine(pod).isPresent());

			boolean lineGone = context.computeOnClient(client -> PodStatusHud.liningLine((PodEntity) client.player.getVehicle()).isEmpty());
			List<String> lines = context.computeOnClient(client -> keys(PodStatusHud.lines((PodEntity) client.player.getVehicle())));
			if (!lineGone || lines.contains("hud.deepcharter.pod.slag")) {
				throw new AssertionError("Once the rack is empty the lining line ends and the stock line goes, but lining gone " + lineGone + ", lines " + lines);
			}
			long placed = singleplayer.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				PodEntity pod = (PodEntity) player.getVehicle();
				return pod.level().getBlockStates(pod.getBoundingBox().inflate(1.5)).filter(state -> state.is(SlagBrick.BLOCK)).count();
			});
			if (placed != RACK) {
				throw new AssertionError("The three bricks of the rack should stand round the pod, found " + placed);
			}
		}
	}

	private static List<String> keys(List<Component> lines) {
		return lines.stream().map(line -> line.getContents() instanceof TranslatableContents contents ? contents.getKey() : line.getString()).toList();
	}
}
