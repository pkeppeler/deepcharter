package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.layer.BreachEffects;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;

/**
 * Evidence scenario "m2-surface-breach": a player stands on the surface, drops through the open floor of the
 * overworld into layer 1, then climbs out of the top of layer 1 and arrives back in the overworld. The crossing
 * is recorded frame by frame, and stills show the surface and the pocket the return opens in the rock.
 */
public class SurfaceBreachScenario extends EvidenceScenario {
	private static final double X = 2000.5;
	private static final double Z = 2000.5;
	/** The shaft in the overworld is open from the floor up to this many blocks above it. */
	private static final int SHAFT_HEIGHT = 16;
	private static final int ROCK_RADIUS = 10;
	private static final int ROCK_HEIGHT = 12;
	/** Frames of the surface at the start, before the fall. */
	private static final int SURFACE_FRAMES = 10;
	private static final int PATIENCE = 400;
	/** Frames recorded once the transmission has finished typing. */
	private static final int TAIL_FRAMES = 12;
	/** The climb out of layer 1: blocks gained and server ticks waited per frame. */
	private static final double CLIMB_STEP = 1.5;
	private static final int TICKS_PER_FRAME = 2;
	private static final int SETTLE_TICKS = 40;

	@Override
	protected String name() {
		return "m2-surface-breach";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitFor(client -> client.player != null && client.level != null);
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.setPermanentlyInvulnerable(true);
				// A transmission goes to a charter: the climb out into the surface brings the surface-arrival transmission.
				if (Charters.found(server, player.getUUID(), "Surface Crew").isPresent()) {
					throw new AssertionError("founding the charter should succeed");
				}
				player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
			});

			// The surface, with its sky.
			singleplayer.getServer().runOnServer(server -> {
				ServerLevel surface = server.overworld();
				// The height of a chunk that is not loaded reads as the floor, so load it first.
				surface.getChunk((int) X >> 4, (int) Z >> 4);
				int y = surface.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) X, (int) Z);
				server.getPlayerList().getPlayers().getFirst().teleportTo(surface, X, y, Z, Set.of(), 0, -10, true);
			});
			context.waitFor(client -> client.level.dimension().equals(LayerChain.dimension(LayerChain.SURFACE)));
			context.waitTicks(SETTLE_TICKS);
			screenshot(context, "overworld-surface");
			for (int i = 0; i < SURFACE_FRAMES; i++) {
				frame(context);
				context.waitTick();
			}

			// Down the overworld's open floor, looking at it.
			singleplayer.getServer().runOnServer(server -> {
				ServerLevel surface = server.overworld();
				BlockPos column = BlockPos.containing(X, 0, Z);
				for (int y = surface.getMinY(); y <= surface.getMinY() + SHAFT_HEIGHT; y++) {
					// room-carver: opens the overworld floor, which is the surface and not layer rock
					surface.setBlock(column.atY(y), Blocks.AIR.defaultBlockState(), 3);
				}
				// Generate the arrival area in layer 1 now so the client has less to wait for after the crossing.
				ServerLevel one = server.getLevel(LayerChain.dimension(1));
				for (int dx = -2; dx <= 2; dx++) {
					for (int dz = -2; dz <= 2; dz++) {
						one.getChunk(column.getX() / 16 + dx, column.getZ() / 16 + dz);
					}
				}
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.teleportTo(surface, X, surface.getMinY() + SHAFT_HEIGHT - 1, Z, Set.of(), 0, 70, true);
			});
			recordUntilFadeEnds(context, "falling into layer 1");
			screenshot(context, "layer-1-arrival");

			// Up and out of the top of layer 1.
			singleplayer.getServer().runOnServer(server -> {
				ServerLevel surface = server.overworld();
				BlockPos column = BlockPos.containing(X, 0, Z);
				for (int dx = -2; dx <= 2; dx++) {
					for (int dz = -2; dz <= 2; dz++) {
						surface.getChunk(column.getX() / 16 + dx, column.getZ() / 16 + dz);
					}
				}
				// The dev world is flat, with its ground right at the floor. Give the return real rock to open a pocket in.
				for (int dx = -ROCK_RADIUS; dx <= ROCK_RADIUS; dx++) {
					for (int dz = -ROCK_RADIUS; dz <= ROCK_RADIUS; dz++) {
						for (int y = surface.getMinY(); y < surface.getMinY() + ROCK_HEIGHT; y++) {
							surface.setBlock(column.offset(dx, 0, dz).atY(y), Blocks.STONE.defaultBlockState(), 3);
						}
					}
				}
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.getAbilities().mayfly = true;
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
			});
			climbOutOfLayerOne(context, singleplayer);
			recordUntilTransmissionTyped(context, "climbing back to the overworld");
			// Look around the pocket, once the client has the chunks around it.
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.teleportTo(player.level(), player.getX(), player.getY(), player.getZ(), Set.of(), 40, 15, true);
			});
			context.waitTicks(SETTLE_TICKS);
			screenshot(context, "overworld-floor-pocket");
		}
	}

	private void climbOutOfLayerOne(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		while (singleplayer.getServer().computeOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			ServerLevel level = player.level();
			if (!level.dimension().equals(LayerChain.dimension(1))) {
				return false;
			}
			double y = Math.min(player.getY() + CLIMB_STEP, level.getMaxY() + LayerTuning.DEFAULT.pocketRadius());
			player.teleportTo(level, X, y, Z, Set.of(), 0, -80, true);
			return true;
		})) {
			context.waitTicks(TICKS_PER_FRAME);
			if (context.computeOnClient(client -> client.gui.screen() == null)) {
				frame(context);
			}
		}
	}

	/** Records a frame per tick until the fade of the crossing that just happened has come and gone. Entering layer 1 has no transmission. */
	private void recordUntilFadeEnds(ClientGameTestContext context, String what) {
		boolean faded = false;
		for (int tick = 0; tick < PATIENCE; tick++) {
			if (context.computeOnClient(client -> client.gui.screen() == null)) {
				frame(context);
			}
			float alpha = context.computeOnClient(client -> BreachEffects.fadeAlpha(0f));
			faded |= alpha > 0;
			if (faded && alpha == 0) {
				return;
			}
			context.waitTick();
		}
		throw new AssertionError("The fade never ended within " + PATIENCE + " frames of " + what);
	}

	/** Records a frame per tick until the transmission of the crossing that just happened has finished typing. */
	private void recordUntilTransmissionTyped(ClientGameTestContext context, String what) {
		int typedAt = -1;
		for (int tick = 0; tick < PATIENCE; tick++) {
			// Vanilla covers the HUD with a "Loading terrain" screen while the client waits for the new level's
			// chunks. It is not part of the effect being shown, so leave those frames out.
			if (context.computeOnClient(client -> client.gui.screen() == null)) {
				frame(context);
			}
			boolean typed = context.computeOnClient(client -> TransmissionOverlay.typed());
			if (typed && typedAt < 0) {
				typedAt = tick;
			}
			if (typedAt >= 0 && tick - typedAt >= TAIL_FRAMES) {
				return;
			}
			context.waitTick();
		}
		throw new AssertionError("The transmission never finished typing within " + PATIENCE + " frames of " + what);
	}
}
