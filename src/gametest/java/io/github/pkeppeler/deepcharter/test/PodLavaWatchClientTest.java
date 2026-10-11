package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;

/**
 * Client GameTest for the lava watch HUD (#454): a pod with a pilot shows no lava line with no lava about, and a lava line (an ALERT, which lights the cockpit's warning
 * lamp) once lava flows within reach of it.
 */
public class PodLavaWatchClientTest implements FabricClientGameTest {
	private static final int LAVA_AT = 3;

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				ServerLevel level = player.level();
				BlockPos feet = player.blockPosition();
				// A stone bed under the pod and the lava, and clear air round and over them.
				for (int x = -3; x <= LAVA_AT + 2; x++) {
					for (int z = -3; z <= 3; z++) {
						level.setBlock(feet.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
						for (int y = 0; y <= 3; y++) {
							level.setBlock(feet.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
						}
					}
				}
				PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
				pod.setPos(Mth.floor(player.getX()), feet.getY(), Mth.floor(player.getZ()));
				level.addFreshEntity(pod);
				if (!player.startRiding(pod)) {
					throw new AssertionError("the player could not mount the new pod");
				}
			});
			ClientWait.until(context, "the pod status HUD", client -> client.player != null && client.player.getVehicle() instanceof PodEntity pod
					&& !PodStatusHud.lines(pod).isEmpty());
			context.waitTicks(30); // tick-wait: long enough for a few looks of the watch (every 10 ticks) to have found nothing
			if (context.computeOnClient(client -> PodStatusHud.lavaNearLine((PodEntity) client.player.getVehicle()).isPresent())) {
				throw new AssertionError("A pod with no lava about shows no lava line");
			}

			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				BlockPos feet = player.getVehicle().blockPosition();
				// The source flows over the stone bed at once; the flowing lava stays while the source does.
				player.level().setBlock(feet.offset(LAVA_AT, 0, 0), Blocks.LAVA.defaultBlockState(), 3);
			});
			ClientWait.until(context, "the lava line", client -> client.player.getVehicle() instanceof PodEntity pod && PodStatusHud.lavaNearLine(pod).isPresent());
			PodStatusHud.Line line = context.computeOnClient(client -> PodStatusHud.readout(client, (PodEntity) client.player.getVehicle()).stream()
					.filter(shown -> shown.text().getContents() instanceof TranslatableContents contents && contents.getKey().equals("hud.deepcharter.pod.lava_near"))
					.findFirst().orElseThrow(() -> new AssertionError("The readout should hold the lava line")));
			if (line.kind() != PodStatusHud.Kind.ALERT) {
				throw new AssertionError("The lava line is a warning, it is " + line.kind());
			}
			int blocksOff = (Integer) ((TranslatableContents) line.text().getContents()).getArgs()[0];
			if (blocksOff < 1 || blocksOff > LAVA_AT + 1) {
				throw new AssertionError("The first flowing lava is next to the source, within " + (LAVA_AT + 1) + " blocks of the pod, the line says " + blocksOff);
			}
		}
	}
}
