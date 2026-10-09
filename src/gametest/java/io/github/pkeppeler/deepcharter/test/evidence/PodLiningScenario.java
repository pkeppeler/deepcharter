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

import io.github.pkeppeler.deepcharter.client.pod.LiningKeys;
import io.github.pkeppeler.deepcharter.layer.LavaHazard;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.scanner.LoadedBlocks;
import io.github.pkeppeler.deepcharter.scanner.ScanArea;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Evidence scenario "pod-lining" (#313): a pilot in a pod with the thermal scanner bores down a shaft with a lava pocket on either
 * side of it. The scanner shows the lava ahead, so the pilot lets go of the drill, presses the lining key, and the pod stands still
 * while it places slag brick over the lava beside the next slab. Then it bores on, lines the second slab of the pocket the same way,
 * and drills past the pocket with the hull as it was and no lava ever touching the pod. The pod has the spoil hopper, so the stone it bores
 * becomes the spoil that the HUD counts.
 */
public class PodLiningScenario extends EvidenceScenario {
	private static final int X = 4500;
	private static final int Z = 4500;
	private static final int FLOOR_Y = 100;
	private static final int RADIUS = 6;
	private static final int DEPTH = 14;
	/** The lava pockets: two slabs tall, one block either side of the shaft's column. */
	private static final int POCKET_TOP = FLOOR_Y - 7;
	private static final int POCKET_BOTTOM = FLOOR_Y - 8;
	private static final int TICKS_PER_FRAME = 4;
	private static final int MAX_TICKS = 4000;
	private static final int BRICKS = 16;
	private static final float LOOK_DOWN = 35f;
	private static final float LOOK_UP = -50f;
	private static final float FACING_EAST = -90f;
	private static final int THERMAL_TIER = 2;

	@Override
	protected String name() {
		return "pod-lining";
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
				ScannerPods.fit(server, player, pod, THERMAL_TIER);
				ScannerPods.fit(server, player, pod, ComponentTrack.SPOIL_HOPPER, 1);
				pod.setFuel(100f);
				PodLining.modify(pod, state -> new PodLining.State(0, BRICKS, 0, false, false));
				if (!player.startRiding(pod)) {
					throw new AssertionError("the player could not mount the pod");
				}
			});
			context.waitFor(client -> client.player != null && client.player.getVehicle() instanceof PodEntity
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

			// Bore down until the thermal scanner has the pocket in view, then let go of the drill.
			boreUntilFeetAt(context, singleplayer, POCKET_TOP + 3);
			if (!scannerShowsLava(singleplayer)) {
				throw new AssertionError("The thermal scanner should show the lava pockets from three slabs above them");
			}
			for (int i = 0; i < 8; i++) {
				frame(context);
				context.waitTicks(TICKS_PER_FRAME);
			}
			screenshot(context, "pod-lining-scanner");

			// One slab above the pocket: the next slab has lava beside it. Stop, line, and bore on.
			boreUntilFeetAt(context, singleplayer, POCKET_TOP + 1);
			line(context, singleplayer);
			screenshot(context, "pod-lining-first-slab");
			boreUntilFeetAt(context, singleplayer, POCKET_BOTTOM + 1);
			line(context, singleplayer);
			screenshot(context, "pod-lining-second-slab");
			boreUntilFeetAt(context, singleplayer, POCKET_BOTTOM - 3);
			// Look back up the shaft: the slag brick stands in its walls where the lava pockets are.
			context.runOnClient(client -> client.player.setXRot(LOOK_UP));
			for (int i = 0; i < 8; i++) {
				frame(context);
				context.waitTicks(TICKS_PER_FRAME);
			}
			screenshot(context, "pod-lining-past");

			String result = singleplayer.getServer().computeOnServer(server -> {
				PodEntity pod = (PodEntity) server.getPlayerList().getPlayers().getFirst().getVehicle();
				return pod.hull() == pod.maxHull() ? "" : "hull " + pod.hull() + " of " + pod.maxHull();
			});
			if (!result.isEmpty()) {
				throw new AssertionError("The pod should be past the lava with no hull lost, but: " + result);
			}
		}
	}

	/** Holds sprint, recording frames, until the pod's feet are at {@code feetY} or below; then lets go and waits for the pod to rest. */
	private void boreUntilFeetAt(ClientGameTestContext context, TestSingleplayerContext singleplayer, int feetY) {
		context.getInput().holdKey(options -> options.keySprint);
		int ticks = 0;
		while (feetY(singleplayer) > feetY + 0.01 && ticks < MAX_TICKS) {
			assertDry(singleplayer);
			context.waitTicks(TICKS_PER_FRAME);
			ticks += TICKS_PER_FRAME;
			frame(context);
		}
		context.getInput().releaseKey(options -> options.keySprint);
		if (feetY(singleplayer) > feetY + 0.01) {
			throw new AssertionError("The pod did not drill down to " + feetY + " within " + MAX_TICKS + " ticks, it is at " + feetY(singleplayer));
		}
		// The drill is released: wait for the slab in progress to finish and the pod to settle on its floor.
		for (int i = 0; i < 6; i++) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
	}

	/** The pilot presses the lining key, and the recording runs until the lining is done. */
	private void line(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		context.getInput().pressKey(LiningKeys.LINE);
		int ticks = 0;
		boolean started = false;
		while (ticks < MAX_TICKS) {
			context.waitTicks(TICKS_PER_FRAME);
			ticks += TICKS_PER_FRAME;
			frame(context);
			assertDry(singleplayer);
			boolean working = singleplayer.getServer().computeOnServer(server -> PodLining.working((PodEntity) server.getPlayerList().getPlayers().getFirst().getVehicle()));
			started |= working;
			if (started && !working) {
				break;
			}
		}
		if (!started) {
			throw new AssertionError("The lining key started no lining");
		}
		for (int i = 0; i < 6; i++) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
	}

	private static double feetY(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().getVehicle().getY());
	}

	private static void assertDry(TestSingleplayerContext singleplayer) {
		boolean touching = singleplayer.getServer().computeOnServer(server -> LavaHazard.touchesLava((PodEntity) server.getPlayerList().getPlayers().getFirst().getVehicle()));
		if (touching) {
			throw new AssertionError("Lava touched the pod, so the lining did not hold it back");
		}
	}

	private static boolean scannerShowsLava(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server -> {
			PodEntity pod = (PodEntity) server.getPlayerList().getPlayers().getFirst().getVehicle();
			ScanSlice slice = ScanSlice.scan(new LoadedBlocks(pod.level()), pod).orElseThrow(() -> new AssertionError("the pod has no working scanner"));
			ScanArea area = slice.area();
			for (int up = area.up(); up >= -area.down(); up--) {
				for (int ahead = -area.halfWidth(); ahead <= area.halfWidth(); ahead++) {
					if (slice.cell(ahead, up) == ScanSlice.Cell.LAVA) {
						return true;
					}
				}
			}
			return false;
		});
	}

	/** A stone bed under an open room, and a lava pocket sealed in the rock on each side of the column the pod bores. */
	private static void build(ServerLevel level) {
		RoomCarver.carve(level, X - RADIUS, X + RADIUS, FLOOR_Y - DEPTH, FLOOR_Y - 1, Z - RADIUS, Z + RADIUS, Blocks.STONE);
		RoomCarver.carve(level, new BlockPos(X - RADIUS, FLOOR_Y, Z - RADIUS), new BlockPos(X + RADIUS, FLOOR_Y + 9, Z + RADIUS),
				Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		// The pod stands at the middle of the column x - 1 and x, z - 1 and z. The ring beside it is x - 2 and x + 1: the pockets fill it.
		for (int[] pocket : new int[][] {{X + 1, X + 2}, {X - 3, X - 2}}) {
			RoomCarver.carve(level, new BlockPos(pocket[0], POCKET_BOTTOM, Z - 1), new BlockPos(pocket[1], POCKET_TOP, Z), Blocks.LAVA.defaultBlockState(),
					Block.UPDATE_CLIENTS);
		}
		for (BlockPos lamp : new BlockPos[] {
				new BlockPos(X + RADIUS, FLOOR_Y + 3, Z), new BlockPos(X - RADIUS, FLOOR_Y + 3, Z),
				new BlockPos(X, FLOOR_Y + 3, Z + RADIUS), new BlockPos(X, FLOOR_Y + 3, Z - RADIUS),
				new BlockPos(X - 2, FLOOR_Y - 3, Z), new BlockPos(X + 1, FLOOR_Y - 4, Z - 1), new BlockPos(X - 2, FLOOR_Y - 11, Z - 1),
				new BlockPos(X + 1, FLOOR_Y - 12, Z)}) {
			level.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), 3);
		}
	}
}
