package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.client.repair.RepairStationScreen;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.test.RepairStationClientTest;

/**
 * Evidence scenario "m2-repair-station" for #70: a charter repairs the hull at the station and buys dynamite and a matter
 * transmitter, then pilots its pod, blasts the rock around it with the dynamite, is carried 48 blocks away and uses the
 * transmitter to come back. Stills of the station screen and of the blast.
 */
public class RepairStationScenario extends EvidenceScenario {
	private static final int TICKS_PER_FRAME = 3;
	private static final int HOLD_FRAMES = 6;
	private static final int ROCK_RADIUS = 3;
	private static final int AWAY_BLOCKS = 48;
	private static final int HOTBAR = 9;
	private static final int FRAME_GUI_WIDTH = 400;
	private static final int FRAME_GUI_HEIGHT = 225;

	@Override
	protected String name() {
		return "m2-repair-station";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			RepairStationClientTest.Scene scene = singleplayer.getServer().computeOnServer(RepairStationClientTest::setUp);
			context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.station())));
			context.waitForScreen(RepairStationScreen.class);
			// The frame cuts the 854 by 480 window to 800 by 450, so the screen is laid out for the GUI the frame shows, 400 by 225.
			RepairStationScreen screen = context.computeOnClient(client -> (RepairStationScreen) client.gui.screen());
			context.runOnClient(client -> screen.resize(FRAME_GUI_WIDTH, FRAME_GUI_HEIGHT));
			hold(context);
			screenshot(context, "station");

			RepairStationClientTest.clickRow(context, "REPAIR ALL");
			RepairStationClientTest.awaitServer(context, () -> singleplayer.getServer().computeOnServer(server -> scene.pod().hull() == scene.pod().maxHull()));
			hold(context);
			RepairStationClientTest.clickRow(context, "BUY DYNAMITE $100");
			RepairStationClientTest.awaitServer(context, () -> singleplayer.getServer().computeOnServer(server -> carried(server, Consumable.DYNAMITE)) == 1);
			hold(context);
			RepairStationClientTest.clickRow(context, "BUY MATTER TRANSMITTER $1500");
			RepairStationClientTest.awaitServer(context, () -> singleplayer.getServer().computeOnServer(server -> carried(server, Consumable.MATTER_TRANSMITTER)) == 1);
			hold(context);
			screenshot(context, "station-bottom");
			context.setScreen(() -> null);

			// Pilot the pod and bury it in rock, so that the blast shows.
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				PodEntity pod = scene.pod();
				BlockPos middle = BlockPos.containing(pod.getBoundingBox().getCenter());
				for (BlockPos cell : BlockPos.betweenClosed(middle.offset(-ROCK_RADIUS, -1, -ROCK_RADIUS), middle.offset(ROCK_RADIUS, ROCK_RADIUS, ROCK_RADIUS))) {
					if (server.overworld().getBlockState(cell).isAir()) {
						server.overworld().setBlock(cell, Blocks.STONE.defaultBlockState(), 3);
					}
				}
				player.startRiding(pod, true, false);
			});
			context.waitTicks(TICKS_PER_FRAME * 2);
			hold(context);
			use(singleplayer, Consumable.DYNAMITE);
			for (int i = 0; i < HOLD_FRAMES; i++) {
				context.waitTicks(TICKS_PER_FRAME);
				frame(context);
			}
			screenshot(context, "blast");

			// Carried far from the colony, then back with the transmitter.
			singleplayer.getServer().runOnServer(server -> {
				PodEntity pod = scene.pod();
				// The rest of the rock is cleared away, so that the transmitter's landing at the spawn is free.
				BlockPos middle = BlockPos.containing(pod.getBoundingBox().getCenter());
				for (BlockPos cell : BlockPos.betweenClosed(middle.offset(-ROCK_RADIUS, -1, -ROCK_RADIUS), middle.offset(ROCK_RADIUS, ROCK_RADIUS, ROCK_RADIUS))) {
					if (server.overworld().getBlockState(cell).is(Blocks.STONE)) {
						server.overworld().setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
					}
				}
				Vec3 far = pod.position().add(AWAY_BLOCKS, 0, 0);
				int ground = server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) far.x, (int) far.z);
				pod.teleport(new TeleportTransition(server.overworld(), new Vec3(far.x, ground, far.z), Vec3.ZERO, pod.getYRot(), pod.getXRot(), TeleportTransition.DO_NOTHING));
			});
			context.waitTicks(TICKS_PER_FRAME * 4);
			hold(context);
			use(singleplayer, Consumable.MATTER_TRANSMITTER);
			for (int i = 0; i < HOLD_FRAMES * 2; i++) {
				context.waitTicks(TICKS_PER_FRAME);
				frame(context);
			}
			screenshot(context, "arrived");
		}
	}

	private static int carried(MinecraftServer server, Consumable consumable) {
		return server.getPlayerList().getPlayers().getFirst().getInventory().countItem(RepairRegistry.item(consumable));
	}

	/** Selects the bought item in the hotbar and uses it as a right click does. */
	private static void use(TestSingleplayerContext singleplayer, Consumable consumable) {
		singleplayer.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			Inventory inventory = player.getInventory();
			for (int slot = 0; slot < HOTBAR; slot++) {
				if (inventory.getItem(slot).is(RepairRegistry.item(consumable))) {
					inventory.setSelectedSlot(slot);
				}
			}
			player.gameMode.useItem(player, player.level(), player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
		});
	}

	private void hold(ClientGameTestContext context) {
		for (int i = 0; i < HOLD_FRAMES; i++) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
	}
}
