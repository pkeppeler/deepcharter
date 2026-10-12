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

/**
 * Evidence scenario "pod-charge" (#509): a pod sits on a slab of Company rock in a sealed stone room. The pilot sets a blasting charge from the seat (the
 * item, a right click), the fuse counts down on the action bar with flames at the charge, and the pilot drives clear. The charge blows: the Company rock under
 * the pod goes, the blocks beside the blast stay, and the pod comes back, drops into the hole and bores on down in a straight 2 x 2 shaft.
 */
public class PodChargeScenario extends EvidenceScenario {
	private static final int X = 5500;
	private static final int Z = 5000;
	private static final int FLOOR_Y = 40;
	private static final int TICKS_PER_FRAME = 3;
	private static final int MAX_TICKS = 900;
	private static final float LOOK_DOWN = 35f;
	/** Past the reach of the blast: the pod's box (1.9 wide) is more than the 3 blocks of chargeReach from the volume (it ends at Z + 1). */
	private static final double SAFE_Z = Z + 5.5;
	private static final int BORED_SLABS = 3;

	@Override
	protected String name() {
		return "pod-charge";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(40);
			singleplayer.getServer().runOnServer(server -> {
				ServerLevel one = server.getLevel(LayerChain.dimension(1));
				RoomCarver.carve(one, X - 9, X + 9, FLOOR_Y - 9, FLOOR_Y + 10, Z - 13, Z + 13, Blocks.STONE);
				RoomCarver.carve(one, new BlockPos(X - 8, FLOOR_Y, Z - 12), new BlockPos(X + 8, FLOOR_Y + 8, Z + 12), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				for (int dx = -1; dx <= 0; dx++) {
					for (int dz = -1; dz <= 0; dz++) {
						for (int dy = -2; dy <= -1; dy++) {
							one.setBlock(new BlockPos(X + dx, FLOOR_Y + dy, Z + dz), HazardBlocks.COMPANY_ROCK.defaultBlockState(), 3);
						}
					}
				}
				// Company rock beside the blast: it must still be there afterwards.
				for (BlockPos beside : new BlockPos[] {new BlockPos(X + 1, FLOOR_Y - 1, Z), new BlockPos(X - 3, FLOOR_Y - 1, Z + 2), new BlockPos(X + 2, FLOOR_Y - 2, Z - 3)}) {
					one.setBlock(beside, HazardBlocks.COMPANY_ROCK.defaultBlockState(), 3);
				}
				for (int x = -6; x <= 6; x += 6) {
					for (int z = -9; z <= 9; z += 6) {
						one.setBlock(new BlockPos(X + x, FLOOR_Y + 8, Z + z), Blocks.GLOWSTONE.defaultBlockState(), 3);
					}
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
			ClientWait.until(context, "the pilot seated in the pod in layer 1", client -> client.player != null && client.player.getVehicle() instanceof PodEntity
					&& client.level.dimension().equals(LayerChain.dimension(1)));
			ClientWait.until(context, "the pod resting on its slab", client -> client.player.getVehicle().onGround());
			context.runOnClient(client -> {
				client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
				client.player.setXRot(LOOK_DOWN);
			});
			context.waitTicks(30);
			context.runOnClient(client -> TransmissionOverlay.reset());
			for (int i = 0; i < 6; i++) {
				frame(context);
				context.waitTicks(TICKS_PER_FRAME);
			}
			screenshot(context, "pod-charge-before");

			// Set the charge: the item in the hand and a right click, as a player does it from the seat.
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.getInventory().setItem(player.getInventory().getSelectedSlot(), new ItemStack(RepairRegistry.item(Consumable.BLASTING_CHARGE)));
				if (!player.gameMode.useItem(player, player.level(), player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND).consumesAction()) {
					throw new AssertionError("the charge should be set from the seat");
				}
			});
			for (int i = 0; i < 4; i++) {
				context.waitTicks(TICKS_PER_FRAME);
				frame(context);
			}
			screenshot(context, "pod-charge-fuse");
			// Drive clear while the fuse burns.
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
				throw new AssertionError("the charge should have blown and cleared the Company rock");
			}
			for (int i = 0; i < 6; i++) {
				context.waitTicks(TICKS_PER_FRAME);
				frame(context);
			}
			screenshot(context, "pod-charge-blown");
			float hull = podHull(singleplayer);
			if (hull < 99f) {
				throw new AssertionError("a pod clear of the blast should keep its hull, it has " + hull);
			}
			if (!beside(singleplayer)) {
				throw new AssertionError("the Company rock beside the blast should still stand");
			}

			// Back over the hole, down into it, and bore on straight.
			context.getInput().holdKey(options -> options.keyDown);
			ticks = 0;
			while (podZ(singleplayer) > Z + 0.45 && ticks < MAX_TICKS) {
				context.waitTicks(TICKS_PER_FRAME);
				ticks += TICKS_PER_FRAME;
				frame(context);
			}
			context.getInput().releaseKey(options -> options.keyDown);
			context.getInput().holdKey(options -> options.keySprint);
			ticks = 0;
			double bottom = FLOOR_Y - 2 - BORED_SLABS;
			while (podY(singleplayer) > bottom + 0.1 && ticks < MAX_TICKS) {
				context.waitTicks(TICKS_PER_FRAME);
				ticks += TICKS_PER_FRAME;
				frame(context);
			}
			context.getInput().releaseKey(options -> options.keySprint);
			if (podY(singleplayer) > bottom + 0.1) {
				throw new AssertionError("the pod should bore on down the straight shaft, it is at y " + podY(singleplayer));
			}
			for (int i = 0; i < 6; i++) {
				context.waitTicks(TICKS_PER_FRAME);
				frame(context);
			}
			screenshot(context, "pod-charge-straight");
		}
	}

	private static boolean blasted(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server -> {
			ServerLevel one = server.getLevel(LayerChain.dimension(1));
			for (int dx = -1; dx <= 0; dx++) {
				for (int dz = -1; dz <= 0; dz++) {
					for (int dy = -2; dy <= -1; dy++) {
						if (!one.getBlockState(new BlockPos(X + dx, FLOOR_Y + dy, Z + dz)).isAir()) {
							return false;
						}
					}
				}
			}
			return true;
		});
	}

	private static boolean beside(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server -> {
			ServerLevel one = server.getLevel(LayerChain.dimension(1));
			return one.getBlockState(new BlockPos(X + 1, FLOOR_Y - 1, Z)).is(HazardBlocks.COMPANY_ROCK)
					&& one.getBlockState(new BlockPos(X - 3, FLOOR_Y - 1, Z + 2)).is(HazardBlocks.COMPANY_ROCK)
					&& one.getBlockState(new BlockPos(X + 2, FLOOR_Y - 2, Z - 3)).is(HazardBlocks.COMPANY_ROCK);
		});
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
