package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.HardLanding;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/**
 * Evidence scenario "pod-hard-landing" (#319): a pilot brakes a pod down an open shaft with the rotor and lands unhurt; the same
 * shaft in free fall shows "HARD LANDING" on the way down and wrecks the pod.
 */
public class PodHardLandingScenario extends EvidenceScenario {
	private static final int X = 5200;
	private static final int Z = 5200;
	private static final int FLOOR_Y = 4;
	private static final int SHAFT_BLOCKS = 96;
	private static final int BRAKED_TICKS_PER_FRAME = 5;
	private static final int FALL_TICKS_PER_FRAME = 2;
	private static final int MAX_TICKS = 900;
	private static final double BRAKE_ABOVE_SINK = 0.5;
	private static final Input JUMP = new Input(false, false, false, false, true, false, false);

	@Override
	protected String name() {
		return "pod-hard-landing";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(40);
			singleplayer.getServer().runOnServer(server -> {
				ServerLevel one = server.getLevel(LayerChain.dimension(1));
				RoomCarver.carve(one, new BlockPos(X - 2, FLOOR_Y - 2, Z - 2), new BlockPos(X + 2, FLOOR_Y, Z + 2), Blocks.STONE.defaultBlockState());
				RoomCarver.carve(one, new BlockPos(X - 1, FLOOR_Y + 1, Z - 1), new BlockPos(X + 1, FLOOR_Y + SHAFT_BLOCKS, Z + 1), Blocks.AIR.defaultBlockState());
				// A glowstone lamp every 12 blocks lights the shaft wall, so the descent reads on screen.
				for (int y = FLOOR_Y + 6; y < FLOOR_Y + SHAFT_BLOCKS; y += 12) {
					one.setBlock(new BlockPos(X + 2, y, Z), Blocks.GLOWSTONE.defaultBlockState(), 3);
				}
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.setGameMode(GameType.SURVIVAL);
				player.teleportTo(one, X + 0.5, FLOOR_Y + SHAFT_BLOCKS - 2, Z + 0.5, Set.of(), 0, 0, true);
			});
			context.waitFor(client -> client.player != null && client.level.dimension().equals(LayerChain.dimension(1)));
			context.waitTicks(30);

			// Braked: the pilot taps the rotor whenever the sink passes BRAKE_ABOVE_SINK.
			boardNewPod(singleplayer);
			context.waitFor(client -> client.player.getVehicle() instanceof PodEntity);
			context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
			context.waitTicks(10);
			screenshot(context, "hard-landing-braked-start");
			int ticks = 0;
			while (!podOnGround(singleplayer) && ticks < MAX_TICKS) {
				singleplayer.getServer().runOnServer(server -> {
					ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
					boolean brake = player.getVehicle() instanceof PodEntity pod && HardLanding.sinkSpeed(pod.getDeltaMovement().y) > BRAKE_ABOVE_SINK;
					player.setLastClientInput(brake ? JUMP : Input.EMPTY);
				});
				context.waitTicks(1);
				ticks++;
				if (ticks % BRAKED_TICKS_PER_FRAME == 0) {
					frame(context);
				}
			}
			if (!podOnGround(singleplayer)) {
				throw new AssertionError("The braked pod did not reach the floor within " + MAX_TICKS + " ticks");
			}
			context.waitTicks(10);
			String braked = singleplayer.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				PodEntity pod = (PodEntity) player.getVehicle();
				return pod.hull() == pod.maxHull() && player.getHealth() == player.getMaxHealth() ? "" : "hull " + pod.hull() + ", health " + player.getHealth();
			});
			if (!braked.isEmpty()) {
				throw new AssertionError("A braked descent should leave the pod and pilot unhurt, but: " + braked);
			}
			for (int i = 0; i < 4; i++) {
				context.waitTicks(BRAKED_TICKS_PER_FRAME);
				frame(context);
			}
			screenshot(context, "hard-landing-braked-landed");

			// Free fall: the same shaft, no rotor.
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.getVehicle().discard();
				player.setLastClientInput(Input.EMPTY);
				player.teleportTo(server.getLevel(LayerChain.dimension(1)), X + 0.5, FLOOR_Y + SHAFT_BLOCKS - 2, Z + 0.5, Set.of(), 0, 0, true);
			});
			context.waitTicks(20);
			boardNewPod(singleplayer);
			context.waitFor(client -> client.player.getVehicle() instanceof PodEntity);
			screenshot(context, "hard-landing-free-start");
			boolean warned = false;
			ticks = 0;
			while (!podWrecked(singleplayer) && ticks < MAX_TICKS) {
				context.waitTicks(FALL_TICKS_PER_FRAME);
				ticks += FALL_TICKS_PER_FRAME;
				frame(context);
				if (!warned && context.computeOnClient(client -> client.player.getVehicle() instanceof PodEntity pod
						&& PodStatusHud.hardLandingLine(pod).isPresent())) {
					warned = true;
					screenshot(context, "hard-landing-warning");
				}
			}
			if (!podWrecked(singleplayer)) {
				throw new AssertionError("The free fall did not wreck the pod within " + MAX_TICKS + " ticks");
			}
			if (!warned) {
				throw new AssertionError("The status readout never showed HARD LANDING during the free fall");
			}
			for (int i = 0; i < 4; i++) {
				context.waitTicks(FALL_TICKS_PER_FRAME);
				frame(context);
			}
			screenshot(context, "hard-landing-free-wrecked");
		}
	}

	private static void boardNewPod(TestSingleplayerContext singleplayer) {
		singleplayer.getServer().runOnServer(server -> {
			ServerLevel one = server.getLevel(LayerChain.dimension(1));
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			PodEntity pod = PodRegistry.POD.create(one, EntitySpawnReason.COMMAND);
			pod.setPos(X + 0.5, FLOOR_Y + SHAFT_BLOCKS - 2, Z + 0.5);
			one.addFreshEntity(pod);
			if (!player.startRiding(pod)) {
				throw new AssertionError("the player could not mount the pod");
			}
		});
	}

	private static boolean podOnGround(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server ->
				server.getPlayerList().getPlayers().getFirst().getVehicle() instanceof PodEntity pod && pod.onGround() && pod.tickCount > 5);
	}

	private static boolean podWrecked(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server -> server.getLevel(LayerChain.dimension(1))
				.getEntitiesOfClass(PodEntity.class, new AABB(X - 3, FLOOR_Y - 2, Z - 3, X + 3, FLOOR_Y + SHAFT_BLOCKS + 3, Z + 3))
				.stream().anyMatch(Wrecks::isWreck));
	}
}
