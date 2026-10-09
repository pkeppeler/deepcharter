package io.github.pkeppeler.deepcharter.test;

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Client GameTest for the seep sounder HUD (#373): a pod with no sounder shows no seepage line; a tier 1 sounder shows the slabs to a pocket in
 * its footprint; a tier 2 sounder also names the side a pocket lies on.
 */
public class PodSounderClientTest implements FabricClientGameTest {
	private static final int SLABS_DOWN = 2;

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				ServerLevel level = player.level();
				BlockPos feet = player.blockPosition();
				// A stone bed under the pod, a pocket under its footprint, and a pocket two blocks east at the level of its box.
				for (int x = -3; x <= 4; x++) {
					for (int z = -3; z <= 4; z++) {
						for (int y = 1; y <= 4; y++) {
							level.setBlock(feet.offset(x, -y, z), Blocks.STONE.defaultBlockState(), 3);
						}
					}
				}
				level.setBlock(feet.offset(0, -SLABS_DOWN, 0), HazardBlocks.GAS_POCKET.defaultBlockState(), 3);
				PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
				pod.setPos(Mth.floor(player.getX()), feet.getY(), Mth.floor(player.getZ()));
				level.addFreshEntity(pod);
				if (!player.startRiding(pod)) {
					throw new AssertionError("the player could not mount the new pod");
				}
			});
			ClientWait.until(context, "the pod status HUD", client -> client.player != null && client.player.getVehicle() instanceof PodEntity pod
					&& !PodStatusHud.lines(pod).isEmpty());
			context.waitTicks(10);
			if (!sounderLines(context).isEmpty()) {
				throw new AssertionError("A pod with no sounder shows no seepage line, the lines are " + keys(sounderLines(context)));
			}

			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				ScannerPods.fit(server, player, (PodEntity) player.getVehicle(), ComponentTrack.SOUNDER, 1);
			});
			ClientWait.until(context, "the seepage line of a tier 1 sounder", client -> client.player.getVehicle() instanceof PodEntity pod
					&& !PodStatusHud.sounderLines(pod).isEmpty());
			List<Component> tierOne = sounderLines(context);
			if (tierOne.size() != 1 || !key(tierOne.getFirst()).equals("hud.deepcharter.pod.seepage") || argument(tierOne.getFirst()) != SLABS_DOWN) {
				throw new AssertionError("A tier 1 sounder shows SEEPAGE " + SLABS_DOWN + ", the lines are " + keys(tierOne) + " " + tierOne);
			}

			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				PodEntity pod = (PodEntity) player.getVehicle();
				ScannerPods.fit(server, player, pod, ComponentTrack.SOUNDER, 2);
				BlockPos feet = pod.blockPosition();
				// East of the footprint (x - 1 and x): the block two out, at the level of the pod's feet.
				player.level().setBlock(new BlockPos(Mth.floor(pod.getX()) + 2, feet.getY(), feet.getZ()), HazardBlocks.GAS_POCKET.defaultBlockState(), 3);
			});
			ClientWait.until(context, "the tier 2 sounder naming the east side", client -> client.player.getVehicle() instanceof PodEntity pod
					&& PodStatusHud.sounderLines(pod).stream().anyMatch(line -> key(line).equals("hud.deepcharter.pod.seepage_beside")));
			List<Component> tierTwo = sounderLines(context);
			if (!keys(tierTwo).equals(List.of("hud.deepcharter.pod.seepage", "hud.deepcharter.pod.seepage_beside"))) {
				throw new AssertionError("A tier 2 sounder shows the slabs down and the side, the lines are " + keys(tierTwo));
			}
			if (!tierTwo.get(1).getString().contains("E")) {
				throw new AssertionError("The side line names the east, it reads: " + tierTwo.get(1).getString());
			}
		}
	}

	private static List<Component> sounderLines(ClientGameTestContext context) {
		return context.computeOnClient(client -> PodStatusHud.sounderLines((PodEntity) client.player.getVehicle()));
	}

	private static String key(Component line) {
		return line.getContents() instanceof TranslatableContents contents ? contents.getKey() : line.getString();
	}

	private static List<String> keys(List<Component> lines) {
		return lines.stream().map(PodSounderClientTest::key).toList();
	}

	private static int argument(Component line) {
		return (Integer) ((TranslatableContents) line.getContents()).getArgs()[0];
	}
}
