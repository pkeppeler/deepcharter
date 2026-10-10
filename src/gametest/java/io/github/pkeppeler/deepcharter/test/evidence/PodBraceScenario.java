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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
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
 * Evidence scenario "pod-brace" (#378): a pilot with 8 hull and four ore in the bay drills down a shaft of layer 1 towards the breach crust. The crust takes 8
 * hull a row and has 3 rows, 24 in all, so the HUD warns from 16 slabs up: the slabs down, the hull, and what the crust takes. The pod stops on the crust and a
 * breach brace is fitted (as if bought at the upgrade terminal). It burns two ore into hull, 8 to 32, the warning goes, and the pod bores the crust into layer 2
 * with 8 hull left.
 */
public class PodBraceScenario extends EvidenceScenario {
	private static final int X = 4900;
	private static final int Z = 5000;
	private static final int CRUST_TOP_Y = 3;
	private static final int FLOOR_Y = 15;
	private static final int RADIUS = 6;
	private static final int TICKS_PER_FRAME = 4;
	private static final int MAX_TICKS = 6000;
	private static final float LOOK_DOWN = 30f;
	private static final float FACING_EAST = -90f;
	private static final float HULL = 8f;
	private static final float CRUST = 24f;
	private static final int ORE = 4;

	@Override
	protected String name() {
		return "pod-brace";
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
				player.teleportTo(one, X, FLOOR_Y, Z, Set.of(), FACING_EAST, LOOK_DOWN, true);
				PodEntity pod = PodRegistry.POD.create(one, EntitySpawnReason.COMMAND);
				pod.setPos(X, FLOOR_Y, Z);
				pod.setYRot(FACING_EAST);
				one.addFreshEntity(pod);
				ScannerPods.fit(server, player, pod, ComponentTrack.FUEL_TANK, 2);
				pod.setFuel(100f);
				pod.setHull(HULL);
				for (int i = 0; i < ORE; i++) {
					pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM));
				}
				if (!player.startRiding(pod)) {
					throw new AssertionError("the player could not mount the pod");
				}
			});
			ClientWait.until(context, "the pilot seated in the pod in layer 1", client -> client.player != null && client.player.getVehicle() instanceof PodEntity
					&& client.level.dimension().equals(LayerChain.dimension(1)));
			context.runOnClient(client -> {
				client.options.setCameraType(CameraType.FIRST_PERSON);
				client.player.setXRot(LOOK_DOWN);
			});
			context.waitTicks(60);
			for (int i = 0; i < 4; i++) {
				frame(context);
				context.waitTicks(TICKS_PER_FRAME);
			}
			screenshot(context, "pod-brace-open-room");

			// Hold the drill down to the top of the crust, with the warning on the way.
			context.getInput().holdKey(options -> options.keySprint);
			boolean warned = false;
			int ticks = 0;
			while (feetY(singleplayer) > CRUST_TOP_Y + 0.01 && ticks < MAX_TICKS) {
				context.waitTicks(TICKS_PER_FRAME);
				ticks += TICKS_PER_FRAME;
				frame(context);
				if (!warned && feetY(singleplayer) < CRUST_TOP_Y + 8) {
					warned = true;
					screenshot(context, "pod-brace-warning");
				}
			}
			context.getInput().releaseKey(options -> options.keySprint);
			if (!warned || feetY(singleplayer) > CRUST_TOP_Y + 0.01) {
				throw new AssertionError("The pod should drill down to the crust with the warning shown on the way, warned " + warned + ", it is at " + feetY(singleplayer));
			}
			context.waitTicks(20);
			screenshot(context, "pod-brace-on-the-crust");

			// The brace is fitted (as if bought at the upgrade terminal). It waits for the pod to rest, then burns two ore: 8 hull to 20, to 32.
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				ScannerPods.fit(server, player, (PodEntity) player.getVehicle(), ComponentTrack.BRACE, 1);
			});
			context.waitTicks(2);
			screenshot(context, "pod-brace-bracing");
			ClientWait.until(context, "the hull patched over the crust's price", client -> client.player.getVehicle() instanceof PodEntity pod && pod.hull() > CRUST + 1);
			for (int i = 0; i < 4; i++) {
				frame(context);
				context.waitTicks(TICKS_PER_FRAME);
			}
			screenshot(context, "pod-brace-patched");

			// Through the crust: three rows, 8 hull each.
			context.getInput().holdKey(options -> options.keySprint);
			ticks = 0;
			while (!context.computeOnClient(client -> client.level.dimension().equals(LayerChain.dimension(2))) && ticks < MAX_TICKS) {
				context.waitTicks(TICKS_PER_FRAME);
				ticks += TICKS_PER_FRAME;
				frame(context);
			}
			context.getInput().releaseKey(options -> options.keySprint);
			ClientWait.until(context, "the pod in layer 2", client -> client.level.dimension().equals(LayerChain.dimension(2)));
			context.waitTicks(120);
			for (int i = 0; i < 6; i++) {
				frame(context);
				context.waitTicks(TICKS_PER_FRAME);
			}
			float hull = singleplayer.getServer().computeOnServer(server -> ((PodEntity) server.getPlayerList().getPlayers().getFirst().getVehicle()).hull());
			screenshot(context, "pod-brace-crossed");
			if (hull < 1f) {
				throw new AssertionError("A pod patched over the crust's price crosses it alive, it has " + hull);
			}
		}
	}

	private static double feetY(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().getVehicle().getY());
	}

	/** A stone bed over three rows of breach crust, under an open room. */
	private static void build(ServerLevel level) {
		RoomCarver.carve(level, X - RADIUS, X + RADIUS, 0, 2, Z - RADIUS, Z + RADIUS, LayerBlocks.BREACH_CRUST);
		RoomCarver.carve(level, X - RADIUS, X + RADIUS, CRUST_TOP_Y, FLOOR_Y - 1, Z - RADIUS, Z + RADIUS, Blocks.STONE);
		RoomCarver.carve(level, new BlockPos(X - RADIUS, FLOOR_Y, Z - RADIUS), new BlockPos(X + RADIUS, FLOOR_Y + 9, Z + RADIUS),
				Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		for (BlockPos lamp : new BlockPos[] {
				new BlockPos(X + RADIUS, FLOOR_Y + 3, Z), new BlockPos(X - RADIUS, FLOOR_Y + 3, Z),
				new BlockPos(X, FLOOR_Y + 3, Z + RADIUS), new BlockPos(X, FLOOR_Y + 3, Z - RADIUS),
				new BlockPos(X - 2, FLOOR_Y - 2, Z), new BlockPos(X + 1, FLOOR_Y - 4, Z - 1), new BlockPos(X - 2, FLOOR_Y - 6, Z - 1),
				new BlockPos(X + 1, FLOOR_Y - 8, Z), new BlockPos(X - 2, FLOOR_Y - 10, Z), new BlockPos(X + 1, FLOOR_Y - 11, Z - 1),
				new BlockPos(X - 2, FLOOR_Y - 12, Z)}) {
			level.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), 3);
		}
	}
}
