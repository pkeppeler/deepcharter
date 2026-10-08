package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Evidence scenario "m2-lampless-figure": a rail line like Prospector's Run, closed and dark (the camera has night vision, so the
 * dark can be seen). The lampless figure walks down the rails toward a pod that has no light, parked beside them. The pod's lights come on, the figure
 * walks on until it is near the light, and then fades out.
 */
public class LamplessFigureScenario extends EvidenceScenario {
	private static final int HALF_LENGTH = 20;
	private static final int HALF_WIDTH = 2;
	private static final int HEIGHT = 4;
	private static final int TICKS_PER_FRAME = 3;
	private static final int DARK_FRAMES = 12;
	private static final int WALK_FRAMES_LIMIT = 90;
	private static final int FADE_FRAMES_LIMIT = 40;
	private static final int FADE_STILL_AFTER_FRAMES = 4;
	private static final int EMPTY_FRAMES = 10;
	private static final int FIGURE_START = -12;
	private static final int POD_BESIDE_RAIL = 1;
	private static final int PLAYER_AT = 8;

	@Override
	protected String name() {
		return "m2-lampless-figure";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(40);
			LamplessFigure[] figure = {null};
			PodEntity[] pod = {null};
			singleplayer.getServer().runOnServer(server -> {
				server.getGameRules().set(GameRules.SPAWN_MONSTERS, false, server);
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				ServerLevel level = player.level();
				BlockPos floor = player.blockPosition().below();
				buildRailLine(level, floor);
				// Facing north (yaw 180), down the rails, with the pod ahead, beside them, and the figure beyond it.
				player.teleportTo(level, floor.getX() + 0.5, floor.getY() + 1, floor.getZ() + PLAYER_AT + 0.5, Set.of(), 180f, 0f, true);
				player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
				Charters.found(server, player.getUUID(), "Demo Charter");
				pod[0] = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
				pod[0].setPos(Vec3.atBottomCenterOf(floor.above().east(POD_BESIDE_RAIL)));
				level.addFreshEntity(pod[0]);
				PodComponents.register(pod[0], Charters.charterOf(server, player.getUUID()).orElseThrow().id());
				figure[0] = CreatureRegistry.LAMPLESS_FIGURE.create(level, EntitySpawnReason.COMMAND);
				figure[0].setPos(Vec3.atBottomCenterOf(floor.above().south(FIGURE_START)));
				figure[0].setHeading(Direction.SOUTH);
				level.addFreshEntity(figure[0]);
			});
			context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
			context.waitTicks(40);
			record(context, DARK_FRAMES);
			screenshot(context, "figure-in-the-dark");

			singleplayer.getServer().runOnServer(server -> {
				CharterId charter = PodComponents.registration(pod[0]).orElseThrow().owner();
				PodComponents.install(pod[0], ComponentItems.mint(server, ComponentTrack.LIGHTS, 2, charter));
			});
			int walked = 0;
			while (!isFading(singleplayer, figure[0]) && walked++ < WALK_FRAMES_LIMIT) {
				record(context, 1);
			}
			if (walked > WALK_FRAMES_LIMIT) {
				throw new AssertionError("the figure had not begun to fade " + WALK_FRAMES_LIMIT + " frames after the pod's lights came on");
			}
			record(context, FADE_STILL_AFTER_FRAMES);
			screenshot(context, "figure-fading-by-the-lit-pod");
			int fading = 0;
			while (!isRemoved(singleplayer, figure[0]) && fading++ < FADE_FRAMES_LIMIT) {
				record(context, 1);
			}
			if (fading > FADE_FRAMES_LIMIT) {
				throw new AssertionError("the figure was still there " + FADE_FRAMES_LIMIT + " frames after it began to fade");
			}
			record(context, EMPTY_FRAMES);
			screenshot(context, "figure-gone");
		}
	}

	private static boolean isFading(TestSingleplayerContext singleplayer, LamplessFigure figure) {
		boolean[] fading = {false};
		singleplayer.getServer().runOnServer(server -> fading[0] = figure.isFading());
		return fading[0];
	}

	private static boolean isRemoved(TestSingleplayerContext singleplayer, LamplessFigure figure) {
		boolean[] removed = {false};
		singleplayer.getServer().runOnServer(server -> removed[0] = figure.isRemoved());
		return removed[0];
	}

	private void record(ClientGameTestContext context, int frames) {
		for (int i = 0; i < frames; i++) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
	}

	/** A closed, timbered drift along z with a single rail down its middle, on a floor of coarse dirt, as in Prospector's Run. */
	private static void buildRailLine(ServerLevel level, BlockPos floor) {
		for (int dz = -HALF_LENGTH - 1; dz <= HALF_LENGTH + 1; dz++) {
			for (int dx = -HALF_WIDTH - 1; dx <= HALF_WIDTH + 1; dx++) {
				for (int dy = 0; dy <= HEIGHT + 1; dy++) {
					boolean wall = Math.abs(dz) == HALF_LENGTH + 1 || Math.abs(dx) == HALF_WIDTH + 1;
					BlockState state = dy == 0 ? Blocks.COARSE_DIRT.defaultBlockState()
							: dy == HEIGHT + 1 ? Blocks.OAK_PLANKS.defaultBlockState()
							: wall ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
					level.setBlock(floor.offset(dx, dy, dz), state, 3);
				}
			}
			level.setBlock(floor.offset(0, 1, dz), Math.abs(dz) == HALF_LENGTH + 1 ? Blocks.STONE.defaultBlockState() : Blocks.RAIL.defaultBlockState(), 3);
		}
		for (int dz = -HALF_LENGTH; dz <= HALF_LENGTH; dz += 8) {
			for (int dx = -HALF_WIDTH; dx <= HALF_WIDTH; dx += 2 * HALF_WIDTH) {
				for (int dy = 1; dy <= HEIGHT; dy++) {
					level.setBlock(floor.offset(dx, dy, dz), Blocks.OAK_LOG.defaultBlockState(), 3);
				}
			}
		}
	}
}
