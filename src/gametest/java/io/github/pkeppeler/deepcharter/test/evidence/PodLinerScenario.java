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

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.layer.LavaHazard;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLiner;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Evidence scenario "pod-liner" (#339): a pilot in a pod with a tier 2 liner and the thermal scanner holds the drill down a shaft with
 * a lava pocket on either side of it at two depths. The pilot never lets go of the drill and never presses the lining key: every three
 * slabs the liner stops the lava with slag brick, and the pod drills past both pockets with the hull as it was and no lava ever touching it.
 * The HUD shows the slag in the rack and the slabs to the next ring.
 */
public class PodLinerScenario extends EvidenceScenario {
	private static final int X = 4500;
	private static final int Z = 4600;
	private static final int FLOOR_Y = 100;
	private static final int RADIUS = 6;
	private static final int DEPTH = 18;
	/** The tier 2 liner rings when the pod's feet are at 97, 94 and 91: each ring covers the slab below its feet, so a pocket one slab under a ring is lined before the drill opens it. */
	private static final int FIRST_POCKET_Y = FLOOR_Y - 7;
	private static final int SECOND_POCKET_Y = FLOOR_Y - 10;
	private static final int FIRST_RING_Y = FLOOR_Y - 6;
	private static final int SECOND_RING_Y = FLOOR_Y - 9;
	private static final int BOTTOM_Y = FLOOR_Y - 14;
	private static final int TICKS_PER_FRAME = 4;
	private static final int MAX_TICKS = 6000;
	private static final int BRICKS = 24;
	private static final float LOOK_DOWN = 30f;
	private static final float LOOK_UP = -50f;
	private static final float FACING_EAST = -90f;
	private static final int THERMAL_TIER = 2;
	private static final int LINER_TIER = 2;

	@Override
	protected String name() {
		return "pod-liner";
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
				ScannerPods.fit(server, player, pod, ComponentTrack.LINER, LINER_TIER);
				// The slower drill and a tier 2 tank: the stock tank runs dry before the second pocket.
				ScannerPods.fit(server, player, pod, ComponentTrack.FUEL_TANK, 2);
				pod.setFuel(100f);
				PodLining.modify(pod, state -> new PodLining.State(0, BRICKS, 0, false, false));
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

			// The drill is held for the whole dive. The liner does the lining.
			context.getInput().holdKey(options -> options.keySprint);
			boolean firstShot = false;
			boolean secondShot = false;
			int ticks = 0;
			while (feetY(singleplayer) > BOTTOM_Y + 0.01 && ticks < MAX_TICKS) {
				assertDry(singleplayer);
				context.waitTicks(TICKS_PER_FRAME);
				ticks += TICKS_PER_FRAME;
				frame(context);
				if (!firstShot && ringedAtOrBelow(singleplayer, FIRST_RING_Y)) {
					firstShot = true;
					screenshot(context, "pod-liner-first-ring");
				}
				if (!secondShot && ringedAtOrBelow(singleplayer, SECOND_RING_Y)) {
					secondShot = true;
					screenshot(context, "pod-liner-second-ring");
				}
			}
			context.getInput().releaseKey(options -> options.keySprint);
			if (feetY(singleplayer) > BOTTOM_Y + 0.01) {
				throw new AssertionError("The pod did not drill down to " + BOTTOM_Y + " within " + MAX_TICKS + " ticks, it is at " + feetY(singleplayer));
			}
			// Look back up the shaft: the slag brick stands in its walls where the lava pockets were.
			context.runOnClient(client -> client.player.setXRot(LOOK_UP));
			for (int i = 0; i < 12; i++) {
				context.waitTicks(TICKS_PER_FRAME);
				frame(context);
			}
			screenshot(context, "pod-liner-past");

			String result = singleplayer.getServer().computeOnServer(server -> {
				PodEntity pod = (PodEntity) server.getPlayerList().getPlayers().getFirst().getVehicle();
				ServerLevel one = server.getLevel(LayerChain.dimension(1));
				boolean lined = one.getBlockState(new BlockPos(X + 1, FIRST_POCKET_Y, Z)).is(SlagBrick.BLOCK)
						&& one.getBlockState(new BlockPos(X - 2, SECOND_POCKET_Y, Z)).is(SlagBrick.BLOCK);
				if (!lined) {
					return "the pockets were not lined with slag brick";
				}
				return pod.hull() == pod.maxHull() ? "" : "hull " + pod.hull() + " of " + pod.maxHull();
			});
			if (!result.isEmpty()) {
				throw new AssertionError("The pod should be past the lava with no hull lost and the pockets lined, but: " + result);
			}
		}
	}

	private static double feetY(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().getVehicle().getY());
	}

	/** The liner has rung at {@code ringY} or lower: the anchor it counts from has moved down to there. */
	private static boolean ringedAtOrBelow(TestSingleplayerContext singleplayer, int ringY) {
		return singleplayer.getServer().computeOnServer(server -> {
			PodEntity pod = (PodEntity) server.getPlayerList().getPlayers().getFirst().getVehicle();
			return Versioned.readable(pod, PodLiner.STATE).orElseThrow().anchor().map(PodLiner.Anchor::feetY).orElse(Integer.MAX_VALUE) <= ringY;
		});
	}

	private static void assertDry(TestSingleplayerContext singleplayer) {
		boolean touching = singleplayer.getServer().computeOnServer(server -> LavaHazard.touchesLava((PodEntity) server.getPlayerList().getPlayers().getFirst().getVehicle()));
		if (touching) {
			throw new AssertionError("Lava touched the pod, so the liner did not hold it back");
		}
	}

	/** A stone bed under an open room, and a one slab lava pocket sealed in the rock on each side of the column the pod bores, at two depths. */
	private static void build(ServerLevel level) {
		RoomCarver.carve(level, X - RADIUS, X + RADIUS, FLOOR_Y - DEPTH, FLOOR_Y - 1, Z - RADIUS, Z + RADIUS, Blocks.STONE);
		RoomCarver.carve(level, new BlockPos(X - RADIUS, FLOOR_Y, Z - RADIUS), new BlockPos(X + RADIUS, FLOOR_Y + 9, Z + RADIUS),
				Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		// The pod stands at the middle of the column x - 1 and x, z - 1 and z. The ring beside it is x - 2 and x + 1: the pockets fill it.
		for (int y : new int[] {FIRST_POCKET_Y, SECOND_POCKET_Y}) {
			for (int[] pocket : new int[][] {{X + 1, X + 2}, {X - 3, X - 2}}) {
				RoomCarver.carve(level, new BlockPos(pocket[0], y, Z - 1), new BlockPos(pocket[1], y, Z), Blocks.LAVA.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
		}
		for (BlockPos lamp : new BlockPos[] {
				new BlockPos(X + RADIUS, FLOOR_Y + 3, Z), new BlockPos(X - RADIUS, FLOOR_Y + 3, Z),
				new BlockPos(X, FLOOR_Y + 3, Z + RADIUS), new BlockPos(X, FLOOR_Y + 3, Z - RADIUS),
				new BlockPos(X - 2, FLOOR_Y - 3, Z), new BlockPos(X + 1, FLOOR_Y - 5, Z - 1), new BlockPos(X - 2, FLOOR_Y - 8, Z - 1),
				new BlockPos(X + 1, FLOOR_Y - 11, Z), new BlockPos(X - 2, FLOOR_Y - 13, Z)}) {
			level.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), 3);
		}
	}
}
