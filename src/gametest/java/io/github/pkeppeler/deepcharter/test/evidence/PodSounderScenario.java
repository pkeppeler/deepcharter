package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodSounder;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Evidence scenario "pod-sounder" (#373): a pilot in a pod with a tier 2 seep sounder holds the drill down a shaft. A gas pocket sits in the rock under the pod,
 * and looks like plain stone. The HUD counts the slabs down to it (SEEPAGE 4, 3, 2, 1) while the pod hisses; at the last slab the drill stops for three seconds
 * (BLEEDING SEEPAGE) and bores it, and the hull loses a quarter of what the blast costs. Further down a second pocket lies in the rock to the east, which the HUD
 * names (SEEPAGE BESIDE E) while the pod passes it.
 */
public class PodSounderScenario extends EvidenceScenario {
	private static final int X = 4700;
	private static final int Z = 4800;
	private static final int FLOOR_Y = 100;
	private static final int RADIUS = 6;
	private static final int DEPTH = 18;
	private static final int FIRST_POCKET_Y = FLOOR_Y - 4;
	private static final int SECOND_POCKET_Y = FLOOR_Y - 9;
	private static final int BOTTOM_Y = FLOOR_Y - 14;
	private static final int TICKS_PER_FRAME = 4;
	private static final int MAX_TICKS = 6000;
	private static final float LOOK_DOWN = 30f;
	private static final float FACING_EAST = -90f;
	private static final int SOUNDER_TIER = 2;

	@Override
	protected String name() {
		return "pod-sounder";
	}

	/** What the server says about the pod now: its feet, the sounder's marks, whether the drill is working, the hull and whether the first pocket is still there. */
	private record Snapshot(double feetY, int down, boolean east, boolean drilling, float hull, float maxHull, boolean pocketStands) {
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
				ScannerPods.fit(server, player, pod, ComponentTrack.SOUNDER, SOUNDER_TIER);
				// The slower drill and a tier 2 tank: the stock tank runs dry before the second pocket.
				ScannerPods.fit(server, player, pod, ComponentTrack.FUEL_TANK, 2);
				pod.setFuel(100f);
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

			// The drill is held for the whole dive. The sounder only warns; the pilot does nothing about the pocket.
			context.getInput().holdKey(options -> options.keySprint);
			boolean seepage = false;
			boolean lastSlab = false;
			boolean bleeding = false;
			boolean bled = false;
			boolean beside = false;
			int ticks = 0;
			float maxHull = 0;
			float hullAfter = 0;
			while (snapshot(singleplayer).feetY() > BOTTOM_Y + 0.01 && ticks < MAX_TICKS) {
				context.waitTicks(TICKS_PER_FRAME);
				ticks += TICKS_PER_FRAME;
				frame(context);
				Snapshot now = snapshot(singleplayer);
				maxHull = now.maxHull();
				if (!seepage && now.down() > 0 && now.pocketStands()) {
					seepage = true;
					screenshot(context, "pod-sounder-seepage");
				}
				if (!lastSlab && now.down() == 1 && now.pocketStands()) {
					lastSlab = true;
					screenshot(context, "pod-sounder-last-slab");
				}
				if (!bleeding && now.down() == 1 && now.drilling() && now.pocketStands()) {
					bleeding = true;
					for (int i = 0; i < 6; i++) {
						context.waitTicks(TICKS_PER_FRAME);
						ticks += TICKS_PER_FRAME;
						frame(context);
					}
					screenshot(context, "pod-sounder-bleeding");
				}
				if (bleeding && !bled && !now.pocketStands()) {
					bled = true;
					hullAfter = now.hull();
					screenshot(context, "pod-sounder-bled");
				}
				if (bled && !beside && now.east()) {
					beside = true;
					screenshot(context, "pod-sounder-beside");
				}
			}
			context.getInput().releaseKey(options -> options.keySprint);
			if (feetBelow(singleplayer)) {
				throw new AssertionError("The pod did not drill down to " + BOTTOM_Y + " within " + MAX_TICKS + " ticks, it is at " + snapshot(singleplayer).feetY());
			}
			if (!seepage || !lastSlab || !bleeding || !bled || !beside) {
				throw new AssertionError("The dive should show seepage " + seepage + ", the last slab " + lastSlab + ", the bleed " + bleeding + ", the bled pocket " + bled
						+ " and the pocket beside " + beside);
			}
			if (!(hullAfter < maxHull) || maxHull - hullAfter > maxHull / 2) {
				throw new AssertionError("A bled pocket should cost the hull a little, it went from " + maxHull + " to " + hullAfter);
			}
		}
	}

	private static boolean feetBelow(TestSingleplayerContext singleplayer) {
		return snapshot(singleplayer).feetY() > BOTTOM_Y + 0.01;
	}

	private static Snapshot snapshot(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server -> {
			PodEntity pod = (PodEntity) server.getPlayerList().getPlayers().getFirst().getVehicle();
			ServerLevel one = server.getLevel(LayerChain.dimension(1));
			PodSounder.State state = PodSounder.reading(pod).orElseThrow();
			return new Snapshot(pod.getY(), state.down(), state.marks(Direction.EAST), pod.drilling(), pod.hull(), pod.maxHull(),
					one.getBlockState(new BlockPos(X, FIRST_POCKET_Y, Z)).is(HazardBlocks.GAS_POCKET));
		});
	}

	/** A stone bed under an open room, a gas pocket in the column the pod bores, and a second one in the rock to the east of it, lower down. */
	private static void build(ServerLevel level) {
		RoomCarver.carve(level, X - RADIUS, X + RADIUS, FLOOR_Y - DEPTH, FLOOR_Y - 1, Z - RADIUS, Z + RADIUS, Blocks.STONE);
		RoomCarver.carve(level, new BlockPos(X - RADIUS, FLOOR_Y, Z - RADIUS), new BlockPos(X + RADIUS, FLOOR_Y + 9, Z + RADIUS),
				Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(new BlockPos(X, FIRST_POCKET_Y, Z), HazardBlocks.GAS_POCKET.defaultBlockState(), 3);
		level.setBlock(new BlockPos(X + 2, SECOND_POCKET_Y, Z), HazardBlocks.GAS_POCKET.defaultBlockState(), 3);
		for (BlockPos lamp : new BlockPos[] {
				new BlockPos(X + RADIUS, FLOOR_Y + 3, Z), new BlockPos(X - RADIUS, FLOOR_Y + 3, Z),
				new BlockPos(X, FLOOR_Y + 3, Z + RADIUS), new BlockPos(X, FLOOR_Y + 3, Z - RADIUS),
				new BlockPos(X - 2, FLOOR_Y - 2, Z), new BlockPos(X + 1, FLOOR_Y - 3, Z - 1), new BlockPos(X - 2, FLOOR_Y - 5, Z - 1),
				new BlockPos(X + 1, FLOOR_Y - 7, Z), new BlockPos(X - 2, FLOOR_Y - 8, Z), new BlockPos(X + 1, FLOOR_Y - 11, Z - 1),
				new BlockPos(X - 2, FLOOR_Y - 12, Z), new BlockPos(X + 1, FLOOR_Y - 13, Z)}) {
			level.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), 3);
		}
	}
}
