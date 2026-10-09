package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;

/**
 * Evidence scenario "pod-drill-lit": the real player drills a pod down through three slabs of stone with ore in them, in a
 * lit room. The player has night vision, as in the layer stills of the design tour, so the bore can be seen. The pilot only
 * holds sprint. Short on purpose: it feeds the README tour.
 */
public class PodDrillLitScenario extends EvidenceScenario {
	private static final int X = 4000;
	private static final int Z = 4000;
	private static final int FLOOR_Y = 4;
	private static final int SLABS = 3;
	private static final int TICKS_PER_FRAME = 6;
	private static final int MAX_TICKS = 1500;
	private static final int END_FRAMES = 6;
	/** The first frame after the player mounts is tinted tan, so the recording starts later. */
	private static final int SETTLE_TICKS = 80;
	private static final float LOOK_DOWN = 55f;

	@Override
	protected String name() {
		return "pod-drill-lit";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(40);
			singleplayer.getServer().runOnServer(server -> {
				ServerLevel one = server.getLevel(LayerChain.dimension(1));
				buildBoreRoom(one);
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
				player.teleportTo(one, X, FLOOR_Y, Z, Set.of(), 0, LOOK_DOWN, true);
				PodEntity pod = PodRegistry.POD.create(one, EntitySpawnReason.COMMAND);
				pod.setPos(X, FLOOR_Y, Z);
				pod.setFuel(100f);
				one.addFreshEntity(pod);
				if (!player.startRiding(pod)) {
					throw new AssertionError("the player could not mount the pod");
				}
			});
			context.waitFor(client -> client.player != null && client.player.getVehicle() instanceof PodEntity
					&& client.level.dimension().equals(LayerChain.dimension(1)));
			context.runOnClient(client -> {
				client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
				client.player.setXRot(LOOK_DOWN);
			});
			context.waitTicks(SETTLE_TICKS);
			frame(context);

			context.getInput().holdKey(options -> options.keySprint);
			int ticks = 0;
			boolean shot = false;
			while (podY(context) > FLOOR_Y - SLABS && ticks < MAX_TICKS) {
				context.waitTicks(TICKS_PER_FRAME);
				ticks += TICKS_PER_FRAME;
				frame(context);
				if (!shot && podY(context) <= FLOOR_Y - 1) {
					screenshot(context, "pod-drill-lit-drilling");
					shot = true;
				}
			}
			context.getInput().releaseKey(options -> options.keySprint);
			if (podY(context) > FLOOR_Y - SLABS) {
				throw new AssertionError("The pod did not drill " + SLABS + " slabs within " + MAX_TICKS + " ticks");
			}
			for (int i = 0; i < END_FRAMES; i++) {
				context.waitTicks(TICKS_PER_FRAME);
				frame(context);
			}
		}
	}

	private static double podY(ClientGameTestContext context) {
		return context.computeOnClient(client -> client.player.getVehicle().getY());
	}

	/** Slabs of stone under the pod with ore in the column it drills, an open room above, and glowstone on the walls. */
	private static void buildBoreRoom(ServerLevel level) {
		RoomCarver.carve(level, X - 5, X + 5, 0, FLOOR_Y - 1, Z - 5, Z + 5, Blocks.STONE);
		// Worldgen scatters lava through layer 1's rock, and lava beside the room would flow in and into the bore, so carve it sealed.
		RoomCarver.carve(level, new BlockPos(X - 5, FLOOR_Y, Z - 5), new BlockPos(X + 5, FLOOR_Y + 9, Z + 5), Blocks.AIR.defaultBlockState(),
				Block.UPDATE_ALL);
		OreType[] ores = {OreType.IRONIUM, OreType.BRONZIUM, OreType.GOLDIUM};
		for (int i = 0; i < ores.length; i++) {
			Block ore = BuiltInRegistries.BLOCK.getValue(ores[i].blockId());
			level.setBlock(new BlockPos(X, FLOOR_Y - 1 - i, Z), ore.defaultBlockState(), 3);
			level.setBlock(new BlockPos(X + 1 + i % 2, FLOOR_Y - 1 - i, Z - 1), ore.defaultBlockState(), 3);
		}
		for (BlockPos lamp : new BlockPos[] {
				new BlockPos(X + 5, FLOOR_Y + 2, Z), new BlockPos(X - 5, FLOOR_Y + 2, Z),
				new BlockPos(X, FLOOR_Y + 2, Z + 5), new BlockPos(X, FLOOR_Y + 2, Z - 5)}) {
			level.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), 3);
		}
	}
}
