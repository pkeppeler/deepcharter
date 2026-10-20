package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.beam.BeamDraw;
import io.github.pkeppeler.deepcharter.client.beam.FloodlightBeams;
import io.github.pkeppeler.deepcharter.client.theme.BeamLook;
import io.github.pkeppeler.deepcharter.client.theme.BeamStyle;
import io.github.pkeppeler.deepcharter.colony.ColonyKit;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/**
 * Client GameTest for the colony floodlight beams (#248). A floodlight is a plain block, so the client finds it with a look every
 * second: a floodlight set on the server is picked up, its beam starts where the lens is and points the way the block faces, a wall
 * shortens it at the next look, and it is gone the moment the block is broken.
 */
public class FloodlightBeamsClientTest implements FabricClientGameTest {
    private static final double TOLERANCE = 0.05;
    private static final int ROOM = 30;
    /** The lamp hangs this high over the floor, so that the tilted beam does not meet the floor inside its reach. */
    private static final int HEIGHT = 40;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            BlockPos lamp = singleplayer.getServer().computeOnServer(FloodlightBeamsClientTest::setUp);
            ClientWait.until(
                    context,
                    "the floodlight on the client",
                    client -> client.level != null
                            && client.level.getBlockState(lamp).is(ColonyKit.FLOODLIGHT));
            // Facing south: the lens looks along +z.
            BeamStyle style =
                    context.computeOnClient(client -> BeamLook.current().floodlight());
            double pitch = Math.toRadians(style.pitch());
            double originZ = lamp.getZ() + 0.5 + style.forward();

            ClientWait.until(
                    context,
                    "the client to find the floodlight within its look",
                    client -> FloodlightBeams.beamAt(lamp) != null);
            BeamDraw open = Objects.requireNonNull(
                    context.computeOnClient(client -> FloodlightBeams.beamAt(lamp)),
                    "the floodlight beam is not drawn");
            require(
                    Math.abs(open.length() - style.length()) < TOLERANCE,
                    "a floodlight in open air is drawn at its full length " + style.length() + ", drawn "
                            + open.length());

            // A wall of stone across the way, six blocks south of the block.
            int wallZ = lamp.getZ() + 6;
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel level = server.overworld();
                for (int dx = -8; dx <= 8; dx++) {
                    for (int dy = -12; dy <= 4; dy++) {
                        level.setBlock(lamp.offset(dx, dy, 6), Blocks.STONE.defaultBlockState(), 3);
                    }
                }
            });
            // The beam meets the wall's north face, at z = wallZ, along its slant.
            double expected = (wallZ - originZ) / Math.cos(pitch);
            ClientWait.until(
                    context,
                    "the next look to cut the floodlight's beam at the wall",
                    client -> {
                        BeamDraw beam = FloodlightBeams.beamAt(lamp);
                        return beam != null && Math.abs(beam.length() - expected) < TOLERANCE;
                    },
                    client -> {
                        BeamDraw beam = FloodlightBeams.beamAt(lamp);
                        return "the beam was "
                                + (beam == null ? "not found" : beam.length() + " long, not " + expected);
                    });

            // Broken, it loses its beam at once: on the very client tick that sees the block gone, before the next
            // look, so a list that
            // is only trusted until that look would still hand the beam out.
            boolean[] beamAfterBreak = {false};
            singleplayer
                    .getServer()
                    .runOnServer(server -> server.overworld().setBlock(lamp, Blocks.AIR.defaultBlockState(), 3));
            ClientWait.until(context, "the client to see the floodlight broken", client -> {
                if (!ClientLight.clientLevel(client).getBlockState(lamp).isAir()) {
                    return false;
                }
                beamAfterBreak[0] = FloodlightBeams.beamAt(lamp) != null;
                return true;
            });
            require(
                    !beamAfterBreak[0],
                    "a broken floodlight must lose its beam as soon as the client sees the block gone");
        }
    }

    /** A box of air in the sky above the spawn with a floor, and a floodlight facing south high in it: its position. */
    private static BlockPos setUp(net.minecraft.server.MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        ServerLevel level = player.level();
        BlockPos floor = player.blockPosition().offset(0, 80, 0);
        for (int dx = -ROOM; dx <= ROOM; dx++) {
            for (int dz = -ROOM; dz <= ROOM; dz++) {
                level.setBlock(floor.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 3);
                for (int dy = 1; dy <= HEIGHT + 4; dy++) {
                    level.setBlock(floor.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        player.teleportTo(level, floor.getX() + 0.5, floor.getY() + 1, floor.getZ() - 12.5, Set.of(), 0f, 0f, true);
        BlockPos lamp = floor.above(HEIGHT);
        level.setBlock(
                lamp,
                ColonyKit.FLOODLIGHT.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH),
                3);
        return lamp;
    }
}
