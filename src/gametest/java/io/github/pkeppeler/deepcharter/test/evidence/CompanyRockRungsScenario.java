package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Evidence scenario "company-rock-rungs" (#526): the two later rungs of the Company rock ladder, in two sealed stone rooms of layer 1.
 *
 * <p>Room one: a Mole sits on a thick stretch of Company rock, 6 rows deep, with stone under it and Company rock beside it. The pilot sets a shaped charge from
 * the seat, the fuse counts down, the pod drives clear, and the charge clears the whole column down to the stone and no further.
 *
 * <p>Room two: a Prospector sits on a slab of Company rock. With the stock drill the pilot is told the drill cannot bite it. A tier 3 drill is fitted, and the
 * same sprint bores the slab slowly while the tank and the hull drop.
 */
public class CompanyRockRungsScenario extends EvidenceScenario {
	private static final int X = 5800;
	private static final int Z = 5000;
	private static final int FLOOR_Y = 40;
	private static final int ROWS = 6;
	private static final int TICKS_PER_FRAME = 3;
	private static final int MAX_TICKS = 900;
	/** The front camera mirrors the pitch: a negative pitch puts it above the pod, looking down at it and at the slab behind it. */
	private static final float LOOK_DOWN = -35f;
	/** Past the reach of the blast: the pod's box (1.9 wide) is more than the 3 blocks of chargeReach from the volume (it ends at Z + 1). */
	private static final double SAFE_Z = Z + 5.5;
	private static final int BITE_X = 5900;
	private static final int REFUSED_TICKS = 45;

	@Override
	protected String name() {
		return "company-rock-rungs";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(40);
			shapedCharge(context, singleplayer);
			biteWithTheDrill(context, singleplayer);
		}
	}

	private void shapedCharge(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		singleplayer.getServer().runOnServer(server -> {
			ServerLevel one = server.getLevel(LayerChain.dimension(1));
			room(one, X);
			for (int dx = -1; dx <= 0; dx++) {
				for (int dz = -1; dz <= 0; dz++) {
					for (int dy = -ROWS; dy <= -1; dy++) {
						one.setBlock(new BlockPos(X + dx, FLOOR_Y + dy, Z + dz), HazardBlocks.COMPANY_ROCK.defaultBlockState(), 3);
					}
				}
			}
			// Beside the column and under the stone that ends it: both must still be there afterwards.
			for (BlockPos stays : stays()) {
				one.setBlock(stays, HazardBlocks.COMPANY_ROCK.defaultBlockState(), 3);
			}
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			player.setGameMode(GameType.SURVIVAL);
			player.teleportTo(one, X, FLOOR_Y, Z, Set.of(), 0, LOOK_DOWN, true);
			PodEntity pod = PodRegistry.POD.create(one, EntitySpawnReason.COMMAND);
			pod.setPos(X, FLOOR_Y, Z);
			one.addFreshEntity(pod);
			if (!player.startRiding(pod)) {
				throw new AssertionError("the player could not mount the pod");
			}
		});
		ClientWait.until(context, "the pilot seated in a pod resting on its slab in layer 1", client -> client.player != null
				&& client.player.getVehicle() instanceof PodEntity pod && pod.onGround() && client.level.dimension().equals(LayerChain.dimension(1)));
		context.runOnClient(client -> {
			client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
			client.player.setXRot(LOOK_DOWN);
		});
		context.waitTicks(30);
		for (int i = 0; i < 6; i++) {
			frame(context);
			context.waitTicks(TICKS_PER_FRAME);
		}
		screenshot(context, "rungs-shaped-before");

		singleplayer.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			player.getInventory().setItem(player.getInventory().getSelectedSlot(), new ItemStack(RepairRegistry.item(Consumable.SHAPED_CHARGE)));
			if (!player.gameMode.useItem(player, player.level(), player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND).consumesAction()) {
				throw new AssertionError("the shaped charge should be set from the seat");
			}
		});
		for (int i = 0; i < 4; i++) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
		screenshot(context, "rungs-shaped-fuse");
		context.getInput().holdKey(options -> options.keyUp);
		int ticks = 0;
		while (podZ(singleplayer) < SAFE_Z && ticks < MAX_TICKS) {
			context.waitTicks(TICKS_PER_FRAME);
			ticks += TICKS_PER_FRAME;
			frame(context);
		}
		context.getInput().releaseKey(options -> options.keyUp);
		if (podZ(singleplayer) < SAFE_Z) {
			throw new AssertionError("the pod should drive clear of the blast, it is at z " + podZ(singleplayer));
		}
		ticks = 0;
		while (!blasted(singleplayer) && ticks < MAX_TICKS) {
			context.waitTicks(TICKS_PER_FRAME);
			ticks += TICKS_PER_FRAME;
			frame(context);
		}
		if (!blasted(singleplayer)) {
			throw new AssertionError("the shaped charge should have blown and cleared the whole column");
		}
		for (int i = 0; i < 4; i++) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
		if (podHull(singleplayer) < 99f) {
			throw new AssertionError("a pod clear of the blast should keep its hull, it has " + podHull(singleplayer));
		}
		if (!stopped(singleplayer)) {
			throw new AssertionError("the shaped charge should stop at the stone under the column, and leave the Company rock beside it and under the stone");
		}
		// Back over the hole: the pod sinks down the whole cleared column.
		context.getInput().holdKey(options -> options.keyDown);
		ticks = 0;
		while (podZ(singleplayer) > Z + 0.45 && ticks < MAX_TICKS) {
			context.waitTicks(TICKS_PER_FRAME);
			ticks += TICKS_PER_FRAME;
			frame(context);
		}
		context.getInput().releaseKey(options -> options.keyDown);
		ticks = 0;
		double bottom = FLOOR_Y - ROWS;
		while (podY(singleplayer) > bottom + 0.1 && ticks < MAX_TICKS) {
			context.waitTicks(TICKS_PER_FRAME);
			ticks += TICKS_PER_FRAME;
			frame(context);
		}
		if (podY(singleplayer) > bottom + 0.1) {
			throw new AssertionError("the pod should sink the whole " + ROWS + " rows of the cleared column, it is at y " + podY(singleplayer));
		}
		for (int i = 0; i < 6; i++) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
		screenshot(context, "rungs-shaped-shaft");
		context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
		floatAbovePod(singleplayer, X, FLOOR_Y + 4.0, Z);
		for (int i = 0; i < 10; i++) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
		screenshot(context, "rungs-shaped-column");
	}

	private void biteWithTheDrill(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		singleplayer.getServer().runOnServer(server -> {
			ServerLevel one = server.getLevel(LayerChain.dimension(1));
			room(one, BITE_X);
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					one.setBlock(new BlockPos(BITE_X + dx, FLOOR_Y - 1, Z + dz), HazardBlocks.COMPANY_ROCK.defaultBlockState(), 3);
				}
			}
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			player.setGameMode(GameType.SURVIVAL);
			player.teleportTo(one, BITE_X + 0.5, FLOOR_Y, Z + 0.5, Set.of(), 0, LOOK_DOWN, true);
			PodEntity pod = PodRegistry.PROSPECTOR.create(one, EntitySpawnReason.COMMAND);
			pod.setPos(BITE_X + 0.5, FLOOR_Y, Z + 0.5);
			one.addFreshEntity(pod);
			if (!player.startRiding(pod)) {
				throw new AssertionError("the player could not mount the Prospector");
			}
		});
		ClientWait.until(context, "the pilot seated in the Prospector in room two", client -> client.player != null && client.player.getVehicle() instanceof PodEntity pod
				&& pod.onGround() && pod.getX() > BITE_X - 2);
		context.runOnClient(client -> {
			client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
			client.player.setXRot(LOOK_DOWN);
		});
		context.waitTicks(30);
		context.runOnClient(client -> TransmissionOverlay.reset());
		// The stock drill: the sprint is refused.
		context.getInput().holdKey(options -> options.keySprint);
		for (int tick = 0; tick < REFUSED_TICKS; tick += TICKS_PER_FRAME) {
			context.waitTicks(TICKS_PER_FRAME);
			snap(context);
		}
		screenshot(context, "rungs-bite-refused");
		if (slabBored(singleplayer)) {
			throw new AssertionError("a stock drill should not bore Company rock");
		}
		context.getInput().releaseKey(options -> options.keySprint);
		float[] before = singleplayer.getServer().computeOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			PodEntity pod = (PodEntity) player.getVehicle();
			ScannerPods.fit(server, player, pod, ComponentTrack.FUEL_TANK, 3);
			pod.setFuel(100f);
			ScannerPods.fit(server, player, pod, ComponentTrack.DRILL, 3);
			return new float[] {pod.fuel(), pod.hull()};
		});
		context.waitTicks(10);
		context.getInput().holdKey(options -> options.keySprint);
		int ticks = 0;
		boolean mid = false;
		while (!slabBored(singleplayer) && ticks < MAX_TICKS) {
			context.waitTicks(TICKS_PER_FRAME);
			ticks += TICKS_PER_FRAME;
			snap(context);
			if (!mid && ticks > 60) {
				mid = true;
				screenshot(context, "rungs-bite-cutting");
			}
		}
		context.getInput().releaseKey(options -> options.keySprint);
		if (!slabBored(singleplayer)) {
			throw new AssertionError("a tier 3 drill should bore the Company rock slab");
		}
		for (int i = 0; i < 6; i++) {
			context.waitTicks(TICKS_PER_FRAME);
			snap(context);
		}
		screenshot(context, "rungs-bite-bored");
		float[] after = singleplayer.getServer().computeOnServer(server -> {
			PodEntity pod = (PodEntity) server.getPlayerList().getPlayers().getFirst().getVehicle();
			return new float[] {pod.fuel(), pod.hull()};
		});
		if (after[1] >= before[1]) {
			throw new AssertionError("the cutter's heat should cost hull: " + before[1] + " then " + after[1]);
		}
		if (after[0] >= before[0]) {
			throw new AssertionError("the bite should burn fuel: " + before[0] + " then " + after[0]);
		}
	}

	/** A frame with no transmission over it: a stand-in transmission fires when the pilot reaches a new place, and would cover the pod. */
	private void snap(ClientGameTestContext context) {
		context.runOnClient(client -> TransmissionOverlay.reset());
		frame(context);
	}

	private static void room(ServerLevel one, int x) {
		RoomCarver.carve(one, x - 9, x + 9, FLOOR_Y - 12, FLOOR_Y + 10, Z - 13, Z + 13, Blocks.STONE);
		RoomCarver.carve(one, new BlockPos(x - 8, FLOOR_Y, Z - 12), new BlockPos(x + 8, FLOOR_Y + 8, Z + 12), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		for (int dx = -6; dx <= 6; dx += 6) {
			for (int dz = -9; dz <= 9; dz += 6) {
				one.setBlock(new BlockPos(x + dx, FLOOR_Y + 8, Z + dz), Blocks.GLOWSTONE.defaultBlockState(), 3);
			}
		}
	}

	/** Company rock that the shaped charge must leave: beside the column, and under the stone that ends it. */
	private static BlockPos[] stays() {
		return new BlockPos[] {new BlockPos(X + 1, FLOOR_Y - 3, Z), new BlockPos(X - 2, FLOOR_Y - 4, Z - 1), new BlockPos(X, FLOOR_Y - ROWS - 2, Z)};
	}

	private static boolean blasted(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server -> {
			ServerLevel one = server.getLevel(LayerChain.dimension(1));
			for (int dx = -1; dx <= 0; dx++) {
				for (int dz = -1; dz <= 0; dz++) {
					for (int dy = -ROWS; dy <= -1; dy++) {
						if (!one.getBlockState(new BlockPos(X + dx, FLOOR_Y + dy, Z + dz)).isAir()) {
							return false;
						}
					}
				}
			}
			return true;
		});
	}

	private static boolean stopped(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server -> {
			ServerLevel one = server.getLevel(LayerChain.dimension(1));
			for (BlockPos stays : stays()) {
				if (!one.getBlockState(stays).is(HazardBlocks.COMPANY_ROCK)) {
					return false;
				}
			}
			return one.getBlockState(new BlockPos(X, FLOOR_Y - ROWS - 1, Z)).is(Blocks.STONE);
		});
	}

	private static boolean slabBored(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server ->
				server.getLevel(LayerChain.dimension(1)).getBlockState(new BlockPos(BITE_X, FLOOR_Y - 1, Z)).isAir());
	}

	private static float podHull(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server -> ((PodEntity) server.getPlayerList().getPlayers().getFirst().getVehicle()).hull());
	}

	private static double podY(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().getVehicle().getY());
	}

	private static double podZ(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().getVehicle().getZ());
	}
}
