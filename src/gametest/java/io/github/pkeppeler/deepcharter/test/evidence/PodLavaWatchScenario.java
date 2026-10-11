package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;

/**
 * Evidence scenario "pod-lava-watch" (#454): a pilot sits in a pod that faces down a stone tunnel. Far down the tunnel a shaft goes up, with
 * lava at the top of it, well past the 3 blocks the liner sees. The lava falls down the shaft and runs along the tunnel toward the pod. The plain HUD shows
 * "LAVA IN THE SHAFT 12 OFF  LINE OR LEAVE" as the first lava comes within 12 blocks, and a compact cockpit (the ribbon) shows the same line and lights its warning lamp.
 * The hull is untouched at both stills.
 */
public class PodLavaWatchScenario extends EvidenceScenario {
	private static final int X = 5600;
	private static final int Z = 5600;
	private static final int FLOOR_Y = 100;
	private static final int TUNNEL = 14;
	private static final int SHAFT_TOP = 10;
	private static final int TICKS_PER_FRAME = 4;
	private static final int MAX_TICKS = 2400;
	private static final float FACING_EAST = -90f;

	@Override
	protected String name() {
		return "pod-lava-watch";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(40);
			singleplayer.getServer().runOnServer(server -> {
				ServerLevel one = server.getLevel(LayerChain.dimension(1));
				build(one);
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.setGameMode(GameType.SURVIVAL);
				player.teleportTo(one, X, FLOOR_Y, Z, Set.of(), FACING_EAST, 0f, true);
				PodEntity pod = PodRegistry.POD.create(one, EntitySpawnReason.COMMAND);
				pod.setPos(X, FLOOR_Y, Z);
				pod.setYRot(FACING_EAST);
				one.addFreshEntity(pod);
				if (!player.startRiding(pod)) {
					throw new AssertionError("the player could not mount the pod");
				}
			});
			ClientWait.until(context, "the pilot seated in the pod in layer 1", client -> client.player != null && client.player.getVehicle() instanceof PodEntity
					&& client.level.dimension().equals(LayerChain.dimension(1)));
			context.runOnClient(client -> {
				client.options.setCameraType(CameraType.FIRST_PERSON);
				client.player.setXRot(0f);
			});
			context.waitTicks(60);
			for (int i = 0; i < 4; i++) {
				frame(context);
				context.waitTicks(TICKS_PER_FRAME);
			}
			screenshot(context, "pod-lava-watch-clear");

			singleplayer.getServer().runOnServer(server ->
					server.getLevel(LayerChain.dimension(1)).setBlock(new BlockPos(X + TUNNEL, FLOOR_Y + SHAFT_TOP, Z), Blocks.LAVA.defaultBlockState(), 3));
			waitForWarning(context, "the plain HUD's lava line");
			for (int i = 0; i < 6; i++) {
				frame(context);
				context.waitTicks(TICKS_PER_FRAME);
			}
			screenshot(context, "pod-lava-watch-plain");

			ClientPacks.enable(context, TestPacks.COCKPIT_RIBBON);
			ClientWait.until(context, "the ribbon cockpit", client -> CockpitHud.seated(client).isPresent());
			boolean lamp = context.computeOnClient(client -> CockpitHud.lampsLit(false, false, false,
					PodStatusHud.readout(client, (PodEntity) client.player.getVehicle()))[3]);
			if (!lamp) {
				throw new AssertionError("The cockpit's warning lamp should be lit by the lava line");
			}
			for (int i = 0; i < 6; i++) {
				frame(context);
				context.waitTicks(TICKS_PER_FRAME);
			}
			screenshot(context, "pod-lava-watch-ribbon");
			ClientPacks.disable(context, TestPacks.COCKPIT_RIBBON);

			float hull = singleplayer.getServer().computeOnServer(server -> ((PodEntity) server.getPlayerList().getPlayers().getFirst().getVehicle()).hull());
			float maxHull = singleplayer.getServer().computeOnServer(server -> ((PodEntity) server.getPlayerList().getPlayers().getFirst().getVehicle()).maxHull());
			if (hull < maxHull) {
				throw new AssertionError("The warning should come before any hull is lost, the hull is " + hull + " of " + maxHull);
			}
		}
	}

	private void waitForWarning(ClientGameTestContext context, String what) {
		int ticks = 0;
		while (!context.computeOnClient(client -> client.player.getVehicle() instanceof PodEntity pod && PodStatusHud.lavaNearLine(pod).isPresent())) {
			if (ticks >= MAX_TICKS) {
				throw new AssertionError("Waited " + MAX_TICKS + " ticks for " + what);
			}
			context.waitTicks(TICKS_PER_FRAME);
			ticks += TICKS_PER_FRAME;
			frame(context);
		}
	}

	/** A stone block with a tunnel of air east from the pod, and at its far end a shaft up, lit by lamps in the rock; the lava is placed at the top by the scenario. */
	private static void build(ServerLevel level) {
		RoomCarver.carve(level, X - 4, X + TUNNEL + 4, FLOOR_Y - 4, FLOOR_Y + SHAFT_TOP + 3, Z - 4, Z + 4, Blocks.STONE);
		RoomCarver.carve(level, X - 2, X + TUNNEL, FLOOR_Y, FLOOR_Y + 2, Z - 1, Z, Blocks.AIR);
		RoomCarver.carve(level, X + TUNNEL - 1, X + TUNNEL, FLOOR_Y, FLOOR_Y + SHAFT_TOP, Z - 1, Z, Blocks.AIR);
		for (int x = X + 3; x < X + TUNNEL - 1; x += 3) {
			level.setBlock(new BlockPos(x, FLOOR_Y + 3, Z), Blocks.GLOWSTONE.defaultBlockState(), 3);
		}
		level.setBlock(new BlockPos(X + TUNNEL - 2, FLOOR_Y + 5, Z), Blocks.GLOWSTONE.defaultBlockState(), 3);
	}
}
