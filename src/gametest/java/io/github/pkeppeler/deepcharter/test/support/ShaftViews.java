package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Views down shafts at the bottom of the campaign world, for {@code BreachVoidClientTest} and the {@code nadir-cover} scenario, so the
 * two cannot drift: the wait that stands the camera at a view until the client has drawn it, and the deep shafts of #1188, each deeper
 * than {@link #RENDER_DISTANCE}, where a straight-down view looks past the sections the client draws.
 */
public final class ShaftViews {
    /** The render distance, in chunks, for the deep shafts: the client draws the sections this many above and below the camera's. */
    public static final int RENDER_DISTANCE = 2;

    private static final double EYE = 1.62;
    /** Each deep shaft is this many blocks out from its middle each way, so the middle of a picture looks past the walls' drawn ends. */
    private static final int RADIUS = 10;
    /** The eye is this far above a deep shaft's floor: past the sections drawn under the camera's, which end at most 48 blocks down. */
    private static final int EYE_HEIGHT = 80;
    /** The shaft's air goes on this far above the eye. */
    private static final int HEADROOM = 4;
    /**
     * A glowstone lamp in each side wall of a deep shaft stands this far under the eye, on the screen's left and right edges. It stands out
     * from a dark fog, and the rock round it from a pale one or one that night vision lifts.
     */
    private static final int LAMP_BELOW_EYE = 12;
    /** The deep shafts stand this far apart along Z, so each is out of the others' drawn chunks. */
    private static final int SPACING = 200;

    private ShaftViews() {}

    /** A deep shaft: its name, the X and Z of its middle, and the Y of its floor (the bottom of the world for the breach). */
    public record Shaft(String name, int x, int z, int floorY) {
        /** The camera's eye, over the middle of the shaft. */
        public Vec3 eye() {
            return new Vec3(x + 0.5, floorY + EYE_HEIGHT + 0.5, z + 0.5);
        }

        /** A point straight under the eye, on the floor: what the camera looks at. */
        public Vec3 below() {
            return eye().subtract(0, EYE_HEIGHT, 0);
        }

        /** The faces of the two lamps that look into the shaft. */
        public List<Vec3> landmarks() {
            List<BlockPos> lamps = lamps();
            BlockPos west = lamps.get(0);
            BlockPos east = lamps.get(1);
            return List.of(
                    new Vec3(west.getX() + 1, west.getY() + 0.5, west.getZ() + 0.5),
                    new Vec3(east.getX(), east.getY() + 0.5, east.getZ() + 0.5));
        }

        /** The blocks round the eye the client must hold and have built: the shaft down to the last section it draws. */
        public ClientLight.Box held() {
            return ClientLight.Box.around(
                    BlockPos.containing(eye()), RADIUS + 1, RENDER_DISTANCE * SectionPos.SECTION_SIZE, HEADROOM);
        }

        private List<BlockPos> lamps() {
            int y = floorY + EYE_HEIGHT - LAMP_BELOW_EYE;
            return List.of(new BlockPos(x - RADIUS - 1, y, z), new BlockPos(x + RADIUS + 1, y, z));
        }
    }

    /**
     * Carves the deep shafts in the campaign {@code level}, from ({@code x}, {@code z}) south: one down to the floor of layer 1 (the top of
     * its crust), one to the floor of layer 2, and one through the bottom of the world (the breach, where {@code BreachVoidCover} lies).
     * Each has a lamp in its west and east walls, under the eye.
     */
    public static List<Shaft> open(ServerLevel level, int x, int z) {
        int bottom = level.getMinY(); // level-height: the breach shaft opens through the bottom of the whole world
        List<Shaft> shafts = List.of(
                new Shaft("layer-1-floor", x, z, TestLayers.band(level, 1).minY() + CampaignBands.crustRows()),
                new Shaft(
                        "layer-2-floor",
                        x,
                        z + SPACING,
                        TestLayers.band(level, 2).minY() + CampaignBands.crustRows()),
                new Shaft("breach", x, z + 2 * SPACING, bottom));
        for (Shaft shaft : shafts) {
            RoomCarver.carve(
                    level,
                    new BlockPos(shaft.x() - RADIUS, shaft.floorY(), shaft.z() - RADIUS),
                    new BlockPos(shaft.x() + RADIUS, shaft.floorY() + EYE_HEIGHT + HEADROOM, shaft.z() + RADIUS),
                    Blocks.AIR.defaultBlockState());
            for (BlockPos lamp : shaft.lamps()) {
                level.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), 3);
            }
        }
        return shafts;
    }

    /**
     * Runs {@code body} with the client's render distance at {@link #RENDER_DISTANCE}, and puts it back in a finally: every client test
     * shares one client. ({@code CavernSky} of #1172 has its own copy for its hall; whichever lands second may share one.)
     */
    public static void withShortRenderDistance(ClientGameTestContext context, Runnable body) {
        int was = context.computeOnClient(
                client -> client.options.renderDistance().get());
        try {
            context.runOnClient(client -> client.options.renderDistance().set(RENDER_DISTANCE));
            body.run();
        } finally {
            context.runOnClient(client -> client.options.renderDistance().set(was));
        }
    }

    /**
     * Puts the first player's eye at {@code eye} in the campaign level, looking at {@code target}, and waits until it has arrived, the
     * client holds and has built every section of {@code held}, and the server's light is settled.
     */
    public static void stand(
            ClientGameTestContext context,
            TestServerContext server,
            Vec3 eye,
            Vec3 target,
            ClientLight.Box held,
            String name) {
        float[] angles = anglesTo(eye, target);
        server.runOnServer(s -> s.getPlayerList()
                .getPlayers()
                .getFirst()
                .teleportTo(campaign(s), eye.x, eye.y - EYE, eye.z, Set.of(), angles[0], angles[1], true));
        ClientWait.until(
                context,
                "the camera at " + name,
                client -> client.player != null
                        && LayerChain.isCampaign(client.player.level())
                        && ClientSession.player(client).distanceToSqr(eye.x, eye.y - EYE, eye.z) < 0.0001
                        && Math.abs(Mth.wrapDegrees(ClientSession.player(client).getYRot() - angles[0])) < 0.01f
                        && Math.abs(ClientSession.player(client).getXRot() - angles[1]) < 0.01f
                        && ClientLight.chunksPresent(ClientLight.clientLevel(client), held));
        ClientWait.until(
                context,
                "the view " + name + " rendered and lit",
                () -> context.computeOnClient(client -> client.gui.screen() == null
                                && client.levelRenderer.hasRenderedAllSections()
                                && sectionsCompiled(client, held))
                        && server.computeOnServer(
                                s -> !campaign(s).getLightEngine().hasLightWork()),
                () -> "chunks still rendering or light still settling");
    }

    /** Yaw and pitch, in degrees, of a camera at {@code eye} that looks at {@code target}. */
    public static float[] anglesTo(Vec3 eye, Vec3 target) {
        Vec3 d = target.subtract(eye);
        return new float[] {
            (float) Math.toDegrees(Math.atan2(-d.x, d.z)),
            (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)))
        };
    }

    private static ServerLevel campaign(MinecraftServer server) {
        return TestLayers.level(server, 1);
    }

    /**
     * Whether the client has a compiled mesh for every section of {@code held}. Its compile queue can be empty while the sections round a
     * camera that has just moved are not yet queued, and a frame then lacks walls (it showed the cover where rock should be).
     */
    private static boolean sectionsCompiled(Minecraft client, ClientLight.Box held) {
        for (int x = SectionPos.blockToSectionCoord(held.min().getX());
                x <= SectionPos.blockToSectionCoord(held.max().getX());
                x++) {
            for (int y = SectionPos.blockToSectionCoord(held.min().getY());
                    y <= SectionPos.blockToSectionCoord(held.max().getY());
                    y++) {
                for (int z = SectionPos.blockToSectionCoord(held.min().getZ());
                        z <= SectionPos.blockToSectionCoord(held.max().getZ());
                        z++) {
                    // A fade of 1 ms: chunkSectionFadeInTime is 0 in the test client, so only the compiled mesh counts.
                    if (!client.levelRenderer.isSectionCompiledAndVisible(
                            SectionPos.of(x, y, z).origin(), 1L)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
