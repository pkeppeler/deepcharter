package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Evidence scenario "m2-surface-breach": a player stands on the surface, then in the campaign level drops through a shaft in the floor
 * of the surface band into layer 1, and climbs out of the top of layer 1 back into the surface band. Each crossing is in place and is
 * recorded frame by frame, and stills show the surface and the shaft.
 */
public class SurfaceBreachScenario extends EvidenceScenario {
    private static final double X = 2000.5;
    private static final double Z = 2000.5;
    /** The shaft in the surface band is open from the floor up to this many blocks above it. */
    private static final int SHAFT_HEIGHT = 16;

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
            awaitWorld(context);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setPermanentlyInvulnerable(true);
                // A transmission goes to a charter: the climb out into the surface brings the
                // surface-arrival transmission.
                if (Charters.found(server, player.getUUID(), "Surface Crew").isPresent()) {
                    throw new AssertionError("founding the charter should succeed");
                }
                player.addEffect(new MobEffectInstance(
                        MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
            });

            // The surface, with its sky.
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel surface = server.overworld();
                // The height of a chunk that is not loaded reads as the floor, so load it first.
                surface.getChunk((int) X >> 4, (int) Z >> 4);
                int y = surface.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) X, (int) Z);
                server.getPlayerList().getPlayers().getFirst().teleportTo(surface, X, y, Z, Set.of(), 0, -10, true);
            });
            ClientWait.until(
                    context, "the client on the surface", client -> TestLayers.clientIsIn(client, LayerChain.SURFACE));
            context.waitTicks(SETTLE_TICKS);
            screenshot(context, "overworld-surface");
            for (int i = 0; i < SURFACE_FRAMES; i++) {
                frame(context);
                context.waitTick();
            }

            // Down the open floor of the surface band, looking at it: the campaign level holds the surface and layer 1
            // as bands, so the
            // fall is a crossing in place. The shaft is cut in the surface band's rock, over a pocket in the top of
            // layer 1.
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel level = TestLayers.level(server, 1);
                BlockPos column = BlockPos.containing(X, 0, Z);
                int floor = TestLayers.band(level, TestLayers.SURFACE).minY();
                RoomCarver.carvePocketBelow(
                        level, TestLayers.SURFACE, column.getX(), column.getX(), column.getZ(), column.getZ());
                RoomCarver.carve(
                        level,
                        column.atY(floor),
                        column.atY(floor + SHAFT_HEIGHT),
                        Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_ALL);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.teleportTo(level, X, floor + SHAFT_HEIGHT - 1, Z, Set.of(), 0, 70, true);
                player.setNoGravity(true);
            });
            ClientWait.until(
                    context,
                    "the client in the surface band",
                    client -> TestLayers.clientIsIn(client, LayerChain.SURFACE));
            context.waitTicks(SETTLE_TICKS);
            // The crossing is read from the band last seen, which the server has now noted; gravity takes the player
            // down the shaft.
            singleplayer
                    .getServer()
                    .runOnServer(server ->
                            server.getPlayerList().getPlayers().getFirst().setNoGravity(false));
            recordUntilInLayerOne(context, "falling into layer 1");
            screenshot(context, "layer-1-arrival");

            // Up and out of the top of layer 1.
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.getAbilities().mayfly = true;
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
            });
            climbOutOfLayerOne(context, singleplayer);
            recordUntilTransmissionTyped(context, "climbing back to the surface band");
            // Look around the shaft, once the client has the chunks around it.
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.teleportTo(player.level(), player.getX(), player.getY(), player.getZ(), Set.of(), 40, 15, true);
            });
            context.waitTicks(SETTLE_TICKS);
            screenshot(context, "surface-floor-shaft");
        }
    }

    private void climbOutOfLayerOne(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        while (singleplayer.getServer().computeOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            ServerLevel level = player.level();
            if (!TestLayers.isIn(player, 1)) {
                return false;
            }
            // The crossing up is a few rows over the surface floor (ADR 0061), and the climb stops there.
            double y = Math.min(
                    player.getY() + CLIMB_STEP,
                    TestLayers.band(level, TestLayers.SURFACE).minY() + LayerTuning.DEFAULT.crustThickness() + 1);
            player.teleportTo(level, X, y, Z, Set.of(), 0, -80, true);
            return true;
        })) {
            context.waitTicks(TICKS_PER_FRAME);
            if (context.computeOnClient(client -> client.gui.screen() == null)) {
                frame(context);
            }
        }
    }

    /** Records a frame per tick until the player has crossed into layer 1, and a few more. A crossing in place has no fade to wait for. */
    private void recordUntilInLayerOne(ClientGameTestContext context, String what) {
        int arrivedAt = -1;
        for (int tick = 0; tick < PATIENCE; tick++) {
            if (context.computeOnClient(client -> client.gui.screen() == null)) {
                frame(context);
            }
            if (arrivedAt < 0 && context.computeOnClient(client -> TestLayers.clientIsIn(client, 1))) {
                arrivedAt = tick;
            }
            if (arrivedAt >= 0 && tick - arrivedAt >= TAIL_FRAMES) {
                return;
            }
            context.waitTick();
        }
        throw new AssertionError("The player never reached layer 1 within " + PATIENCE + " frames of " + what);
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
