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
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Views down shafts at the bottom of the campaign world, for {@code BreachVoidClientTest} and the {@code nadir-cover} scenario, so the
 * two cannot drift: the wait that stands the camera at a view until the client has drawn it, and the deep shafts of #1188, each
 * deeper than {@link #RENDER_DISTANCE}, where a straight-down view looks past the sections the client draws. The scenario also
 * stands over open ground on the surface ({@link #ground}).
 */
public final class ShaftViews {
    /** The render distance, in chunks, for the deep shafts: the client draws the sections this many above and below the camera's. */
    public static final int RENDER_DISTANCE = 2;

    private static final double EYE = 1.62;
    /** Each deep shaft's walls stand this many blocks out from its middle each way, so the middle of a picture looks past their drawn ends. */
    public static final int RADIUS = 10;
    /** The eye is at least this far above a deep shaft's floor: past the sections drawn under the camera's, which end at most 48 blocks down. */
    private static final int EYE_HEIGHT = 80;
    /**
     * The eye's block is this many blocks up its section, so the walls' drawn ends lie {@code RENDER_DISTANCE} sections and this far (and
     * half a block) under the eye, well past the render-distance fog's end: the walls' last blocks are the fog colour.
     */
    private static final int EYE_IN_SECTION = 12;
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

    /**
     * A deep shaft: its name, its level, the X and Z of its middle, and the Y of its floor (the bottom of the world for the breach).
     */
    public record Shaft(String name, ResourceKey<Level> level, int x, int z, int floorY) {
        /** The camera's eye, over the middle of the shaft. */
        public Vec3 eye() {
            return new Vec3(x + 0.5, eyeY() + 0.5, z + 0.5);
        }

        /** A point straight under the eye, on the floor: what the camera looks at. */
        public Vec3 below() {
            return new Vec3(x + 0.5, floorY, z + 0.5);
        }

        /** How far under the eye the walls' drawn ends are: the bottom of the lowest section the client draws. */
        public double drawnEndDrop() {
            return eye().y - SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(eyeY()) - RENDER_DISTANCE);
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

        private int eyeY() {
            int lowest = floorY + EYE_HEIGHT;
            return lowest + Math.floorMod(EYE_IN_SECTION - lowest, SectionPos.SECTION_SIZE);
        }

        private List<BlockPos> lamps() {
            int y = eyeY() - LAMP_BELOW_EYE;
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
        ResourceKey<Level> key = level.dimension();
        List<Shaft> shafts = List.of(
                new Shaft("layer-1-floor", key, x, z, TestLayers.band(level, 1).minY() + CampaignBands.crustRows()),
                new Shaft(
                        "layer-2-floor",
                        key,
                        x,
                        z + SPACING,
                        TestLayers.band(level, 2).minY() + CampaignBands.crustRows()),
                new Shaft("breach", key, x, z + 2 * SPACING, bottom));
        for (Shaft shaft : shafts) {
            RoomCarver.carve(
                    level,
                    new BlockPos(shaft.x() - RADIUS, shaft.floorY(), shaft.z() - RADIUS),
                    new BlockPos(shaft.x() + RADIUS, shaft.eyeY() + HEADROOM, shaft.z() + RADIUS),
                    Blocks.AIR.defaultBlockState());
            for (BlockPos lamp : shaft.lamps()) {
                level.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), 3);
            }
        }
        return shafts;
    }

    /**
     * Puts the first player's eye at {@code eye} in the campaign {@code level}, looking at {@code target}, and waits until it has arrived,
     * the client holds and has built every section of {@code held}, and the server's light is settled.
     */
    public static void stand(
            ClientGameTestContext context,
            TestServerContext server,
            ResourceKey<Level> level,
            Vec3 eye,
            Vec3 target,
            ClientLight.Box held,
            String name) {
        float[] angles = anglesTo(eye, target);
        server.runOnServer(s -> s.getPlayerList()
                .getPlayers()
                .getFirst()
                .teleportTo(campaign(s, level), eye.x, eye.y - EYE, eye.z, Set.of(), angles[0], angles[1], true));
        ClientWait.until(
                context,
                "the camera at " + name,
                client -> client.player != null
                        && client.player.level().dimension().equals(level)
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
                                s -> !campaign(s, level).getLightEngine().hasLightWork()),
                () -> "chunks still rendering or light still settling");
    }

    /** Gives the first player night vision, or takes every effect away. */
    public static void nightVision(TestServerContext server, boolean on) {
        server.runOnServer(s -> {
            ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
            player.removeAllEffects();
            if (on) {
                player.addEffect(new MobEffectInstance(
                        MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
            }
        });
    }

    /** The top of the ground at ({@code x}, {@code z}) in {@code level}, generating its chunk first. */
    public static BlockPos ground(ServerLevel level, int x, int z) {
        // sync-load: a client test calls this on the singleplayer server with no GameTestHelper to wait with
        level.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(x, 0, z));
    }

    /** Yaw and pitch, in degrees, of a camera at {@code eye} that looks at {@code target}. */
    public static float[] anglesTo(Vec3 eye, Vec3 target) {
        Vec3 d = target.subtract(eye);
        return new float[] {
            (float) Math.toDegrees(Math.atan2(-d.x, d.z)),
            (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)))
        };
    }

    private static ServerLevel campaign(MinecraftServer server, ResourceKey<Level> key) {
        ServerLevel level = server.getLevel(key);
        if (level == null || !LayerChain.isCampaign(level)) {
            throw new IllegalArgumentException(key.identifier()
                    + " is not a campaign world: the views stand in the campaign level, which holds the layers");
        }
        return level;
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
